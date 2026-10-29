package org.fossify.messages.transport

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import inkstax.sms.transport.HeartbeatNotificationState
import inkstax.sms.transport.heartbeatNotificationStateFor
import inkstax.sms.transport.nextHeartbeatDelayMillis
import java.util.concurrent.atomic.AtomicBoolean
import org.fossify.commons.helpers.ensureBackgroundThread

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
 * wake-up alarm fires through sleep, on elapsed real time so a
 * wall-clock correction cannot stretch the interval either. And
 * a dataSync foreground service may only run for a bounded
 * budget per day on current Android, so a service that never
 * stops would be terminated by the platform during normal
 * unattended use. The service therefore lives for one beat only
 * — seconds, well inside the budget — and the persistent
 * notification is a standing notification each beat updates, not
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

    override fun onCreate() {
        super.onCreate()
        createHeartbeatChannel()
    }

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
        try {
            runHeartbeatBeatOnce { stopped.get() }
        } finally {
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

/**
 * Runs one heartbeat beat for this context and publishes its
 * result, holding a wake lock only while the beat runs.
 * [isStopped] reports whether the owner has gone away (a service
 * being destroyed); a stopped owner publishes nothing, so a beat
 * that finishes after disconnect never recreates the
 * disconnected link's notification. A beat that finds no usable
 * credential cancels the schedule and the notification instead.
 *
 * The foreground service runs this on its background thread. The
 * alarm receiver also runs it, inline, when the platform will
 * not let an alarm start the service — receiver code itself is
 * background-permitted, so the beat still happens on the
 * fallback path instead of every unattended beat being skipped.
 */
internal fun Context.runHeartbeatBeatOnce(isStopped: () -> Boolean = { false }) {
    val wakeLock = getSystemService(PowerManager::class.java)
        ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, HEARTBEAT_WAKE_LOCK_TAG)
    wakeLock?.setReferenceCounted(false)
    wakeLock?.acquire(HEARTBEAT_WAKE_LOCK_TIMEOUT_MILLIS)
    try {
        when (val result = performHeartbeat(System.currentTimeMillis())) {
            HeartbeatBindingResult.NoUsableCredential -> {
                if (!isStopped()) {
                    cancelHeartbeatSchedule()
                    cancelHeartbeatNotification()
                }
            }
            is HeartbeatBindingResult.Completed -> {
                if (!isStopped()) {
                    postHeartbeatNotification(heartbeatNotificationStateFor(result.outcome))
                }
            }
        }
    } finally {
        if (wakeLock?.isHeld == true) {
            wakeLock.release()
        }
    }
}

/**
 * Tries to start the one-beat foreground service, reporting
 * whether the platform accepted the start. Only an exact alarm
 * and a few other moments carry the platform's exemption for
 * starting a foreground service from the background, so callers
 * whose start may be refused get the refusal as a result rather
 * than an exception: each of them has an inline fallback that
 * runs the beat itself, and a refused start must select that
 * fallback, never lose the beat or fail the caller.
 */
@Suppress("SwallowedException")
internal fun Context.tryStartHeartbeatBeatService(): Boolean {
    return try {
        ContextCompat.startForegroundService(this, Intent(this, HeartbeatService::class.java))
        true
    } catch (e: IllegalStateException) {
        // No background-start exemption for this caller; the
        // caller runs the beat inline instead.
        false
    }
}

/**
 * Starts the heartbeat schedule for a phone that has just paired:
 * the persistent notification goes up in its starting state, the
 * first beat runs at once through the one-beat service, and the
 * interval alarm is set. A service start the platform refuses
 * because the app is in the background is swallowed rather than
 * allowed to fail the pairing that triggered it: the pairing
 * itself is complete and stored, the alarm is still set, and the
 * refused start loses only the first beat, never the schedule.
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

/**
 * Re-arms the heartbeat schedule for a paired phone when the app
 * comes to the foreground, and beats at once. This is the
 * recovery for a start the platform refused while the app was in
 * the background during pairing: the pairing was preserved, and
 * the next foreground visit restores the schedule instead of the
 * stored credential stranding the phone with no way to re-pair
 * and nothing beating. An unpaired phone has nothing to ensure.
 * The notification is left as it stands — the immediate beat's
 * result updates it within seconds.
 */
@Suppress("SwallowedException")
fun Context.ensureHeartbeatSchedule() {
    if (storedDeviceCredential() == null) {
        return
    }
    scheduleNextHeartbeat(nextHeartbeatDelayMillis(isFirstBeat = false))
    try {
        ContextCompat.startForegroundService(this, Intent(this, HeartbeatService::class.java))
    } catch (e: IllegalStateException) {
        // Still background-restricted; the alarm above is set, and
        // the next foreground visit tries the immediate beat again.
    }
}

/** Stops the heartbeat schedule and its notification, as disconnect requires. */
fun Context.stopHeartbeatService() {
    cancelHeartbeatSchedule()
    cancelHeartbeatNotification()
    stopService(Intent(this, HeartbeatService::class.java))
}
