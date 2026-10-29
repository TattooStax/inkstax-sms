package org.fossify.messages.transport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import inkstax.sms.transport.HeartbeatNotificationState
import inkstax.sms.transport.heartbeatNotificationStateFor
import inkstax.sms.transport.nextHeartbeatDelayMillis
import java.util.concurrent.atomic.AtomicBoolean
import org.fossify.messages.R
import org.fossify.messages.activities.MainActivity

private const val HEARTBEAT_CHANNEL_ID = "heartbeat_link"
private const val HEARTBEAT_NOTIFICATION_ID = 491001
private const val HEARTBEAT_THREAD_NAME = "heartbeat"

/**
 * The scheduled heartbeat caller: while this phone is paired, it
 * beats every heartbeat interval through the heartbeat binding
 * and holds the persistent foreground notification the
 * failure-honesty law requires, so the link's state is visible on
 * the phone itself instead of only being inferable from silence
 * at the brain.
 *
 * The service runs only while a credential is stored: pairing
 * starts it, disconnect stops it, and a beat that finds no usable
 * credential stops it again, because a phone with no key has
 * nothing to beat with and must not present a link it cannot
 * prove. Every other outcome keeps the schedule — a failed beat
 * is exactly when the notification matters, and its text is read
 * through the :transport notification-state vocabulary, so the
 * words on the phone cannot drift from the link health the brain
 * judges. A rejected credential keeps beating and keeps showing
 * its state until the owner acts on it (disconnect, then pair
 * again); stopping silently would hide the one failure whose
 * recovery needs the owner.
 *
 * Beats run on this service's own background thread because the
 * binding blocks on the network. The first beat is immediate and
 * later beats wait one full interval, per the :transport schedule
 * definitions. The notification is ongoing and silent: it is a
 * standing sign of life, not an alert, so it never makes a sound.
 */
class HeartbeatService : Service() {

    private val scheduled = AtomicBoolean(false)
    private var beatThread: HandlerThread? = null
    private var beatHandler: Handler? = null

    private val beatRunnable = object : Runnable {
        override fun run() {
            val handler = beatHandler ?: return
            when (val result = performHeartbeat(System.currentTimeMillis())) {
                HeartbeatBindingResult.NoUsableCredential -> stopSelf()
                is HeartbeatBindingResult.Completed -> {
                    notifyState(heartbeatNotificationStateFor(result.outcome))
                    handler.postDelayed(this, nextHeartbeatDelayMillis(isFirstBeat = false))
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createHeartbeatChannel()
        val thread = HandlerThread(HEARTBEAT_THREAD_NAME)
        thread.start()
        beatThread = thread
        beatHandler = Handler(thread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithState(HeartbeatNotificationState.STARTING)
        if (scheduled.compareAndSet(false, true)) {
            beatHandler?.postDelayed(beatRunnable, nextHeartbeatDelayMillis(isFirstBeat = true))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scheduled.set(false)
        beatHandler?.removeCallbacks(beatRunnable)
        beatThread?.quitSafely()
        beatHandler = null
        beatThread = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundWithState(state: HeartbeatNotificationState) {
        val notification = heartbeatNotification(state)
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

    private fun notifyState(state: HeartbeatNotificationState) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(HEARTBEAT_NOTIFICATION_ID, heartbeatNotification(state))
    }

    private fun heartbeatNotification(state: HeartbeatNotificationState): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, HEARTBEAT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_messenger)
            .setContentTitle(getString(R.string.heartbeat_notification_title))
            .setContentText(getString(heartbeatStateText(state)))
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

    private fun createHeartbeatChannel() {
        val channel = NotificationChannel(
            HEARTBEAT_CHANNEL_ID,
            getString(R.string.heartbeat_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
}

/**
 * Starts the heartbeat service for a phone that has just paired.
 * A start the platform refuses because the app is in the
 * background is swallowed rather than allowed to fail the pairing
 * that triggered it: the pairing itself is complete and stored,
 * and the refused start is a scheduling gap, not a pairing
 * failure. The caller cannot report the service as running in
 * that case — it can only be observed on the phone, in the
 * persistent notification's presence.
 */
@Suppress("SwallowedException")
fun Context.startHeartbeatService() {
    try {
        ContextCompat.startForegroundService(this, Intent(this, HeartbeatService::class.java))
    } catch (e: IllegalStateException) {
        // The platform refused a background start; see the doc above.
    }
}

/** Stops the heartbeat service, as disconnect requires. */
fun Context.stopHeartbeatService() {
    stopService(Intent(this, HeartbeatService::class.java))
}
