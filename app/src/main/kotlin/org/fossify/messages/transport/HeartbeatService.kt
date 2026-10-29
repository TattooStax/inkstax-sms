package org.fossify.messages.transport

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import inkstax.sms.transport.HeartbeatNotificationState
import inkstax.sms.transport.heartbeatNotificationStateFor
import inkstax.sms.transport.nextHeartbeatDelayMillis
import java.util.concurrent.atomic.AtomicBoolean
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R
import org.fossify.messages.activities.MainActivity

private const val HEARTBEAT_CHANNEL_ID = "heartbeat_link"
private const val HEARTBEAT_NOTIFICATION_ID = 491001
private const val HEARTBEAT_ALARM_REQUEST_CODE = 491002
private const val HEARTBEAT_WAKE_LOCK_TAG = "inkstax:heartbeat"
private const val HEARTBEAT_WAKE_LOCK_TIMEOUT_MILLIS = 120_000L

/**
 * The scheduled heartbeat caller: while this phone is paired, an
 * alarm wakes it every heartbeat interval to beat once through
 * the heartbeat binding, and the persistent notification the
 * failure-honesty law requires stays posted between beats, so
 * the link's state is visible on the phone itself instead of
 * only being inferable from silence at the brain.
 *
 * The schedule is an alarm, not a standing service, for two
 * platform reasons. A handler timer runs on uptime, so deep
 * sleep would stretch the interval past the stale threshold
 * while the notification still claimed the link was healthy; a
 * wake-up alarm fires on wall-clock time through sleep. And a
 * dataSync foreground service may only run for a bounded budget
 * per day on current Android, so a service that never stops
 * would be terminated by the platform during normal unattended
 * use. The service below therefore lives for one beat only —
 * seconds, well inside the budget — and the persistent
 * notification is a standing notification the beat updates, not
 * the service's foreground notification, so it survives the
 * service stopping between beats. A wake lock is held only
 * while a beat runs, never between beats.
 *
 * Pairing starts the schedule, disconnect cancels it together
 * with the notification, and a beat that finds no usable
 * credential cancels both again, because a phone with no key has
 * nothing to beat with and must not present a link it cannot
 * prove. Every other outcome keeps the schedule — a failed beat
 * is exactly when the notification matters, and its text is read
 * through the :transport notification-state vocabulary, so the
 * words on the phone cannot drift from the link health the brain
 * judges. A rejected credential keeps beating and keeps showing
 * its state until the owner acts on it (disconnect, then pair
 * again); cancelling silently would hide the one failure whose
 * recovery needs the owner.
 */
class HeartbeatService : Service() {

    private val stopped = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithState(HeartbeatNotificationState.STARTING)
        ensureBackgroundThread {
            runOneBeat()
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) {
        // The platform's foreground-service budget for one beat is
        // far longer than a beat takes; if it ever expires, the
        // only honest move is to stop promptly as required.
        stopped.set(true)
        stopSelf()
    }

    override fun onDestroy() {
        // A beat still blocked on the network when the owner
        // disconnects must not publish its result afterwards: the
        // publication checks this flag, so a stopped service never
        // recreates the disconnected link's notification.
        stopped.set(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun runOneBeat() {
        val wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, HEARTBEAT_WAKE_LOCK_TAG)
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire(HEARTBEAT_WAKE_LOCK_TIMEOUT_MILLIS)
        try {
            when (val result = performHeartbeat(System.currentTimeMillis())) {
                HeartbeatBindingResult.NoUsableCredential -> {
                    if (!stopped.get()) {
                        cancelHeartbeatSchedule()
                        cancelHeartbeatNotification()
                    }
                }
                is HeartbeatBindingResult.Completed -> {
                    if (!stopped.get()) {
                        postHeartbeatNotification(heartbeatNotificationStateFor(result.outcome))
                    }
                }
            }
        } finally {
            if (wakeLock?.isHeld == true) {
                wakeLock.release()
            }
            // Detach rather than remove: the persistent
            // notification the beat just posted stays up between
            // beats, owned by the notification manager now.
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private fun startForegroundWithState(state: HeartbeatNotificationState) {
        val notification = buildHeartbeatNotification(this, state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                HEARTBEAT_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(HEARTBEAT_NOTIFICATION_ID, notification)
        }
    }
}

private fun buildHeartbeatNotification(context: Context, state: HeartbeatNotificationState): Notification {
    val contentIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    return NotificationCompat.Builder(context, HEARTBEAT_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_messenger)
        .setContentTitle(context.getString(R.string.heartbeat_notification_title))
        .setContentText(context.getString(heartbeatStateText(state)))
        .setContentIntent(contentIntent)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()
}

private fun heartbeatStateText(state: HeartbeatNotificationState): Int {
    return when (state) {
        HeartbeatNotificationState.STARTING -> R.string.heartbeat_notification_starting
        HeartbeatNotificationState.LINKED -> R.string.heartbeat_notification_linked
        HeartbeatNotificationState.UNREACHABLE -> R.string.heartbeat_notification_unreachable
        HeartbeatNotificationState.REJECTED -> R.string.heartbeat_notification_rejected
        HeartbeatNotificationState.PROBLEM -> R.string.heartbeat_notification_problem
    }
}

/** Posts or updates the persistent heartbeat notification in [state]. */
internal fun Context.postHeartbeatNotification(state: HeartbeatNotificationState) {
    val channel = NotificationChannel(
        HEARTBEAT_CHANNEL_ID,
        getString(R.string.heartbeat_channel_name),
        NotificationManager.IMPORTANCE_LOW
    )
    val manager = getSystemService(NotificationManager::class.java)
    manager?.createNotificationChannel(channel)
    manager?.notify(HEARTBEAT_NOTIFICATION_ID, buildHeartbeatNotification(this, state))
}

/** Removes the persistent heartbeat notification. */
internal fun Context.cancelHeartbeatNotification() {
    getSystemService(NotificationManager::class.java)?.cancel(HEARTBEAT_NOTIFICATION_ID)
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
 * wake-up alarm. An exact alarm is used when the platform grants
 * exact scheduling; the inexact allow-while-idle form is the
 * fallback, and its beats can land late in deep idle — the brain
 * judges staleness from recorded beats, so a late beat reads
 * honestly as a late beat rather than being hidden here.
 */
internal fun Context.scheduleNextHeartbeat(delayMillis: Long) {
    val alarmManager = getSystemService(AlarmManager::class.java) ?: return
    val triggerAtMillis = System.currentTimeMillis() + delayMillis
    val pendingIntent = heartbeatAlarmIntent()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
    } else {
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
    }
}

/** Cancels any pending heartbeat alarm. */
internal fun Context.cancelHeartbeatSchedule() {
    val alarmManager = getSystemService(AlarmManager::class.java) ?: return
    alarmManager.cancel(heartbeatAlarmIntent())
}

/**
 * Starts the heartbeat schedule for a phone that has just paired:
 * the persistent notification goes up in its starting state, the
 * first beat runs at once through the one-beat service, and the
 * interval alarm is set. A service start the platform refuses
 * because the app is in the background is swallowed rather than
 * allowed to fail the pairing that triggered it: the pairing
 * itself is complete and stored, the alarm is still set, and the
 * refused start is a scheduling gap, not a pairing failure.
 */
@Suppress("SwallowedException")
fun Context.startHeartbeatService() {
    postHeartbeatNotification(HeartbeatNotificationState.STARTING)
    scheduleNextHeartbeat(nextHeartbeatDelayMillis(isFirstBeat = false))
    try {
        ContextCompat.startForegroundService(this, Intent(this, HeartbeatService::class.java))
    } catch (e: IllegalStateException) {
        // The platform refused a background start; see the doc above.
    }
}

/** Stops the heartbeat schedule and its notification, as disconnect requires. */
fun Context.stopHeartbeatService() {
    cancelHeartbeatSchedule()
    cancelHeartbeatNotification()
    stopService(Intent(this, HeartbeatService::class.java))
}
