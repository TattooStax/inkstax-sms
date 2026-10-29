package inkstax.sms.transport

/**
 * Scheduled drain-retry definitions for the bridge seam.
 *
 * The arrival and verdict callers attempt a drain when a message
 * or a verdict is held, and a drain that stops at its head leaves
 * everything behind it held in order. Without a later trigger, a
 * blocked drain would wait for the next arrival or verdict to be
 * retried — on a quiet phone that can be far longer than the
 * failure itself, and a single held message with no successor
 * would never be retried at all. This file defines the rhythm a
 * scheduled retry keeps, so every binding retries blocked drains
 * at the same pace. There is no scheduler and no network code
 * here — a caller supplies the periodic mechanism and reads the
 * interval back.
 *
 * The retry interval is the heartbeat interval. Retrying faster
 * cannot deliver sooner in any way the brain can distinguish —
 * the brain judges the link stale at twice the heartbeat
 * interval — and it would spend battery re-asking an endpoint
 * that has already refused or not answered. Retrying slower
 * would leave a recovered endpoint undiscovered past the point
 * where the link already reads stale. One interval is the pace
 * the failure-honesty definitions already set.
 *
 * A retry covers both queues together. The held inbound queue
 * and the pending outbound report queue block for the same
 * reasons (an unreachable endpoint, a rejected credential) and
 * recover together, so a retry that covered only one would
 * leave the other waiting for an unrelated event. A drain of an
 * empty queue is a defined no-op outcome, so a periodic retry
 * on a phone with nothing held costs one empty read per queue.
 */

/** Interval between scheduled retries of a blocked drain. */
const val DRAIN_RETRY_INTERVAL_MILLIS: Long = HEARTBEAT_INTERVAL_MILLIS

/** The delay a scheduled drain retry waits between attempts. */
fun drainRetryIntervalMillis(): Long = DRAIN_RETRY_INTERVAL_MILLIS
