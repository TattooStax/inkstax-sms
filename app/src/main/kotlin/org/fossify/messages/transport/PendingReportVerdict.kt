package org.fossify.messages.transport

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import inkstax.sms.transport.OutboundAckEvent
import inkstax.sms.transport.pendingOutboundReportFor
import inkstax.sms.transport.pendingReportQueueAfterVerdict

private const val PENDING_REPORT_DRAIN_WORK_NAME = "pending_outbound_report_drain"

/**
 * The verdict caller for the pending outbound report queue: when
 * the phone reaches its verdict on a send — the ack event a
 * send-result path receives, never the transport's submission
 * outcome — the verdict is held through the stored queue, and a
 * drain of that queue is handed to the pending-report drain
 * worker, so reporting to the brain is attempted when the
 * verdict is reached instead of waiting for a later trigger.
 *
 * The hold is synchronous and the drain is not. A send-result
 * receiver's execution window bounds everything it does, and a
 * drain is one network report per pending verdict — a slow
 * endpoint or a recovered backlog would overrun the window. So
 * the caller does only the hold, which is local storage work,
 * and hands the serialized drain to lifecycle-managed work that
 * runs outside the caller's lifetime, with the network it needs
 * as its constraint. The work is unique and appended: a drain
 * enqueued behind one already pending runs after it, so two
 * drains never report the same queue at once, and a verdict
 * held while a drain runs is reported by the work appended
 * behind that drain.
 *
 * Only a verdict that may be held at all is attempted — the
 * shared holding rule decides, so an unreportable verdict (a
 * blank or whitespace-bearing source id, a negative verdict
 * time, an error code that contradicts the event) is never
 * held and never reported. Only a paired phone attempts the
 * hold: with no usable credential there is no endpoint to
 * report to, and an unpaired or local-mode phone behaves
 * exactly as it does without this caller — nothing is held,
 * nothing is reported.
 *
 * The hold runs under the credential guard, outside the queue
 * guard the drain's release also takes, in the one order every
 * caller uses: storing the queue stamps it with the pairing
 * that held it, so the credential check and the store must be
 * atomic against a disconnect and re-pair — a pairing change
 * landing between them would stamp this pairing's verdict with
 * the next pairing's identity. The queue itself is bound to
 * that pairing (see the pending report queue storage), so a
 * later pairing never reports this pairing's verdicts under
 * its own credential.
 *
 * Returns whether the verdict is pending in the stored queue
 * when the call returns — the drain is enqueued only for a
 * held verdict — and the send-result paths act on neither
 * outcome: the user-facing send handling in front of this
 * caller has already decided what the phone shows.
 */
fun Context.holdAndDrainOutboundVerdict(
    sourceId: String,
    event: OutboundAckEvent,
    errorCode: Int?,
    reportedAtMillis: Long
): Boolean {
    val pending = pendingOutboundReportFor(sourceId, event, errorCode, reportedAtMillis)
        ?: return false
    val isHeld = synchronized(CredentialGuard.lock) {
        synchronized(PendingReportQueueGuard.lock) {
            if (storedDeviceCredential() == null) {
                false
            } else {
                storePendingOutboundReportQueue(
                    pendingReportQueueAfterVerdict(
                        storedPendingOutboundReportQueue(),
                        pending.sourceId,
                        pending.event,
                        pending.errorCode,
                        pending.reportedAtMillis
                    )
                )
                storedPendingOutboundReportQueue().any { it.sourceId == pending.sourceId }
            }
        }
    }
    if (isHeld) {
        enqueuePendingOutboundReportDrain()
    }
    return isHeld
}

/**
 * Enqueues one drain of the pending outbound report queue as
 * lifecycle-managed work. Appended unique work is the
 * serialization between drains; the network constraint is the
 * drain's own precondition, so work enqueued while the phone
 * is offline runs when the network returns instead of burning
 * its one attempt against an endpoint it cannot reach.
 */
private fun Context.enqueuePendingOutboundReportDrain() {
    val request = OneTimeWorkRequestBuilder<PendingReportDrainWorker>()
        .setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        )
        .build()
    WorkManager.getInstance(this).enqueueUniqueWork(
        PENDING_REPORT_DRAIN_WORK_NAME,
        ExistingWorkPolicy.APPEND,
        request
    )
}
