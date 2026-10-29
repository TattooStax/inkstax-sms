package org.fossify.messages.transport

import android.content.Context
import inkstax.sms.transport.DeviceCredential
import inkstax.sms.transport.InboundDeliveryOutcome
import inkstax.sms.transport.InboundDisposition
import inkstax.sms.transport.InboundSms
import inkstax.sms.transport.inboundDeliveryOutcomeFor
import inkstax.sms.transport.inboundDeliveryRequestFor
import inkstax.sms.transport.inboundDispositionFor
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val DELIVERY_CONNECT_TIMEOUT_MILLIS = 10_000
private const val DELIVERY_READ_TIMEOUT_MILLIS = 15_000

/** The result of one inbound delivery run by this binding. */
sealed interface InboundDeliveryBindingResult {
    /**
     * No delivery could be built: this device holds no credential
     * the seam accepts for its own endpoint, so there is no key to
     * deliver with. That is a broken or absent pairing whose
     * recovery is pairing (again), never a link that merely went
     * quiet — the caller must not read it as an unreachable
     * endpoint, and the message stays held.
     */
    data object NoUsableCredential : InboundDeliveryBindingResult

    /**
     * The message itself may not be delivered: the shared
     * disposition rule does not handle it, or it carries no source
     * id the brain's ack could release against. The pairing is not
     * in question here — the caller keeps the message and acts on
     * its disposition, surfacing a not-forwarded arrival the way
     * the local binding already does.
     */
    data object NotDeliverable : InboundDeliveryBindingResult

    /** A delivery was sent and its answer was read the one way. */
    data class Completed(val outcome: InboundDeliveryOutcome) : InboundDeliveryBindingResult
}

/**
 * Delivers one inbound [message] to the brain for this device's
 * stored credential, handed off at [handedOffAtMillis]. This is
 * the binding that performs the delivery the :transport
 * definitions describe: the request is the one
 * [inboundDeliveryRequestFor] builds from the stored credential
 * and the message, and the answer is read the one way
 * [inboundDeliveryOutcomeFor] reads it. Only the brain's ack
 * releases the held message, through the transition the delivery
 * definitions name; every other outcome leaves it held for a
 * redelivery against the same source id.
 *
 * The credential's endpoint is HTTPS (the pairing reader accepts
 * no other scheme for an offer, and a credential is minted only
 * for a scanned offer's endpoint), so the secret travels only
 * over TLS, and only in the authorization value the wire shape
 * defines — never in the body, never logged here.
 *
 * A negative timestamp — the message's receipt time or the
 * hand-off time — is a caller error and throws: an argument
 * fault must never be reported as a missing or broken
 * credential, whose documented recovery is re-pairing, nor as a
 * message that merely may not be delivered.
 *
 * This call blocks on the network, so it belongs on a background
 * thread. A transport fault while asking reads as no answer at
 * all, which the outcome reader names unreachable, and the
 * message stays held. Redirects are never followed: the brain
 * answers on the credential's own endpoint or not at all, and a
 * redirect status is read by the outcome reader as an answer
 * that is not the brain's ack. The answer's body is never read —
 * the ack is defined to carry no body, so the status alone
 * decides.
 */
fun Context.performInboundDelivery(
    message: InboundSms,
    handedOffAtMillis: Long
): InboundDeliveryBindingResult {
    require(message.receivedAtMillis >= 0L) { "receivedAtMillis must not be negative" }
    require(handedOffAtMillis >= 0L) { "handedOffAtMillis must not be negative" }
    val credential = storedDeviceCredential()
        ?: return InboundDeliveryBindingResult.NoUsableCredential
    return performInboundDelivery(credential, message, handedOffAtMillis)
}

/**
 * The same delivery performed with a credential the caller
 * already holds: a caller that read work under a pairing —
 * the held inbound drain reading its queue under the stored
 * credential — delivers with that pairing's credential, never
 * one a disconnect and re-pair may have stored since, so a
 * message taken in under one pairing is only ever sent to
 * that pairing's endpoint.
 */
@Suppress("SwallowedException")
internal fun Context.performInboundDelivery(
    credential: DeviceCredential,
    message: InboundSms,
    handedOffAtMillis: Long
): InboundDeliveryBindingResult {
    require(message.receivedAtMillis >= 0L) { "receivedAtMillis must not be negative" }
    require(handedOffAtMillis >= 0L) { "handedOffAtMillis must not be negative" }
    if (inboundDispositionFor(message).disposition != InboundDisposition.HANDLED ||
        message.sourceId == null
    ) {
        return InboundDeliveryBindingResult.NotDeliverable
    }
    val request = inboundDeliveryRequestFor(credential, message, handedOffAtMillis)
        ?: return InboundDeliveryBindingResult.NoUsableCredential
    var connection: HttpURLConnection? = null
    var statusCode: Int? = null
    try {
        connection = URL(request.url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = DELIVERY_CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = DELIVERY_READ_TIMEOUT_MILLIS
        connection.instanceFollowRedirects = false
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", request.authorization)
        connection.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
        statusCode = connection.responseCode
    } catch (e: IOException) {
        statusCode = null
    } finally {
        connection?.disconnect()
    }
    return InboundDeliveryBindingResult.Completed(inboundDeliveryOutcomeFor(statusCode))
}
