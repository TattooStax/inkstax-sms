package org.fossify.messages.transport

/**
 * The execution guards for the two drains: one lock per queue,
 * held across a whole drain — its reads, its network calls, and
 * its releases.
 *
 * A drain can be triggered from more than one path: the arrival
 * and verdict callers enqueue one-time work on their appended
 * unique chains, and the scheduled retry enqueues periodic work
 * under its own names. WorkManager serializes work within one
 * unique chain, not across chains, so without this guard two
 * triggers of the same drain could run at once, read the same
 * head, and deliver or report it twice before either release
 * landed. The queue guards cannot prevent that: they protect
 * storage updates only and are deliberately never held across a
 * network call. This guard is the drain-level lock instead —
 * every worker that drains a queue holds that queue's lock for
 * the whole drain, whichever trigger enqueued it, so drains of
 * one queue run one at a time in the order their workers start.
 *
 * The two queues keep separate locks because they are separate
 * drains: a long inbound drain must not delay an outbound
 * report whose queue, endpoint calls, and release rule share
 * nothing with it. No code takes one of these locks while
 * holding the other, and a drain takes the credential and queue
 * guards only inside its own execution lock, so the execution
 * lock is always the outermost lock a drain holds.
 */
object DrainExecutionGuard {
    /** Serializes drains of the held inbound queue. */
    val heldInboundLock = Any()

    /** Serializes drains of the pending outbound report queue. */
    val pendingReportLock = Any()
}
