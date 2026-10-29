package org.fossify.messages.transport

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import inkstax.sms.transport.InboundSms
import inkstax.sms.transport.heldInboundMessageFor
import inkstax.sms.transport.heldQueueAfterArrival

private const val HELD_DRAIN_WORK_NAME = "held_inbound_drain"

/**
 * The arrival caller for the held inbound queue: an arriving
 * [message] is held through the stored queue, and a drain of
 * that queue is handed to the held-drain worker, so delivery
 * to the brain is attempted on arrival instead of waiting for
 * a later trigger.
 *
 * The hold is synchronous and the drain is not. A broadcast's
 * execution window bounds everything its receiver does until
 * it reports finished, and a drain is one network delivery per
 * held message — a slow endpoint or a recovered backlog would
 * overrun the window and stall the broadcasts behind it. So
 * the receiver's thread does only the hold, which is local
 * storage work, and hands the serialized drain to
 * lifecycle-managed work that runs outside the broadcast's
 * lifetime, with the network it needs as its constraint. The
 * work is unique and appended: a drain enqueued behind one
 * already pending runs after it, so two drains never deliver
 * the same queue at once, and an arrival held while a drain
 * runs is drained by the work appended behind that drain.
 *
 * Only an arrival that may be held at all is attempted — the
 * shared holding rule decides, so an MMS arrival stays not
 * forwarded and never held for delivery, a blank-sender
 * arrival the caller still owns stays unheld, and an arrival
 * with no source id has no identity a delivery could be acked
 * against. Only a paired phone attempts the hold: with no
 * usable credential there is no endpoint to deliver to, and an
 * unpaired or local-mode phone behaves exactly as it does
 * without this caller — nothing is held, nothing is delivered,
 * and the local binding's classification stands on its own.
 * The queue itself is bound to the pairing that held it (see
 * the held queue storage), so a later pairing never drains
 * this pairing's messages under its own credential.
 *
 * Returns whether the arrival is held in the stored queue
 * when the call returns — the drain is enqueued only for a
 * held arrival — and the receivers act on neither outcome:
 * the local persistence and classification in front of this
 * caller have already decided what the phone shows.
 */
fun Context.holdAndDrainInboundArrival(message: InboundSms): Boolean {
    val held = heldInboundMessageFor(message) ?: return false
    val isHeld = synchronized(HeldQueueGuard.lock) {
        if (storedDeviceCredential() == null) {
            false
        } else {
            storeHeldInboundQueue(heldQueueAfterArrival(storedHeldInboundQueue(), message))
            storedHeldInboundQueue().any { it.sourceId == held.sourceId }
        }
    }
    if (isHeld) {
        enqueueHeldInboundDrain()
    }
    return isHeld
}

/**
 * Enqueues one drain of the held inbound queue as
 * lifecycle-managed work. Appended unique work is the
 * serialization between drains; the network constraint is the
 * drain's own precondition, so work enqueued while the phone
 * is offline runs when the network returns instead of burning
 * its one attempt against an endpoint it cannot reach.
 */
private fun Context.enqueueHeldInboundDrain() {
    val request = OneTimeWorkRequestBuilder<HeldDrainWorker>()
        .setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        )
        .build()
    WorkManager.getInstance(this).enqueueUniqueWork(
        HELD_DRAIN_WORK_NAME,
        ExistingWorkPolicy.APPEND,
        request
    )
}
