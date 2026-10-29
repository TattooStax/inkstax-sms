package org.fossify.messages.transport

import android.app.Activity
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R

/**
 * The disconnect caller: the one way out of the paired state the
 * :transport definitions describe (see pairingStateAfterDisconnect
 * in PairingExchange.kt). Pairing is terminal, so re-pairing this
 * phone with another endpoint — or handing it to someone else —
 * starts here: the stored credential and its on-device token are
 * discarded through SMS-7's storage, and only a verified clear is
 * reported as a disconnect. Afterwards the phone pairs afresh
 * through the same scanner flow as a never-paired phone; there is
 * no second re-pair path.
 *
 * Disconnect is destructive in exactly one way — it throws away
 * this device's only key — so it asks first. The confirmation
 * names the recovery (scan a fresh pairing code) because the key
 * itself cannot be recovered: the brain mints a new one on the
 * next pairing, it never re-issues this one.
 *
 * The clear runs under the same process-wide single-flight guard
 * as a pairing attempt, acquired only once the user confirms —
 * holding it across the confirmation would block pairing on a
 * dialog the user may walk away from, and a stuck guard would
 * wedge both flows. Inside the guard the paired state is checked
 * again, because it can have changed while the dialog was up:
 * only clearing a credential that is still there counts as a
 * disconnect, never a disconnect that did not happen. The check
 * before the dialog is only the entry condition — a phone with
 * nothing stored has nothing to disconnect and is told so
 * without a dialog.
 *
 * Both checks key on stored credential material, not on the
 * usable credential: a clear that only partly finished (one
 * store removed, the other not) leaves a phone that reads as
 * unpaired but still holds a leftover token or secret, and that
 * leftover is exactly what a retry exists to remove. Keying on
 * the usable credential would hide the entry and reject the
 * retry, stranding the leftover with no way to clear it.
 */
fun Activity.confirmPairingDisconnect(onDisconnected: () -> Unit) {
    if (PairingAttemptGuard.inFlight.get()) {
        toast(R.string.pairing_in_progress)
        return
    }
    if (!hasStoredCredentialMaterial()) {
        toast(R.string.pairing_not_paired)
        return
    }
    ConfirmationDialog(
        activity = this,
        message = "",
        messageId = R.string.pairing_disconnect_confirmation,
        positive = org.fossify.commons.R.string.yes,
        negative = org.fossify.commons.R.string.no
    ) {
        disconnectPairing(onDisconnected)
    }
}

private fun Activity.disconnectPairing(onDisconnected: () -> Unit) {
    if (!PairingAttemptGuard.inFlight.compareAndSet(false, true)) {
        toast(R.string.pairing_in_progress)
        return
    }
    ensureBackgroundThread {
        try {
            val cleared = hasStoredCredentialMaterial() && clearDeviceCredential()
            runOnUiThread {
                if (cleared) {
                    // No credential remains, so the heartbeat
                    // service has nothing to beat with and stops.
                    stopHeartbeatService()
                    cancelDrainRetrySchedule()
                    toast(R.string.pairing_disconnected)
                    onDisconnected()
                } else {
                    toast(R.string.pairing_disconnect_failed)
                }
            }
        } finally {
            PairingAttemptGuard.inFlight.set(false)
        }
    }
}
