package org.fossify.messages.transport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import inkstax.sms.transport.nextHeartbeatDelayMillis

/**
 * One heartbeat alarm firing. The receiver is deliberately thin:
 * it re-arms the next interval alarm first, so one lost beat can
 * never end the schedule, and then starts the one-beat service.
 * A firing that finds no usable credential ends the schedule
 * instead — the credential is gone, so there is nothing left to
 * beat with and no link left to present.
 */
class HeartbeatAlarmReceiver : BroadcastReceiver() {

    @Suppress("SwallowedException")
    override fun onReceive(context: Context, intent: Intent?) {
        if (context.storedDeviceCredential() == null) {
            context.cancelHeartbeatSchedule()
            context.cancelHeartbeatNotification()
            return
        }
        context.scheduleNextHeartbeat(nextHeartbeatDelayMillis(isFirstBeat = false))
        try {
            ContextCompat.startForegroundService(context, Intent(context, HeartbeatService::class.java))
        } catch (e: IllegalStateException) {
            // A start the platform refuses from the background
            // loses this one beat; the alarm for the next beat is
            // already set, and the brain reads the silence
            // honestly rather than this receiver hiding it.
        }
    }
}
