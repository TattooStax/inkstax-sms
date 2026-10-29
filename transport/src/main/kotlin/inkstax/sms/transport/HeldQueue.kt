package inkstax.sms.transport

/**
 * Held inbound message queue definitions for the bridge seam.
 *
 * FailureHonesty.kt defines the inbound half of dual ack — a
 * message stays HELD on the phone until the brain acks it — and
 * this file defines the one queue shape every binding holds
 * those messages in, so what may be held, in what order it is
 * delivered, and what releases it cannot drift between bindings.
 * The queue is a plain immutable list of values: there is no
 * storage, no timer, and no network code here — a binding keeps
 * the list, delivers its head through the inbound delivery
 * binding, and folds each delivery outcome back through these
 * functions.
 *
 * Only a message the shared disposition rule handles, carrying
 * a source id, may be held for delivery — the same conditions
 * the delivery wire itself requires, applied here at the point
 * of holding so an MMS arrival (surfaced as not forwarded in
 * V1), a blank-sender arrival the caller still owns, an arrival
 * with no provider identity, or an arrival whose receipt time
 * is negative (a caller fault the delivery binding throws for,
 * so the entry could never be acked and would block the head
 * of the queue forever) never enters a queue it could never
 * drain from. The source id is the held
 * message's identity: an arrival whose source id is already
 * held is the same message seen again, never a second entry,
 * because a duplicate entry would deliver a second copy against
 * an identity the brain files once.
 *
 * The queue drains in the order messages were held, so the
 * brain receives a conversation in the order the phone took it
 * in. An entry leaves the queue only when its delivery is
 * acknowledged — removal IS the release the dual-ack transition
 * defines — and every other outcome leaves the entry where it
 * stands, head first, for a redelivery against the same source
 * id. A lost ack therefore costs a redelivery the brain can
 * recognise by identity, never a lost message and never a
 * silently reordered one.
 */

/**
 * One held inbound message: the message itself and the source
 * id it is held under — the identity its delivery carries and
 * the brain's ack releases against.
 */
data class HeldInboundMessage(
    val message: InboundSms,
    val sourceId: String
)

/**
 * The held form of an inbound [message], or null when the
 * message may not be held for delivery: its receipt time is
 * negative, the shared disposition rule does not handle it, or
 * it carries no source id.
 */
fun heldInboundMessageFor(message: InboundSms): HeldInboundMessage? {
    if (message.receivedAtMillis < 0L) {
        return null
    }
    if (inboundDispositionFor(message).disposition != InboundDisposition.HANDLED) {
        return null
    }
    val sourceId = message.sourceId ?: return null
    return HeldInboundMessage(message, sourceId)
}

/**
 * The queue after an inbound [message] arrives: the message
 * appended in held order when it may be held and its source id
 * is not already held, and the queue unchanged otherwise — an
 * unholdable arrival stays with the caller, and a re-seen
 * arrival is the held message itself, not a copy of it.
 */
fun heldQueueAfterArrival(
    queue: List<HeldInboundMessage>,
    message: InboundSms
): List<HeldInboundMessage> {
    val held = heldInboundMessageFor(message) ?: return queue
    if (queue.any { it.sourceId == held.sourceId }) {
        return queue
    }
    return queue + held
}

/** The next message to deliver: the head of the queue, in held order. */
fun nextHeldInbound(queue: List<HeldInboundMessage>): HeldInboundMessage? {
    return queue.firstOrNull()
}

/**
 * The queue after one delivery attempt on the entry held under
 * [sourceId]: the entry is removed only when the brain
 * acknowledged the delivery, through the one transition
 * [inboundAckStateAfterDelivery] defines. Every other outcome
 * leaves the queue untouched — the entry keeps its place for a
 * redelivery, and an outcome naming a source id that is not
 * held changes nothing.
 */
fun heldQueueAfterDelivery(
    queue: List<HeldInboundMessage>,
    sourceId: String,
    outcome: InboundDeliveryOutcome
): List<HeldInboundMessage> {
    val entry = queue.firstOrNull { it.sourceId == sourceId } ?: return queue
    val state = inboundAckStateAfterDelivery(InboundAckState.HELD, outcome)
    return if (state == InboundAckState.RELEASED) {
        queue - entry
    } else {
        queue
    }
}
