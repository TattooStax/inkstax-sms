package inkstax.sms.transport

/**
 * Heartbeat wire definitions for the bridge seam.
 *
 * FailureHonesty.kt defines what link health means — a beat every
 * [HEARTBEAT_INTERVAL_MILLIS], stale at [STALE_ALERT_AFTER_MILLIS]
 * of silence — and this file defines the one wire shape every
 * binding uses to send that beat, so the request a binding sends
 * and the reading of the answer it gets back cannot drift between
 * bindings. There is no network call, no timer, and no scheduler
 * here — a binding supplies the stored credential and the current
 * time, performs the HTTPS POST, and acts on the outcome.
 *
 * The beat is a POST to the heartbeat path on the credential's own
 * endpoint, authenticated by the credential itself: the device id
 * travels in the JSON body and the secret travels only in the
 * authorization value, never in the body, so a request body that
 * gets logged somewhere can never leak the device's key. The body
 * also carries the phone's send time, so the brain can tell a beat
 * that was delayed in transit from one that was never sent; the
 * brain's own receipt time is what [bridgeHealthAt] judges, never
 * the phone's claim. Every wire body in this seam is a flat JSON
 * object of string values, so either side needs only that one
 * reader for any of them.
 *
 * A beat is not a message ack. The dual-ack states in
 * FailureHonesty.kt move only on the message exchanges, so the
 * heartbeat answer carries no body and no message state: a
 * healthy link can never be mistaken for a delivered message. The
 * brain answers No Content when it has recorded the beat for
 * exactly the device the credential names. Any other answer — a
 * success page served by something that is not the brain, a
 * redirect the binding did not follow — is not a recorded beat.
 *
 * A credential the seam does not accept builds no request at all:
 * a corrupt or backup-restored stored value must never be sent to
 * an endpoint as if it were this device's key, so the credential
 * is re-checked here, at the point of use, in the same terms
 * [isCredentialUsableFor] defines.
 */

/** Path of the heartbeat on the brain's endpoint. */
const val HEARTBEAT_PATH: String = "/companion/heartbeat"

/** JSON field that carries the device id in the heartbeat request. */
const val HEARTBEAT_DEVICE_FIELD: String = "device_id"

/** JSON field that carries the phone's send time in the heartbeat request. */
const val HEARTBEAT_SENT_AT_FIELD: String = "sent_at_millis"

/** Authorization scheme that carries the device secret on the heartbeat request. */
const val HEARTBEAT_AUTHORIZATION_SCHEME: String = "Bearer"

/**
 * One heartbeat request, ready for a binding to POST: the URL on
 * the credential's endpoint, the JSON body carrying the device id
 * and the send time, and the authorization value carrying the
 * secret. The secret appears only in [authorization] — it is a
 * credential, never logged and never part of the body.
 */
data class HeartbeatRequest(
    val url: String,
    val body: String,
    val authorization: String
)

/**
 * The heartbeat request for a stored [credential] beaten at
 * [sentAtMillis], or null when no beat may be sent: the send time
 * is negative, or the credential is not one the seam accepts for
 * its own endpoint. Building no request is the honest outcome for
 * a credential that does not count — the binding surfaces the
 * broken pairing instead of presenting a key the brain never
 * minted for this device.
 */
fun heartbeatRequestFor(credential: DeviceCredential, sentAtMillis: Long): HeartbeatRequest? {
    if (sentAtMillis < 0L) {
        return null
    }
    if (!isCredentialUsableFor(credential, credential.endpointBase)) {
        return null
    }
    return HeartbeatRequest(
        url = credential.endpointBase + HEARTBEAT_PATH,
        body = "{\"$HEARTBEAT_DEVICE_FIELD\":${jsonString(credential.deviceId)}," +
            "\"$HEARTBEAT_SENT_AT_FIELD\":${jsonString(sentAtMillis.toString())}}",
        authorization = "$HEARTBEAT_AUTHORIZATION_SCHEME ${credential.secret}"
    )
}

/** Why a heartbeat was not recorded. */
enum class HeartbeatFailure {
    /** The endpoint does not recognise this device credential. */
    CREDENTIAL_REJECTED,

    /** The endpoint refused the heartbeat for another reason. */
    ENDPOINT_REJECTED,

    /** The endpoint could not be reached at all. */
    NETWORK_UNREACHABLE,

    /** The endpoint answered, but not with the recording ack. */
    MALFORMED_RESPONSE
}

/** The outcome of one heartbeat attempt, as the seam reads it. */
sealed interface HeartbeatOutcome {
    /** The brain recorded the beat for this device. */
    data object Recorded : HeartbeatOutcome

    /** The beat was not recorded; the failure says why. */
    data class Failed(val failure: HeartbeatFailure) : HeartbeatOutcome
}

/**
 * Reads one heartbeat attempt's answer as a [HeartbeatOutcome].
 * A null [statusCode] means no answer arrived at all — the
 * endpoint could not be reached. Only No Content is a recorded
 * beat: the ack is defined to carry no body, so the status alone
 * decides and a body is never read into evidence. A credential
 * refusal is its own failure, not a transient one — retrying the
 * same key cannot succeed, and the phone reads it as a broken
 * pairing whose recovery is re-pairing, never as a link that
 * merely went quiet.
 */
fun heartbeatOutcomeFor(statusCode: Int?): HeartbeatOutcome {
    if (statusCode == null) {
        return HeartbeatOutcome.Failed(HeartbeatFailure.NETWORK_UNREACHABLE)
    }
    return when {
        statusCode == HTTP_NO_CONTENT -> HeartbeatOutcome.Recorded
        statusCode == HTTP_UNAUTHORIZED || statusCode == HTTP_FORBIDDEN ->
            HeartbeatOutcome.Failed(HeartbeatFailure.CREDENTIAL_REJECTED)
        statusCode in HTTP_CLIENT_ERROR_RANGE ->
            HeartbeatOutcome.Failed(HeartbeatFailure.ENDPOINT_REJECTED)
        else -> HeartbeatOutcome.Failed(HeartbeatFailure.MALFORMED_RESPONSE)
    }
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
