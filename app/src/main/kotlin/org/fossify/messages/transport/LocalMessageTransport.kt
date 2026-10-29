package org.fossify.messages.transport

import android.content.Context
import android.widget.Toast.LENGTH_LONG
import inkstax.sms.transport.MessageTransport
import inkstax.sms.transport.OutboundSms
import inkstax.sms.transport.TransportOutcome
import org.fossify.commons.extensions.toast
import org.fossify.messages.R
import org.fossify.messages.extensions.messagingUtils
import org.fossify.messages.messaging.SmsException

/**
 * The local binding of the bridge seam (SMS-1): SMS text is sent
 * on this device through the fork's existing persistence and
 * SmsManager sending code, exactly as before the extraction. The
 * sending error codes of that code are the codes
 * [TransportOutcome.Failed] carries, so callers keep their
 * existing error handling.
 *
 * This is not a bridge-protocol implementation: nothing here
 * talks to the InkStax core or to any network endpoint.
 */
val Context.messageTransport: MessageTransport
    get() = LocalMessageTransport(this)

class LocalMessageTransport(private val context: Context) : MessageTransport {

    // The binding maps every failure of the underlying send to a Failed outcome;
    // that total mapping is the seam's contract, so the generic catch is intentional.
    @Suppress("TooGenericExceptionCaught")
    override fun sendSms(message: OutboundSms): TransportOutcome {
        return try {
            context.messagingUtils.sendSmsMessage(
                text = message.text,
                addresses = message.addresses.toSet(),
                subId = message.subscriptionId,
                requireDeliveryReport = message.requireDeliveryReport,
                messageId = message.messageId
            )
            TransportOutcome.Sent
        } catch (e: SmsException) {
            TransportOutcome.Failed(e.errorCode)
        } catch (e: Exception) {
            TransportOutcome.Failed(TransportOutcome.UNKNOWN_ERROR, e.message ?: e.toString())
        }
    }
}

/** Shows the user-facing error for a failed seam send, as the send entry point did before SMS-1. */
fun Context.showSmsTransportFailure(outcome: TransportOutcome.Failed) {
    when (outcome.errorCode) {
        TransportOutcome.EMPTY_DESTINATION_ADDRESS -> toast(
            id = R.string.empty_destination_address,
            length = LENGTH_LONG
        )

        TransportOutcome.ERROR_PERSISTING_MESSAGE -> toast(
            id = R.string.unable_to_save_message,
            length = LENGTH_LONG
        )

        TransportOutcome.ERROR_SENDING_MESSAGE -> toast(
            msg = getString(
                R.string.sms_preparation_error_details,
                getString(R.string.message_not_sent_short),
                outcome.errorCode
            ),
            length = LENGTH_LONG
        )

        else -> toast(
            msg = outcome.detail ?: getString(R.string.message_not_sent_short),
            length = LENGTH_LONG
        )
    }
}
