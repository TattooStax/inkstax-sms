package inkstax.sms.transport

/**
 * Inbound message delivery wire definitions for the bridge seam.
 *
 * FailureHonesty.kt defines what the inbound half of dual ack
 * means — an inbound message stays [InboundAckState.HELD] on the
 * phone until the brain acks it — and this file defines the one
 * wire shape every binding uses to hand an inbound message off,
 * so the request a binding sends and the reading of the answer
 * it gets back cannot drift between bindings. There is no
 * network call and no queue here — a binding supplies the stored
 * credential, the message its receivers already persisted, and
 * the hand-off time, performs the HTTPS POST, and acts on the
 * outcome.
 *
 * The delivery is a POST to the inbound path on the credential's
 * own endpoint, authenticated by the credential itself: the
 * device id travels in the JSON body and the secret travels only
 * in the authorization value, in its base64url-encoded spelling,
 * never in the body — the same shape HeartbeatWire.kt uses, for
 * the same reason: a logged request body must never leak the
 * device's key, and every opaque secret the exchange can mint
 * must spell a valid header value. The body carries the message
 * itself under its [InboundSms.sourceId], the identity the seam
 * already renders for it, so a redelivery after a lost ack files
 * against the same identity instead of minting a second one.
 * The body also carries the received-late verdict
 * [isReceivedLate] reaches for this hand-off, so the brain
 * records the delay the phone observed instead of re-deriving it
 * from clocks it does not share. Every wire body in this seam is
 * a flat JSON object of string values, so either side needs only
 * that one reader for any of them.
 *
 * Only a message the shared disposition rule handles may be
 * delivered: [inboundDispositionFor] is applied here, at the
 * point of use, so an MMS arrival — surfaced as not forwarded in
 * V1 — and a blank-sender arrival the caller still owns can
 * never be put on this wire by a binding that forgot the rule.
 * A message with no source id builds no request either: without
 * the provider identity there is nothing the brain's ack could
 * release, and an unidentifiable delivery would be a second
 * identity waiting to duplicate.
 *
 * The brain's ack carries no body. The brain answers No Content
 * when it has recorded the message for exactly the device the
 * credential names, and only that answer releases the held
 * message — a healthy heartbeat can never stand in for it, and
 * neither can any other success status. A credential the seam
 * does not accept builds no request at all, in the same terms
 * [isCredentialUsableFor] defines: a corrupt or backup-restored
 * stored value must never be sent to an endpoint as if it were
 * this device's key.
 */

/** Path of the inbound delivery on the brain's endpoint. */
const val INBOUND_DELIVERY_PATH: String = "/companion/inbound"

/** JSON field that carries the device id in the delivery request. */
const val INBOUND_DELIVERY_DEVICE_FIELD: String = "device_id"

/** JSON field that carries the message source id in the delivery request. */
const val INBOUND_DELIVERY_SOURCE_FIELD: String = "source_id"

/** JSON field that carries the sender in the delivery request. */
const val INBOUND_DELIVERY_SENDER_FIELD: String = "sender"

/** JSON field that carries the message body in the delivery request. */
const val INBOUND_DELIVERY_BODY_FIELD: String = "body"

/** JSON field that carries the receipt time in the delivery request. */
const val INBOUND_DELIVERY_RECEIVED_AT_FIELD: String = "received_at_millis"

/** JSON field that carries the received-late verdict in the delivery request. */
const val INBOUND_DELIVERY_RECEIVED_LATE_FIELD: String = "received_late"

/** Authorization scheme that carries the device secret on the delivery request. */
const val INBOUND_DELIVERY_AUTHORIZATION_SCHEME: String = "Bearer"

/**
 * One inbound delivery request, ready for a binding to POST: the
 * URL on the credential's endpoint, the JSON body carrying the
 * message, and the authorization value carrying the secret. The
 * secret appears only in [authorization], in its encoded
 * spelling — it is a credential, never logged and never part of
 * the body. The generated data-class rendering would print that
 * value verbatim, so [toString] redacts it: a request that lands
 * in a log line shows where the delivery was going, never the
 * key it carried.
 */
data class InboundDeliveryRequest(
    val url: String,
    val body: String,
    val authorization: String
) {
    override fun toString(): String {
        return "InboundDeliveryRequest(url=" + url + ", body=" + body +
            ", authorization=" + INBOUND_DELIVERY_AUTHORIZATION_SCHEME + " <redacted>)"
    }
}

/**
 * The delivery request for an inbound [message] handed off at
 * [handedOffAtMillis] under a stored [credential], or null when
 * no delivery may be sent: a timestamp is negative, the
 * credential is not one the seam accepts for its own endpoint,
 * the shared disposition rule does not handle the message, or
 * the message carries no source id. Building no request is the
 * honest outcome for each of those — the caller keeps the
 * message held rather than delivering something the brain
 * cannot ack against a stable identity.
 */
fun inboundDeliveryRequestFor(
    credential: DeviceCredential,
    message: InboundSms,
    handedOffAtMillis: Long
): InboundDeliveryRequest? {
    if (message.receivedAtMillis < 0L || handedOffAtMillis < 0L) {
        return null
    }
    if (!isCredentialUsableFor(credential, credential.endpointBase)) {
        return null
    }
    if (inboundDispositionFor(message).disposition != InboundDisposition.HANDLED) {
        return null
    }
    val sourceId = message.sourceId ?: return null
    val receivedLate = isReceivedLate(message.receivedAtMillis, handedOffAtMillis)
    return InboundDeliveryRequest(
        url = credential.endpointBase.trimEnd('/') + INBOUND_DELIVERY_PATH,
        body = "{" +
            "\"$INBOUND_DELIVERY_DEVICE_FIELD\":${jsonString(credential.deviceId)}," +
            "\"$INBOUND_DELIVERY_SOURCE_FIELD\":${jsonString(sourceId)}," +
            "\"$INBOUND_DELIVERY_SENDER_FIELD\":${jsonString(message.sender)}," +
            "\"$INBOUND_DELIVERY_BODY_FIELD\":${jsonString(message.body)}," +
            "\"$INBOUND_DELIVERY_RECEIVED_AT_FIELD\":${jsonString(message.receivedAtMillis.toString())}," +
            "\"$INBOUND_DELIVERY_RECEIVED_LATE_FIELD\":${jsonString(receivedLate.toString())}" +
            "}",
        authorization = INBOUND_DELIVERY_AUTHORIZATION_SCHEME + " " + base64Url(credential.secret)
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

/** Why an inbound delivery was not acked. */
enum class InboundDeliveryFailure {
    /** The endpoint does not recognise this device credential. */
    CREDENTIAL_REJECTED,

    /** The endpoint refused the delivery for another reason. */
    ENDPOINT_REJECTED,

    /** The endpoint could not be reached at all. */
    NETWORK_UNREACHABLE,

    /** The endpoint answered, but not with the recording ack. */
    MALFORMED_RESPONSE
}

/** The outcome of one inbound delivery attempt, as the seam reads it. */
sealed interface InboundDeliveryOutcome {
    /** The brain recorded the message; the phone may release it. */
    data object Acknowledged : InboundDeliveryOutcome

    /** The delivery was not acked; the failure says why. */
    data class Failed(val failure: InboundDeliveryFailure) : InboundDeliveryOutcome
}

/**
 * Reads one delivery attempt's answer as an
 * [InboundDeliveryOutcome]. A null [statusCode] means no answer
 * arrived at all — the endpoint could not be reached, the ack
 * may have been lost after the brain recorded the message, and
 * the message stays held for a redelivery against the same
 * source id. Only No Content is the brain's ack: the ack is
 * defined to carry no body, so the status alone decides and a
 * body is never read into evidence. A credential refusal is its
 * own failure, not a transient one — retrying the same key
 * cannot succeed, and the phone reads it as a broken pairing
 * whose recovery is re-pairing, never as a link that merely
 * went quiet.
 */
fun inboundDeliveryOutcomeFor(statusCode: Int?): InboundDeliveryOutcome {
    if (statusCode == null) {
        return InboundDeliveryOutcome.Failed(InboundDeliveryFailure.NETWORK_UNREACHABLE)
    }
    return when {
        statusCode == HTTP_NO_CONTENT -> InboundDeliveryOutcome.Acknowledged
        statusCode == HTTP_UNAUTHORIZED || statusCode == HTTP_FORBIDDEN ->
            InboundDeliveryOutcome.Failed(InboundDeliveryFailure.CREDENTIAL_REJECTED)
        statusCode in HTTP_CLIENT_ERROR_RANGE ->
            InboundDeliveryOutcome.Failed(InboundDeliveryFailure.ENDPOINT_REJECTED)
        else -> InboundDeliveryOutcome.Failed(InboundDeliveryFailure.MALFORMED_RESPONSE)
    }
}

/**
 * The inbound ack state after one delivery attempt: the message
 * is released only when the brain acked it, through the one
 * transition [inboundAckStateAfter] defines, so a failed or lost
 * delivery leaves a held message held and a released message is
 * never held again by a late failure.
 */
fun inboundAckStateAfterDelivery(current: InboundAckState, outcome: InboundDeliveryOutcome): InboundAckState {
    return inboundAckStateAfter(current, outcome is InboundDeliveryOutcome.Acknowledged)
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
