package org.fossify.messages.transport

import android.content.Context
import inkstax.sms.transport.HeartbeatOutcome
import inkstax.sms.transport.heartbeatOutcomeFor
import inkstax.sms.transport.heartbeatRequestFor
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val HEARTBEAT_CONNECT_TIMEOUT_MILLIS = 10_000
private const val HEARTBEAT_READ_TIMEOUT_MILLIS = 15_000

/** The result of one heartbeat run by this binding. */
sealed interface HeartbeatBindingResult {
    /**
     * No beat could be built: this device holds no credential the
     * seam accepts for its own endpoint, so there is no key to beat
     * with. That is a broken or absent pairing whose recovery is
     * pairing (again), never a link that merely went quiet — the
     * caller must not read it as an unreachable endpoint.
     */
    data object NoUsableCredential : HeartbeatBindingResult

    /** A beat was sent and its answer was read the one way. */
    data class Completed(val outcome: HeartbeatOutcome) : HeartbeatBindingResult
}

/**
 * Sends one heartbeat for this device's stored credential, beaten
 * at [sentAtMillis]. This is the binding that performs the beat
 * the :transport definitions describe: the request is the one
 * [heartbeatRequestFor] builds from the stored credential, and
 * the answer is read the one way [heartbeatOutcomeFor] reads it.
 *
 * The credential's endpoint is HTTPS (the pairing reader accepts
 * no other scheme for an offer, and a credential is minted only
 * for a scanned offer's endpoint), so the secret travels only
 * over TLS, and only in the authorization value the wire shape
 * defines — never in the body, never logged here.
 *
 * A negative [sentAtMillis] is a caller error and throws: an
 * argument fault must never be reported as a missing or broken
 * credential, whose documented recovery is re-pairing.
 *
 * This call blocks on the network, so it belongs on a background
 * thread. A transport fault while asking reads as no answer at
 * all, which the outcome reader names unreachable. Redirects are
 * never followed: the brain answers on the credential's own
 * endpoint or not at all, and a redirect status is read by the
 * outcome reader as an answer that is not a recorded beat. The
 * answer's body is never read — the ack is defined to carry no
 * body and no message state, so the status alone decides and no
 * healthy link can be mistaken for a delivered message here.
 */
@Suppress("SwallowedException")
fun Context.performHeartbeat(sentAtMillis: Long): HeartbeatBindingResult {
    require(sentAtMillis >= 0L) { "sentAtMillis must not be negative" }
    val credential = storedDeviceCredential()
        ?: return HeartbeatBindingResult.NoUsableCredential
    val request = heartbeatRequestFor(credential, sentAtMillis)
        ?: return HeartbeatBindingResult.NoUsableCredential
    var connection: HttpURLConnection? = null
    var statusCode: Int? = null
    try {
        connection = URL(request.url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = HEARTBEAT_CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = HEARTBEAT_READ_TIMEOUT_MILLIS
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
    return HeartbeatBindingResult.Completed(heartbeatOutcomeFor(statusCode))
}
