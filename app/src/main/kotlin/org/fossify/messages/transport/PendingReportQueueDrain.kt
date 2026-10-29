package org.fossify.messages.transport

import android.content.Context
import inkstax.sms.transport.OutboundAckOutcome
import inkstax.sms.transport.nextPendingOutboundReport
import inkstax.sms.transport.pendingReportQueueAfterReport

/**
 * Drains the stored pending outbound report queue through the
 * outbound ack binding, in reached order.
 *
 * Each report folds back through the one release rule: only
 * the brain recording the report removes the head, through
 * [pendingReportQueueAfterReport], and that removal is
 * persisted and read back before the next report is attempted,
 * so a process death mid-drain cannot resurrect a report whose
 * release was already persisted, and cannot lose one whose
 * release was not. The queue is read back from storage for
 * every attempt, and each release re-reads, folds, and stores
 * under the same guard a verdict's hold uses (see
 * PendingReportQueueGuard), with the network call itself
 * outside the guard: a drain runs outside its caller's
 * lifetime, so a verdict can be held while a report is in
 * flight, and a release folded onto the copy an earlier attempt
 * read would overwrite that hold with the drain's older queue —
 * losing a pending verdict and filing the conversation's
 * outcomes out of order.
 * The first result that is not a recording stops the drain — a
 * later report must never overtake a head that is still
 * pending, because the brain files a conversation's outcomes
 * in the order the phone reached them. That includes the
 * binding's own distinct outcomes: a pairing that holds no
 * usable credential is not a link that merely went quiet, and
 * it releases nothing.
 *
 * The drain takes no report time of its own: each pending
 * report carries the time the phone reached its verdict, and
 * that is the time the wire carries, so a re-report files the
 * verdict when it was reached, never when the drain happened
 * to run.
 *
 * Each attempt reports with the credential the queue was read
 * under, captured before the report: a disconnect and re-pair
 * landing while a report is in flight never reroutes a verdict
 * taken in under the old pairing to the new pairing's
 * endpoint. The release re-checks that pairing inside the
 * guard before it folds anything — a pairing that changed
 * mid-report leaves the stored queue, which is the new
 * pairing's, untouched; the recording already landed at the
 * pairing that was owed it, and the loop moves on to whatever
 * the current pairing holds.
 *
 * This call blocks on the network, once per pending report, so
 * it belongs on a background thread. It wires no trigger of
 * its own — the caller that holds a verdict on a send result,
 * and any scheduled retry, act on it through lifecycle-managed
 * work the same way the inbound drain is acted on.
 */
fun Context.drainPendingOutboundReportQueue(): PendingReportQueueDrainResult {
    var reportedCount = 0
    var sawPending = false
    while (true) {
        // The credential and the queue are captured under the
        // credential guard as one snapshot: credential changes
        // run under the same guard, so the queue this attempt
        // reports from is the queue of the pairing whose
        // credential it holds, never a mix of two pairings.
        val snapshot = synchronized(CredentialGuard.lock) {
            val snapshotCredential = storedDeviceCredential()
            val snapshotQueue = if (snapshotCredential == null) {
                emptyList()
            } else {
                storedPendingOutboundReportQueue()
            }
            snapshotCredential to snapshotQueue
        }
        val credential = snapshot.first
        val queue = snapshot.second
        val head = nextPendingOutboundReport(queue)
        if (credential == null || head == null) {
            break
        }
        sawPending = true
        val result = performOutboundAckReport(
            credential = credential,
            sourceId = head.sourceId,
            event = head.event,
            errorCode = head.errorCode,
            reportedAtMillis = head.reportedAtMillis
        )
        val outcome = (result as? OutboundAckBindingResult.Completed)?.outcome
        if (outcome !is OutboundAckOutcome.Recorded) {
            return PendingReportQueueDrainResult.Blocked(
                stoppedBy = result,
                reportedCount = reportedCount,
                // Read back under the guard at the stop, not
                // the size this attempt read before its network
                // call: a verdict held while the report was in
                // flight is still pending and counts here.
                remainingCount = synchronized(PendingReportQueueGuard.lock) {
                    storedPendingOutboundReportQueue().size
                }
            )
        }
        // The release folds onto the queue as stored now — not
        // the copy this attempt read before its network call —
        // under both guards, in the order every caller takes
        // them: the credential guard makes the pairing check
        // and the store atomic against credential changes
        // (which run under it too), and the queue guard a
        // verdict's hold also takes keeps the read, fold, and
        // store from interleaving with a hold landing while
        // the report was in flight. The pairing is re-checked
        // first, inside the same block: if it changed while
        // the report was in flight, the stored queue belongs
        // to the new pairing and this release stores nothing —
        // the outcome is null, distinct from a store that was
        // attempted and failed.
        val releaseStored = synchronized(CredentialGuard.lock) {
            synchronized(PendingReportQueueGuard.lock) {
                if (storedDeviceCredential() != credential) {
                    null
                } else {
                    val current = storedPendingOutboundReportQueue()
                    val remaining = pendingReportQueueAfterReport(current, head.sourceId, outcome)
                    if (storePendingOutboundReportQueue(remaining)) {
                        true
                    } else {
                        // A failed commit can still leave the in-memory
                        // preferences holding the released queue, so the
                        // pre-release queue is written back before the
                        // failure is reported: whatever that write's own
                        // durability, the in-memory state a later drain
                        // in this process reads is the queue with the
                        // head still pending, and the stored read-back
                        // below is what the report counts.
                        storePendingOutboundReportQueue(current)
                        false
                    }
                }
            }
        }
        if (releaseStored == false) {
            return PendingReportQueueDrainResult.StorageFailed(
                reportedCount = reportedCount,
                remainingCount = storedPendingOutboundReportQueue().size
            )
        }
        reportedCount++
    }
    return if (sawPending) {
        PendingReportQueueDrainResult.Drained(reportedCount)
    } else {
        PendingReportQueueDrainResult.Empty
    }
}
