package org.fossify.messages.transport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import inkstax.sms.transport.HeartbeatNotificationState
import inkstax.sms.transport.nextHeartbeatDelayMillis
import org.fossify.commons.helpers.ensureBackgroundThread

/**
 * A completed boot, for a paired phone: the reboot cleared the
 * heartbeat alarm and removed the persistent notification, and
 * alarms do not survive a reboot on their own, so without this
 * receiver a paired phone would stay silent after every restart
 * until the owner happened to open Settings — the link reading
 * stale at the brain for a reason the phone could have fixed
 * itself. The receiver restores exactly what the reboot took:
 * the notification goes back up in its starting state (no beat
 * has completed since the boot, so nothing stronger may be
 * claimed), the interval alarm is re-armed, and a first beat
 * runs at once.
 *
 * An unpaired phone restores nothing: it holds no credential to
 * beat with and no link to present, and a stored credential is
 * the only signal that this phone was paired before the reboot.
 * A force-stopped app receives no boot broadcast at all — that
 * is a platform rule no receiver can change — so the
 * Settings-visit re-arm remains the recovery for that case.
 *
 * The beat's path depends on the platform version. Current
 * Android forbids starting a dataSync foreground service from a
 * boot broadcast, and it enforces the ban inside the service's
 * own foreground call rather than at the start request — so on
 * those versions an accepted start would still fail, inside the
 * service, after this receiver had already given up its inline
 * path. The boot beat therefore runs inline there, on a
 * background thread under goAsync: receiver code itself is
 * background-permitted. On older versions the beat runs in the
 * one-beat foreground service, with the same inline path as the
 * fallback when that start is refused — the arrangement the
 * alarm receiver uses, for the same reason. The alarm is set
 * before either path runs, so a beat lost to a refused start or
 * a cut-short inline window costs one beat, never the schedule.
 */
class HeartbeatBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }
        if (context.storedDeviceCredential() == null) {
            return
        }
        context.postHeartbeatNotification(HeartbeatNotificationState.STARTING)
        context.scheduleNextHeartbeat(nextHeartbeatDelayMillis(isFirstBeat = false))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            context.tryStartHeartbeatBeatService()
        ) {
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
