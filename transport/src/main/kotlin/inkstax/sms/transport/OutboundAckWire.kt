package inkstax.sms.transport

/**
 * Outbound ack wire definitions for the bridge seam.
 *
 * FailureHonesty.kt defines what the outbound half of dual ack
 * means — a reply stays [OutboundAckState.QUEUED] until the phone
 * acks it actually sent — and this file defines the one wire
 * shape every binding uses to report that ack to the brain, so
 * the report a binding sends and the reading of the answer it
 * gets back cannot drift between bindings. There is no network
 * call here — a binding supplies the stored credential, the
 * identity of the reply it sent, and the send outcome its
 * transport returned, performs the HTTPS POST, and acts on the
 * outcome.
 *
 * The report is a POST to the outbound ack path on the
 * credential's own endpoint, authenticated by the credential
 * itself: the device id travels in the JSON body and the secret
 * travels only in the authorization value, in its
 * base64url-encoded spelling, never in the body — the same shape
 * HeartbeatWire.kt and MessageDeliveryWire.kt use, for the same
 * reason: a logged request body must never leak the device's
 * key, and every opaque secret the exchange can mint must spell
 * a valid header value.
 *
 * The body carries no message content. The brain already holds
 * the reply it dispatched; what it lacks is the phone's verdict
 * on the send, so the body carries only the identity the brain
 * files that verdict against (the reply's source id, in the
 * same shape the inbound side renders), the outcome itself, the
 * sending error code when the send failed, and the time the
 * phone reached its verdict. A report with no source id builds
 * no request: without the identity there is nothing the brain
 * could file the verdict against, and an unidentifiable report
 * would be a second record waiting to diverge. An identity with
 * whitespace in it builds no request either — the identities
 * this seam renders carry none, so one that does is not an
 * identity the seam minted.
 *
 * The phone's send outcome — not the brain's answer —
 * moves the ack state, through the one transition
 * [outboundAckStateAfter] defines: the brain's No Content
 * answer only records that the report landed. A report that
 * never lands leaves the local state exactly where the send put
 * it, and the report is simply sent again; the terminal states
 * ignore duplicate events, so a re-report can never un-send a
 * sent reply or revive a failed one. The sending failure's
 * free-text detail stays on the phone, where the existing
 * user-facing error handling already surfaces it — the wire
 * carries the error code the brain can file and act on, not a
 * rendering of a local exception.
 *
 * A credential the seam does not accept builds no request at
 * all, in the same terms [isCredentialUsableFor] defines: a
 * corrupt or backup-restored stored value must never be sent to
 * an endpoint as if it were this device's key.
 */

/** Path of the outbound ack report on the brain's endpoint. */
const val OUTBOUND_ACK_PATH: String = "/companion/outbound-ack"

/** JSON field that carries the device id in the ack report. */
const val OUTBOUND_ACK_DEVICE_FIELD: String = "device_id"

/** JSON field that carries the reply source id in the ack report. */
const val OUTBOUND_ACK_SOURCE_FIELD: String = "source_id"

/** JSON field that carries the send outcome in the ack report. */
const val OUTBOUND_ACK_OUTCOME_FIELD: String = "outcome"

/** JSON field that carries the sending error code in a failed ack report. */
const val OUTBOUND_ACK_ERROR_CODE_FIELD: String = "error_code"

/** JSON field that carries the report time in the ack report. */
const val OUTBOUND_ACK_REPORTED_AT_FIELD: String = "reported_at_millis"

/** Authorization scheme that carries the device secret on the ack report. */
const val OUTBOUND_ACK_AUTHORIZATION_SCHEME: String = "Bearer"

/** Outcome value in the ack report when the phone acked the reply sent. */
const val OUTBOUND_ACK_OUTCOME_SENT: String = "sent"

/** Outcome value in the ack report when the phone reported the send failed. */
const val OUTBOUND_ACK_OUTCOME_FAILED: String = "failed"

/**
 * One outbound ack report, ready for a binding to POST: the URL
 * on the credential's endpoint, the JSON body carrying the
 * verdict, and the authorization value carrying the secret. The
 * secret appears only in [authorization], in its encoded
 * spelling — it is a credential, never logged and never part of
 * the body. The generated data-class rendering would print that
 * value verbatim, so [toString] redacts it: a report that lands
 * in a log line shows where the report was going, never the key
 * it carried.
 */
data class OutboundAckRequest(
    val url: String,
    val body: String,
    val authorization: String
) {
    override fun toString(): String {
        return "OutboundAckRequest(url=" + url + ", body=" + body +
            ", authorization=" + OUTBOUND_ACK_AUTHORIZATION_SCHEME + " <redacted>)"
    }
}

/**
 * The ack report for a reply filed under [sourceId] whose send
 * ended in [sendOutcome], reported at [reportedAtMillis] under
 * a stored [credential] — or null when no report may be sent:
 * the report time is negative, the credential is not one the
 * seam accepts for its own endpoint, or the source id is blank
 * or carries whitespace. Building no report is the honest
 * outcome for each of those — the caller keeps the verdict
 * unreported rather than filing it against an identity the
 * brain cannot match.
 */
fun outboundAckRequestFor(
    credential: DeviceCredential,
    sourceId: String,
    sendOutcome: TransportOutcome,
    reportedAtMillis: Long
): OutboundAckRequest? {
    if (reportedAtMillis < 0L) {
        return null
    }
    if (!isCredentialUsableFor(credential, credential.endpointBase)) {
        return null
    }
    if (sourceId.isBlank() || sourceId.any { it.isWhitespace() }) {
        return null
    }
    val outcomeValue: String
    val errorCodeField: String
    when (sendOutcome) {
        is TransportOutcome.Sent -> {
            outcomeValue = OUTBOUND_ACK_OUTCOME_SENT
            errorCodeField = ""
        }
        is TransportOutcome.Failed -> {
            outcomeValue = OUTBOUND_ACK_OUTCOME_FAILED
            errorCodeField = ",\"$OUTBOUND_ACK_ERROR_CODE_FIELD\":${jsonString(sendOutcome.errorCode.toString())}"
        }
    }
    return OutboundAckRequest(
        url = credential.endpointBase.trimEnd('/') + OUTBOUND_ACK_PATH,
        body = "{" +
            "\"$OUTBOUND_ACK_DEVICE_FIELD\":${jsonString(credential.deviceId)}," +
            "\"$OUTBOUND_ACK_SOURCE_FIELD\":${jsonString(sourceId)}," +
            "\"$OUTBOUND_ACK_OUTCOME_FIELD\":${jsonString(outcomeValue)}" +
            errorCodeField + "," +
            "\"$OUTBOUND_ACK_REPORTED_AT_FIELD\":${jsonString(reportedAtMillis.toString())}" +
            "}",
        authorization = OUTBOUND_ACK_AUTHORIZATION_SCHEME + " " + base64Url(credential.secret)
    )
}

/**
 * The base64url spelling of a value, without padding. The
 * alphabet is exactly the characters a header value may carry,
 * so any opaque secret encodes to a valid authorization value,
 * and decoding the spelling recovers the value itself.
 */
private fun base64Url(value: String): String {
    return java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))
}

/** Why an outbound ack report was not recorded. */
enum class OutboundAckFailure {
    /** The endpoint does not recognise this device credential. */
    CREDENTIAL_REJECTED,

    /** The endpoint refused the report for another reason. */
    ENDPOINT_REJECTED,

    /** The endpoint could not be reached at all. */
    NETWORK_UNREACHABLE,

    /** The endpoint answered, but not with the recording ack. */
    MALFORMED_RESPONSE
}

/** The outcome of one outbound ack report attempt, as the seam reads it. */
sealed interface OutboundAckOutcome {
    /** The brain recorded the verdict; the report has landed. */
    data object Recorded : OutboundAckOutcome

    /** The report was not recorded; the failure says why. */
    data class Failed(val failure: OutboundAckFailure) : OutboundAckOutcome
}

/**
 * Reads one report attempt's answer as an [OutboundAckOutcome].
 * A null [statusCode] means no answer arrived at all — the
 * endpoint could not be reached, the answer may have been lost
 * after the brain recorded the verdict, and the report stays
 * unlanded for a re-report against the same source id. Only No
 * Content is the brain recording the report: the answer is
 * defined to carry no body, so the status alone decides and a
 * body is never read into evidence. A credential refusal is its
 * own failure, not a transient one — retrying the same key
 * cannot succeed, and the phone reads it as a broken pairing
 * whose recovery is re-pairing, never as a link that merely
 * went quiet.
 */
fun outboundAckOutcomeFor(statusCode: Int?): OutboundAckOutcome {
    if (statusCode == null) {
        return OutboundAckOutcome.Failed(OutboundAckFailure.NETWORK_UNREACHABLE)
    }
    return when {
        statusCode == HTTP_NO_CONTENT -> OutboundAckOutcome.Recorded
        statusCode == HTTP_UNAUTHORIZED || statusCode == HTTP_FORBIDDEN ->
            OutboundAckOutcome.Failed(OutboundAckFailure.CREDENTIAL_REJECTED)
        statusCode in HTTP_CLIENT_ERROR_RANGE ->
            OutboundAckOutcome.Failed(OutboundAckFailure.ENDPOINT_REJECTED)
        else -> OutboundAckOutcome.Failed(OutboundAckFailure.MALFORMED_RESPONSE)
    }
}

/**
 * The outbound ack state after a send ended in [sendOutcome]:
 * the phone's own verdict moves the state, through the one
 * transition [outboundAckStateAfter] defines, so a queued reply
 * is shown sent or failed exactly when the phone knows, and a
 * terminal state ignores a late or duplicate verdict. Whether
 * the report of that verdict reached the brain is a separate
 * fact, read through [outboundAckOutcomeFor] — it never moves
 * this state.
 */
fun outboundAckStateAfterSend(current: OutboundAckState, sendOutcome: TransportOutcome): OutboundAckState {
    val event = when (sendOutcome) {
        is TransportOutcome.Sent -> OutboundAckEvent.PHONE_ACKED_SENT
        is TransportOutcome.Failed -> OutboundAckEvent.PHONE_REPORTED_FAILURE
    }
    return outboundAckStateAfter(current, event)
}

private const val HTTP_NO_CONTENT: Int = 204
private const val HTTP_UNAUTHORIZED: Int = 401
private const val HTTP_FORBIDDEN: Int = 403
private val HTTP_CLIENT_ERROR_RANGE: IntRange = 400..499

/**
 * Renders a value as a JSON string literal, escaping exactly the
 * characters JSON requires: the quote, the backslash, and the
 * control characters. Everything else is carried as-is — the
 * value a reader recovers from the literal is the value itself.
 */
private fun jsonString(value: String): String {
    val rendered = StringBuilder(value.length + 2)
    rendered.append('"')
    for (char in value) {
        when (char) {
            '"' -> rendered.append("\\\"")
            '\\' -> rendered.append("\\\\")
            '\b' -> rendered.append("\\b")
            '\u000C' -> rendered.append("\\f")
            '\n' -> rendered.append("\\n")
            '\r' -> rendered.append("\\r")
            '\t' -> rendered.append("\\t")
            else -> if (char.code < 0x20) {
                rendered.append("\\u")
                rendered.append(char.code.toString(16).padStart(4, '0'))
            } else {
                rendered.append(char)
            }
        }
    }
    rendered.append('"')
    return rendered.toString()
}
