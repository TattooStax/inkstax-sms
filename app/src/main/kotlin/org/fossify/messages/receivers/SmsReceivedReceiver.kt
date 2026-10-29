package org.fossify.messages.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import inkstax.sms.transport.InboundSms
import inkstax.sms.transport.RolePosture
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.isNumberBlocked
import org.fossify.commons.helpers.ContactLookupResult
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.helpers.ReceiverUtils.isMessageFilteredOut
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages
import org.fossify.messages.transport.LocalInboundSink
import org.fossify.messages.transport.holdAndDrainInboundArrival
import org.fossify.messages.transport.storedRolePosture

/**
 * The permissions-only posture's inbound path (SMS-3).
 *
 * SMS_DELIVER reaches only the default SMS app, so a personal phone —
 * where the owner's own messenger stays default — would never feed
 * the bridge seam through [SmsReceiver]. This receiver listens for
 * the SMS_RECEIVED broadcast every app holding RECEIVE_SMS gets, and
 * hands a copy to the seam. It deliberately writes nothing: the
 * default app owns the provider row and the notification, so this
 * path must not insert, notify, or touch the conversation cache.
 *
 * Two guards keep it from ever double-processing: it acts only in
 * the PERMISSIONS_ONLY posture, and never while this app itself
 * holds the default role (there SMS_DELIVER is the one intake).
 * The same keyword/blocked filtering as the default intake applies,
 * so a message the user filtered out cannot reach the seam either.
 */
class SmsReceivedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val appContext = context.applicationContext
        if (appContext.storedRolePosture() != RolePosture.PERMISSIONS_ONLY) return
        if (Telephony.Sms.getDefaultSmsPackage(appContext) == appContext.packageName) return

        val pending = goAsync()
        ensureBackgroundThread {
            try {
                val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if (parts.isEmpty()) return@ensureBackgroundThread

                val address = parts.last().originatingAddress.orEmpty()
                if (address.isBlank()) return@ensureBackgroundThread
                val body = buildString { parts.forEach { append(it.messageBody.orEmpty()) } }

                if (isMessageFilteredOut(appContext, body)) return@ensureBackgroundThread
                if (appContext.isNumberBlocked(address)) return@ensureBackgroundThread
                if (appContext.baseConfig.blockUnknownNumbers) {
                    val privateCursor =
                        appContext.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
                    val result = SimpleContactsHelper(appContext).existsSync(address, privateCursor)
                    if (result == ContactLookupResult.NotFound) return@ensureBackgroundThread
                }

                val inbound = InboundSms(
                    sender = address,
                    body = body,
                    receivedAtMillis = parts.last().timestampMillis,
                    subscriptionId = intent.getIntExtra("subscription", -1)
                )
                LocalInboundSink().onInbound(inbound)
                // SMS-25-TOUCHPOINT 2/4: the arrival caller runs on this receiver's
                // background thread too. This path writes no provider row, so its
                // arrivals carry no source id and the caller holds none of them —
                // a held delivery needs an identity the brain can ack against.
                appContext.holdAndDrainInboundArrival(inbound)

                // Open screens reload from the provider on these events,
                // as they do for the default intake. The reload races the
                // default app's own provider insert; a screen that reloads
                // first still converges on its next resume, which is the
                // existing behaviour for any external provider change.
                refreshMessages()
                refreshConversations()
            } finally {
                pending.finish()
            }
        }
    }
}
