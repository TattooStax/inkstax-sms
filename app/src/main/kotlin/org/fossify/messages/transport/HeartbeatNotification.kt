package org.fossify.messages.transport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import inkstax.sms.transport.HeartbeatNotificationState
import org.fossify.messages.R
import org.fossify.messages.activities.MainActivity

internal const val HEARTBEAT_CHANNEL_ID = "heartbeat_link"
internal const val HEARTBEAT_NOTIFICATION_ID = 491001

/**
 * Creates the heartbeat notification channel if it does not exist
 * yet. Every path that posts or foregrounds the notification
 * creates the channel first: a phone paired before the service
 * existed reaches it through recovery paths, not through the
 * pairing path that posted the first notification, and a channel
 * must be registered before a notification can use it.
 */
internal fun Context.createHeartbeatChannel() {
    val channel = NotificationChannel(
        HEARTBEAT_CHANNEL_ID,
        getString(R.string.heartbeat_channel_name),
        NotificationManager.IMPORTANCE_LOW
    )
    getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
}

/**
 * The persistent heartbeat notification in one state: ongoing
 * and silent, because it is a standing sign of life, not an
 * alert, so it never makes a sound.
 */
internal fun buildHeartbeatNotification(context: Context, state: HeartbeatNotificationState): Notification {
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
    createHeartbeatChannel()
    val manager = getSystemService(NotificationManager::class.java)
    manager?.notify(HEARTBEAT_NOTIFICATION_ID, buildHeartbeatNotification(this, state))
}

/** Removes the persistent heartbeat notification. */
internal fun Context.cancelHeartbeatNotification() {
    getSystemService(NotificationManager::class.java)?.cancel(HEARTBEAT_NOTIFICATION_ID)
}
