package inkstax.sms.transport

/**
 * Failure-honesty states for the bridge seam.
 *
 * The bridge must never present a guess as a fact: a message is not
 * delivered because time passed, and a link is not healthy because
 * nothing has complained yet. These pure definitions are the one
 * vocabulary every binding uses for that honesty, so local and later
 * bindings cannot drift into different meanings of "sent" or "late".
 * There is no timer, no scheduler, and no network code here — a
 * binding supplies timestamps and ack events, and reads states back.
 *
 * Heartbeat: the phone heartbeats every [HEARTBEAT_INTERVAL_MILLIS].
 * One missed beat makes the link [BridgeHealth.OVERDUE]; at
 * [STALE_ALERT_AFTER_MILLIS] without a beat the link is
 * [BridgeHealth.STALE], which is when the brain raises its
 * stale-alert (the brain-written email mirror/alert channel — no
 * message ever travels by email). A link that has never heartbeated
 * is stale, not healthy: absence of a beat is not evidence of life.
 *
 * Dual ack: an inbound message stays [InboundAckState.HELD] on the
 * phone until the brain acks it, and only then is it
 * [InboundAckState.RELEASED]. An outbound reply stays
 * [OutboundAckState.QUEUED] until the phone acks it actually sent,
 * and only then may it be shown as [OutboundAckState.SENT]. Ack
 * states never move backwards: a late or duplicate ack cannot
 * un-send a sent reply, and a released inbound is never held again.
 *
 * Late arrival: a message handed off more than
 * [LATE_ARRIVAL_AFTER_MILLIS] after it was received is marked
 * received late, so a delay is visible in the thread instead of the
 * message silently appearing as if it had just arrived.
 */

/** How often the phone heartbeats to the brain. */
const val HEARTBEAT_INTERVAL_MILLIS: Long = 15 * 60 * 1000L

/** Silence longer than this makes the link stale and raises the stale-alert. */
const val STALE_ALERT_AFTER_MILLIS: Long = 30 * 60 * 1000L

/** A hand-off later than this after receipt marks the message received late. */
const val LATE_ARRIVAL_AFTER_MILLIS: Long = HEARTBEAT_INTERVAL_MILLIS

enum class BridgeHealth {
    /** A heartbeat arrived within one heartbeat interval. */
    CONNECTED,

    /** One heartbeat was missed; the link is not yet stale. */
    OVERDUE,

    /** No heartbeat within the stale window; the stale-alert fires. */
    STALE;

    val requiresStaleAlert: Boolean
        get() = this == STALE
}

/**
 * The link health at [nowMillis] given the last heartbeat the brain
 * saw. A null heartbeat means none was ever recorded, which reads
 * back stale rather than healthy. A heartbeat timestamped in the
 * future (clock skew between phone and brain) counts as just seen:
 * skew must never manufacture a stale alert.
 */
fun bridgeHealthAt(lastHeartbeatMillis: Long?, nowMillis: Long): BridgeHealth {
    if (lastHeartbeatMillis == null) {
        return BridgeHealth.STALE
    }
    val silenceMillis = maxOf(0L, nowMillis - lastHeartbeatMillis)
    return when {
        silenceMillis <= HEARTBEAT_INTERVAL_MILLIS -> BridgeHealth.CONNECTED
        silenceMillis <= STALE_ALERT_AFTER_MILLIS -> BridgeHealth.OVERDUE
        else -> BridgeHealth.STALE
    }
}

enum class InboundAckState {
    /** Received on the phone and held there until the brain acks it. */
    HELD,

    /** The brain acked; the phone may treat the message as consumed. */
    RELEASED
}

/** An inbound message is released only by the brain's ack, and never re-held. */
fun inboundAckStateAfter(current: InboundAckState, brainAckReceived: Boolean): InboundAckState {
    return if (current == InboundAckState.RELEASED || brainAckReceived) {
        InboundAckState.RELEASED
    } else {
        InboundAckState.HELD
    }
}

enum class OutboundAckState {
    /** Written but not yet acked by the phone as sent; shown as queued. */
    QUEUED,

    /** The phone acked that the message actually sent. */
    SENT,

    /** The phone reported the send failed. */
    FAILED
}

enum class OutboundAckEvent {
    PHONE_ACKED_SENT,
    PHONE_REPORTED_FAILURE
}

/**
 * The next outbound state after a phone ack event. SENT and FAILED
 * are terminal: a duplicate or contradictory late ack changes
 * nothing, so a reply once shown sent can never fall back to queued.
 */
fun outboundAckStateAfter(current: OutboundAckState, event: OutboundAckEvent): OutboundAckState {
    if (current != OutboundAckState.QUEUED) {
        return current
    }
    return when (event) {
        OutboundAckEvent.PHONE_ACKED_SENT -> OutboundAckState.SENT
        OutboundAckEvent.PHONE_REPORTED_FAILURE -> OutboundAckState.FAILED
    }
}

/**
 * Whether a message received at [receivedAtMillis] and handed off at
 * [handedOffAtMillis] must be marked received late. A hand-off
 * timestamped before receipt (clock skew) is never late.
 */
fun isReceivedLate(receivedAtMillis: Long, handedOffAtMillis: Long): Boolean {
    if (handedOffAtMillis <= receivedAtMillis) {
        return false
    }
    return handedOffAtMillis - receivedAtMillis > LATE_ARRIVAL_AFTER_MILLIS
}
