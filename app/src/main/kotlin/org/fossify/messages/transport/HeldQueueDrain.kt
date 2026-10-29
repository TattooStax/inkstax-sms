package org.fossify.messages.transport

import android.content.Context
import inkstax.sms.transport.InboundDeliveryOutcome
import inkstax.sms.transport.heldQueueAfterDelivery
import inkstax.sms.transport.nextHeldInbound

/**
 * Drains the stored held inbound queue through the inbound
 * delivery binding, in held order, handing each message off at
 * [handedOffAtMillis].
 *
 * Each delivery folds back through the one release rule: only
 * the brain's ack removes the head, through
 * [heldQueueAfterDelivery], and that removal is persisted and
 * read back before the next message is attempted, so a process
 * death mid-drain cannot resurrect a message whose release was
 * already reported, and cannot lose one whose release was not.
 * The queue is read back from storage for every attempt, and
 * each release re-reads, folds, and stores under the same
 * guard an arrival's hold uses (see HeldQueueGuard), with the
 * network call itself outside the guard: a drain runs outside
 * its caller's lifetime, so an arrival can be held while a
 * delivery is in flight, and a release folded onto the copy an
 * earlier attempt read would overwrite that hold with the
 * drain's older queue — losing a held message and delivering
 * the conversation out of order.
 * The first result that is not an ack stops the drain — a later
 * message must never overtake a head that is still held, because
 * the brain receives a conversation in the order the phone took
 * it in. That includes the binding's own distinct outcomes: a
 * pairing that holds no usable credential is not a link that
 * merely went quiet, and it releases nothing.
 *
 * A negative hand-off time is a caller error and throws, for the
 * same reason the delivery binding throws for one: an argument
 * fault must never be reported as a delivery outcome.
 *
 * This call blocks on the network, once per held message, so it
 * belongs on a background thread. It wires no trigger of its
 * own — the arrival caller acts on it through lifecycle-managed
 * work, and any scheduled retry acts on it the same way.
 */
fun Context.drainHeldInboundQueue(handedOffAtMillis: Long): HeldQueueDrainResult {
    require(handedOffAtMillis >= 0L) { "handedOffAtMillis must not be negative" }
    var deliveredCount = 0
    var sawHeld = false
    while (true) {
        val queue = storedHeldInboundQueue()
        val head = nextHeldInbound(queue) ?: break
        sawHeld = true
        val result = performInboundDelivery(head.message, handedOffAtMillis)
        val outcome = (result as? InboundDeliveryBindingResult.Completed)?.outcome
        if (outcome !is InboundDeliveryOutcome.Acknowledged) {
            return HeldQueueDrainResult.Blocked(
                stoppedBy = result,
                deliveredCount = deliveredCount,
                remainingCount = queue.size
            )
        }
        // The release folds onto the queue as stored now — not
        // the copy this attempt read before its network call —
        // under the guard an arrival's hold also takes, so the
        // read, fold, and store cannot interleave with a hold
        // landing while the delivery was in flight.
        val releaseStored = synchronized(HeldQueueGuard.lock) {
            val current = storedHeldInboundQueue()
            val remaining = heldQueueAfterDelivery(current, head.sourceId, outcome)
            if (storeHeldInboundQueue(remaining)) {
                true
            } else {
                // A failed commit can still leave the in-memory
                // preferences holding the released queue, so the
                // pre-release queue is written back before the
                // failure is reported: whatever that write's own
                // durability, the in-memory state a later drain
                // in this process reads is the queue with the
                // head still held, and the stored read-back
                // below is what the report counts.
                storeHeldInboundQueue(current)
                false
            }
        }
        if (!releaseStored) {
            return HeldQueueDrainResult.StorageFailed(
                deliveredCount = deliveredCount,
                remainingCount = storedHeldInboundQueue().size
            )
        }
        deliveredCount++
    }
    return if (sawHeld) {
        HeldQueueDrainResult.Drained(deliveredCount)
    } else {
        HeldQueueDrainResult.Empty
    }
}
