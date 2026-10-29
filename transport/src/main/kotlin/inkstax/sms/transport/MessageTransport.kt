package inkstax.sms.transport

/**
 * The one outbound SMS seam of the InkStax SMS layer (SMS-1).
 *
 * Every binding of the bridge seam — the local on-device binding
 * today, the X52 and cloud bindings as later design-intent — sends
 * SMS text through this single interface. This module is pure
 * Kotlin/JVM: it has no Android and no Google Mobile Services
 * dependencies, so the GMS-free invariant of the local build is
 * structural here, not a promise. Types are plain values only:
 * no Android `Uri`, no app models, no endpoint or credential
 * material of any kind.
 *
 * This slice defines the interface only. No bridge protocol is
 * implemented here or in the app-side local binding: there is no
 * wire format, no ack/heartbeat state, and no transport over a
 * network in this code. V1 is SMS text only; MMS stays on the
 * existing app-side path until its own slice (see
 * [outboundChannel], which only makes the routing decision that
 * used to be inlined in the app).
 */
data class OutboundSms(
    val text: String,
    val addresses: List<String>,
    val subscriptionId: Int,
    val requireDeliveryReport: Boolean,
    val messageId: Long? = null
)

/**
 * Result of one [MessageTransport.sendSms] call.
 *
 * [Failed.errorCode] values are the fork's long-standing sending
 * error codes, carried unchanged so existing user-facing error
 * handling keeps working: -1 empty destination address, -2 error
 * persisting the message, -3 error sending the message. Any other
 * failure is reported as [UNKNOWN_ERROR] with the original
 * failure rendered in [Failed.detail].
 */
sealed interface TransportOutcome {

    data object Sent : TransportOutcome

    data class Failed(
        val errorCode: Int,
        val detail: String? = null
    ) : TransportOutcome

    companion object {
        const val UNKNOWN_ERROR = 0
        const val EMPTY_DESTINATION_ADDRESS = -1
        const val ERROR_PERSISTING_MESSAGE = -2
        const val ERROR_SENDING_MESSAGE = -3
    }
}

/** Sends SMS text through one binding of the bridge seam. */
interface MessageTransport {

    fun sendSms(message: OutboundSms): TransportOutcome
}
