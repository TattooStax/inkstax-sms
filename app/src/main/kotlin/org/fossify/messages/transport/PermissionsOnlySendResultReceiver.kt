package org.fossify.messages.transport

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fossify.messages.extensions.messagingUtils
import org.fossify.messages.receivers.SendStatusReceiver

/**
 * Sent-result reporting for the permissions-only send path (SMS-3).
 *
 * SmsManager reports radio/carrier failures asynchronously through
 * the sent PendingIntent, not through an exception, so a send that
 * merely queues is not yet a success. The default-role path learns
 * the outcome through SmsStatusSentReceiver and reflects it on the
 * provider row; this posture has no provider row, so the receiver
 * surfaces the same user-facing error toast and nothing else. It is
 * registered non-exported and addressed only by the explicit
 * PendingIntents the local binding builds.
 */
class PermissionsOnlySendResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (resultCode == Activity.RESULT_OK) return
        context.messagingUtils.maybeShowErrorToast(
            resultCode = resultCode,
            errorCode = intent.getIntExtra(
                SendStatusReceiver.EXTRA_ERROR_CODE,
                SendStatusReceiver.NO_ERROR_CODE
            )
        )
    }
}
