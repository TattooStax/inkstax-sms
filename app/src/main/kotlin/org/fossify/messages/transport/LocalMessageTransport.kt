package org.fossify.messages.transport

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
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
        // The direct path is for a phone where this app is NOT the
        // default SMS app. Posture alone does not decide it: an install
        // that already holds the default role and then answers
        // "personal" still owns the provider, so its sends keep the
        // persisting path until the user actually switches the default
        // messenger — otherwise sent messages would vanish from history.
        val isDefaultSmsApp = Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        if (context.storedRolePosture() == RolePosture.PERMISSIONS_ONLY && !isDefaultSmsApp) {
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
     * because both ride on the provider row.
     *
     * A returned [TransportOutcome.Sent] means the send was queued,
     * exactly as on the default path: radio and carrier failures
     * arrive asynchronously, here through the sent PendingIntent to
     * [PermissionsOnlySendResultReceiver], which surfaces the same
     * error toast the default path shows. Synchronous error codes
     * mirror the default path's, so callers keep their existing
     * failure handling either way.
     *
     * One known gap, recorded rather than papered over: a
     * multi-recipient SMS shows in each recipient's own thread, but
     * not in the combined group conversation — the persisting path
     * manufactures that combined copy with a provider insert this
     * posture cannot make, and the only alternative is a second
     * message store, which this fork does not grow. How the personal
     * posture should present group sends is an open design question
     * on the track queue.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun sendSmsWithoutProviderRow(message: OutboundSms): TransportOutcome {
        // Two contacts can share one number; the persisting path sends
        // to each distinct destination once, and so does this one.
        val destinations = message.addresses.toSet()
        if (destinations.isEmpty()) {
            return TransportOutcome.Failed(TransportOutcome.EMPTY_DESTINATION_ADDRESS)
        }
        return try {
            val smsManager = getSmsManager(message.subscriptionId)
            for (address in destinations) {
                val destination = PhoneNumberUtils.stripSeparators(address)
                if (destination.isEmpty()) {
                    return TransportOutcome.Failed(TransportOutcome.EMPTY_DESTINATION_ADDRESS)
                }
                val parts = smsManager.divideMessage(message.text)
                if (parts == null || parts.size < 1) {
                    return TransportOutcome.Failed(TransportOutcome.ERROR_SENDING_MESSAGE)
                }
                val sentIntent = sentResultIntent(destination)
                if (parts.size == 1) {
                    @Suppress("DEPRECATION")
                    smsManager.sendTextMessage(destination, null, message.text, sentIntent, null)
                } else {
                    @Suppress("DEPRECATION")
                    smsManager.sendMultipartTextMessage(
                        destination, null, parts, ArrayList(List(parts.size) { sentIntent }), null
                    )
                }
            }
            TransportOutcome.Sent
        } catch (e: Exception) {
            TransportOutcome.Failed(TransportOutcome.UNKNOWN_ERROR, e.message ?: e.toString())
        }
    }

    /**
     * One sent-result PendingIntent per destination. The data Uri
     * makes each intent's identity unique, so concurrent sends never
     * overwrite each other's result routing.
     */
    private fun sentResultIntent(destination: String): PendingIntent {
        val intent = Intent(context, PermissionsOnlySendResultReceiver::class.java).apply {
            data = Uri.parse("inkstax-sms://sent-result/$destination/${System.currentTimeMillis()}")
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, 0, intent, flags)
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
