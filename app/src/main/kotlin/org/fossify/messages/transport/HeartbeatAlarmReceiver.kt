package org.fossify.messages.transport

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import inkstax.sms.transport.nextHeartbeatDelayMillis
import org.fossify.commons.helpers.ensureBackgroundThread

private const val HEARTBEAT_ALARM_REQUEST_CODE = 491002

/**
 * One heartbeat alarm firing. The receiver is deliberately thin:
 * it re-arms the next interval alarm first, so one lost beat can
 * never end the schedule, and then runs the beat. A firing that
 * finds no usable credential ends the schedule instead — the
 * credential is gone, so there is nothing left to beat with and
 * no link left to present.
 *
 * The beat normally runs in the one-beat foreground service.
 * Only an exact alarm carries the platform's exemption for
 * starting a foreground service from the background, so when the
 * start is refused — the inexact fallback, or a permission
 * revoked since the alarm was armed — the receiver runs the beat
 * inline instead, on a background thread under goAsync: receiver
 * code itself is background-permitted, so the fallback loses no
 * beats. The async window bounds how long an inline beat may
 * take; a beat cut short by it leaves the last notification
 * state standing and the next alarm already set, which the brain
 * reads as silence, honestly, rather than as a hidden success.
 */
class HeartbeatAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (context.storedDeviceCredential() == null) {
            context.cancelHeartbeatSchedule()
            context.cancelHeartbeatNotification()
            return
        }
        context.scheduleNextHeartbeat(nextHeartbeatDelayMillis(isFirstBeat = false))
        if (context.tryStartHeartbeatBeatService()) {
            return
        }
        val pendingResult = goAsync()
        ensureBackgroundThread {
            try {
                context.runHeartbeatBeatOnce()
            } finally {
                pendingResult.finish()
            }
        }
    }
}

private fun Context.heartbeatAlarmIntent(): PendingIntent {
    return PendingIntent.getBroadcast(
        this,
        HEARTBEAT_ALARM_REQUEST_CODE,
        Intent(this, HeartbeatAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}

/**
 * Schedules the next heartbeat alarm [delayMillis] from now as a
 * wake-up alarm. The deadline is elapsed real time, not wall
 * clock: the interval measures the passage of time, and a
 * wall-clock correction must not stretch a 15-minute interval
 * past the brain's stale threshold. An exact alarm is used when
 * the platform grants exact scheduling; the inexact
 * allow-while-idle form is the fallback, and its beats can land
 * late in deep idle — the brain judges staleness from recorded
 * beats, so a late beat reads honestly as a late beat rather
 * than being hidden here. The fallback also changes how a fired
 * alarm may execute (see the alarm receiver), because only
 * exact alarms carry the platform's foreground-service start
 * exemption.
 */
internal fun Context.scheduleNextHeartbeat(delayMillis: Long) {
    val alarmManager = getSystemService(AlarmManager::class.java) ?: return
    val triggerAtMillis = SystemClock.elapsedRealtime() + delayMillis
    val pendingIntent = heartbeatAlarmIntent()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent
        )
    } else {
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent
        )
    }
}

/** Cancels any pending heartbeat alarm. */
internal fun Context.cancelHeartbeatSchedule() {
    val alarmManager = getSystemService(AlarmManager::class.java) ?: return
    alarmManager.cancel(heartbeatAlarmIntent())
}
