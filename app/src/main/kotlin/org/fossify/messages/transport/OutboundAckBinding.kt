package org.fossify.messages.transport

import android.content.Context
import inkstax.sms.transport.OutboundAckEvent
import inkstax.sms.transport.OutboundAckOutcome
import inkstax.sms.transport.outboundAckOutcomeFor
import inkstax.sms.transport.outboundAckRequestFor
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val OUTBOUND_ACK_CONNECT_TIMEOUT_MILLIS = 10_000
private const val OUTBOUND_ACK_READ_TIMEOUT_MILLIS = 15_000

/** The result of one outbound ack report run by this binding. */
sealed interface OutboundAckBindingResult {
    /**
     * No report could be built: this device holds no credential
     * the seam accepts for its own endpoint, so there is no key to
     * report with. That is a broken or absent pairing whose
     * recovery is pairing (again), never a link that merely went
     * quiet — the caller must not read it as an unreachable
     * endpoint, and the verdict stays unreported.
     */
    data object NoUsableCredential : OutboundAckBindingResult

    /**
     * The verdict itself may not be reported: the reply carries
     * no source id the brain could file the verdict against, or
     * the error code contradicts the verdict. The pairing is not
     * in question here — the caller keeps the verdict unreported
     * rather than filing it against an identity the brain cannot
     * match, or with a shape that contradicts itself.
     */
    data object NotReportable : OutboundAckBindingResult

    /** A report was sent and its answer was read the one way. */
    data class Completed(val outcome: OutboundAckOutcome) : OutboundAckBindingResult
}

/**
 * Reports one outbound send verdict to the brain for this
 * device's stored credential: the reply filed under [sourceId]
 * whose send the phone acked with [event], with the sending
 * [errorCode] on a failure, reported at [reportedAtMillis]. This
 * is the binding that performs the report the :transport
 * definitions describe: the request is the one
 * [outboundAckRequestFor] builds from the stored credential, the
 * source id, and the phone's verdict, and the answer is read the
 * one way [outboundAckOutcomeFor] reads it. Only the brain
 * recording the report lands it; every other outcome leaves the
 * verdict unreported for a re-report against the same source id,
 * and the terminal ack states ignore a duplicate verdict, so a
 * re-report can never un-send a sent reply or revive a failed
 * one. Whether the report landed never moves the ack state — the
 * phone's verdict already did, through the transition the ack
 * definitions name.
 *
 * The report is built from the phone's ack event, never from
 * the transport's submission outcome: a send call returning
 * means the message was queued with the radio, not that it
 * sent, and the verdict the caller hands here is the one its
 * send-result path actually received.
 *
 * The credential's endpoint is HTTPS (the pairing reader accepts
 * no other scheme for an offer, and a credential is minted only
 * for a scanned offer's endpoint), so the secret travels only
 * over TLS, and only in the authorization value the wire shape
 * defines — never in the body, never logged here.
 *
 * A negative [reportedAtMillis] is a caller error and throws:
 * an argument fault must never be reported as a missing or
 * broken credential, whose documented recovery is re-pairing,
 * nor as a verdict that merely may not be reported.
 *
 * This call blocks on the network, so it belongs on a background
 * thread. A transport fault while asking reads as no answer at
 * all, which the outcome reader names unreachable, and the
 * verdict stays unreported. Redirects are never followed: the
 * brain answers on the credential's own endpoint or not at all,
 * and a redirect status is read by the outcome reader as an
 * answer that is not the brain recording the report. The
 * answer's body is never read — the recording answer is defined
 * to carry no body, so the status alone decides.
 */
@Suppress("SwallowedException")
fun Context.performOutboundAckReport(
    sourceId: String,
    event: OutboundAckEvent,
    errorCode: Int?,
    reportedAtMillis: Long
): OutboundAckBindingResult {
    require(reportedAtMillis >= 0L) { "reportedAtMillis must not be negative" }
    val credential = storedDeviceCredential()
        ?: return OutboundAckBindingResult.NoUsableCredential
    val sourceIdNamesAReply = !sourceId.isBlank() && sourceId.none { it.isWhitespace() }
    val verdictContradictsItself = when (event) {
        OutboundAckEvent.PHONE_ACKED_SENT -> errorCode != null
        OutboundAckEvent.PHONE_REPORTED_FAILURE -> errorCode == null
    }
    if (!sourceIdNamesAReply || verdictContradictsItself) {
        return OutboundAckBindingResult.NotReportable
    }
    val request = outboundAckRequestFor(credential, sourceId, event, errorCode, reportedAtMillis)
        ?: return OutboundAckBindingResult.NoUsableCredential
    var connection: HttpURLConnection? = null
    var statusCode: Int? = null
    try {
        connection = URL(request.url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = OUTBOUND_ACK_CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = OUTBOUND_ACK_READ_TIMEOUT_MILLIS
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
    return OutboundAckBindingResult.Completed(outboundAckOutcomeFor(statusCode))
}
