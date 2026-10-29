package org.fossify.messages.transport

import android.content.Context
import android.telephony.PhoneNumberUtils
import android.widget.Toast.LENGTH_LONG
import inkstax.sms.transport.MessageTransport
import inkstax.sms.transport.OutboundSms
import inkstax.sms.transport.RolePosture
import inkstax.sms.transport.TransportOutcome
import org.fossify.commons.extensions.toast
import org.fossify.messages.R
import org.fossify.messages.extensions.messagingUtils
import org.fossify.messages.messaging.SmsException
import org.fossify.messages.messaging.getSmsManager

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
        if (context.storedRolePosture() == RolePosture.PERMISSIONS_ONLY) {
            return sendSmsWithoutProviderRow(message)
        }
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

    /**
     * The permissions-only send path. Only the default SMS app may
     * write the telephony provider, and the path above persists
     * before it sends — on a personal phone that insert is denied
     * and the message never leaves. This posture therefore sends
     * through SmsManager directly and writes no provider row. The
     * trade-offs are platform-imposed, not chosen: no sent-copy in
     * the provider for any app to show, and no delivery report,
     * because both ride on the provider row and its pending intents.
     * Error codes mirror the default path's, so callers keep their
     * existing failure handling.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun sendSmsWithoutProviderRow(message: OutboundSms): TransportOutcome {
        if (message.addresses.isEmpty()) {
            return TransportOutcome.Failed(TransportOutcome.EMPTY_DESTINATION_ADDRESS)
        }
        return try {
            val smsManager = getSmsManager(message.subscriptionId)
            for (address in message.addresses) {
                val destination = PhoneNumberUtils.stripSeparators(address)
                if (destination.isEmpty()) {
                    return TransportOutcome.Failed(TransportOutcome.EMPTY_DESTINATION_ADDRESS)
                }
                val parts = smsManager.divideMessage(message.text)
                if (parts == null || parts.size < 1) {
                    return TransportOutcome.Failed(TransportOutcome.ERROR_SENDING_MESSAGE)
                }
                if (parts.size == 1) {
                    @Suppress("DEPRECATION")
                    smsManager.sendTextMessage(destination, null, message.text, null, null)
                } else {
                    @Suppress("DEPRECATION")
                    smsManager.sendMultipartTextMessage(destination, null, parts, null, null)
                }
            }
            TransportOutcome.Sent
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
