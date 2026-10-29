package inkstax.sms.transport

/**
 * The inbound side of the one bridge seam (SMS-2, local binding first).
 *
 * Outbound SMS text leaves through [MessageTransport]; inbound messages
 * arrive through [InboundSink]. Both directions live in this pure
 * Kotlin/JVM module so every binding — local today, the host and cloud
 * bindings as design intent only — shares the same value types and the
 * same disposition rules. There is no wire format, no network code, and
 * no second intake, store, or scheduler here: a binding only classifies
 * and hands off a message the fork's existing receivers already
 * persisted through the Android telephony provider.
 *
 * V1 is SMS text only. An MMS arrival is classified
 * [InboundDisposition.NOT_FORWARDED] so it is surfaced as not forwarded
 * instead of being silently dropped; its media stays in the provider
 * store until a later slice designs media filing.
 */
enum class InboundKind {
    SMS,
    MMS
}

/**
 * One inbound message as the seam sees it. Plain values only: no
 * Android types, no endpoint or credential material.
 *
 * [providerId] is the Android telephony provider row id when the
 * receiver knows it; [sourceId] renders it in the shape the bridge
 * contract uses ("sms:<id>" / "mms:<id>"), so a later binding can file
 * against the core without re-minting an identity the fork already has.
 */
data class InboundSms(
    val sender: String,
    val body: String,
    val receivedAtMillis: Long,
    val subscriptionId: Int,
    val kind: InboundKind = InboundKind.SMS,
    val providerId: Long? = null,
    val threadId: Long? = null
) {
    val sourceId: String?
        get() = providerId?.let { id ->
            when (kind) {
                InboundKind.SMS -> "sms:$id"
                InboundKind.MMS -> "mms:$id"
            }
        }
}

enum class InboundDisposition {
    /** The binding accepted the message as handled. */
    HANDLED,

    /** V1 does not forward this kind; the arrival is surfaced, not dropped. */
    NOT_FORWARDED,

    /** The message cannot be handed off yet and stays with the caller. */
    DEFERRED
}

data class InboundOutcome(
    val disposition: InboundDisposition,
    val reason: String? = null
) {
    companion object {
        const val REASON_MMS_NOT_FORWARDED_IN_V1 = "mms_not_forwarded_in_v1"
        const val REASON_BLANK_SENDER = "blank_sender"
    }
}

/**
 * The disposition rule every binding applies before hand-off, kept in
 * one place so local and later bindings cannot drift: blank senders are
 * deferred back to the caller, MMS is not forwarded in V1, SMS text is
 * handled.
 */
fun inboundDispositionFor(message: InboundSms): InboundOutcome {
    if (message.sender.isBlank()) {
        return InboundOutcome(InboundDisposition.DEFERRED, InboundOutcome.REASON_BLANK_SENDER)
    }
    return when (message.kind) {
        InboundKind.MMS ->
            InboundOutcome(InboundDisposition.NOT_FORWARDED, InboundOutcome.REASON_MMS_NOT_FORWARDED_IN_V1)
        InboundKind.SMS -> InboundOutcome(InboundDisposition.HANDLED)
    }
}

/** Receives inbound messages through one binding of the bridge seam. */
interface InboundSink {

    fun onInbound(message: InboundSms): InboundOutcome
}
