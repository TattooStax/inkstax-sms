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
 * own — the arrival caller and any scheduled retry act on it in
 * later slices.
 */
fun Context.drainHeldInboundQueue(handedOffAtMillis: Long): HeldQueueDrainResult {
    require(handedOffAtMillis >= 0L) { "handedOffAtMillis must not be negative" }
    var queue = storedHeldInboundQueue()
    if (queue.isEmpty()) {
        return HeldQueueDrainResult.Empty
    }
    var deliveredCount = 0
    while (true) {
        val head = nextHeldInbound(queue) ?: break
        val result = performInboundDelivery(head.message, handedOffAtMillis)
        val outcome = (result as? InboundDeliveryBindingResult.Completed)?.outcome
        if (outcome !is InboundDeliveryOutcome.Acknowledged) {
            return HeldQueueDrainResult.Blocked(
                stoppedBy = result,
                deliveredCount = deliveredCount,
                remainingCount = queue.size
            )
        }
        val remaining = heldQueueAfterDelivery(queue, head.sourceId, outcome)
        if (!storeHeldInboundQueue(remaining)) {
            return HeldQueueDrainResult.StorageFailed(
                deliveredCount = deliveredCount,
                remainingCount = storedHeldInboundQueue().size
            )
        }
        deliveredCount++
        queue = remaining
    }
    return HeldQueueDrainResult.Drained(deliveredCount)
}
