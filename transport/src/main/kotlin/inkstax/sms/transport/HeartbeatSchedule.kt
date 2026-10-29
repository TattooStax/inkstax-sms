package inkstax.sms.transport

/**
 * Heartbeat schedule and notification-state definitions for the
 * bridge seam.
 *
 * FailureHonesty.kt defines what link health means and
 * HeartbeatWire.kt defines the one wire shape a beat takes; this
 * file defines the rhythm the scheduled caller keeps and the one
 * vocabulary its persistent notification speaks, so every binding
 * that schedules a beat beats at the same pace and reports the
 * link in the same words. There is no timer, no scheduler, and no
 * network code here — a caller supplies beat outcomes and reads
 * delays and states back.
 *
 * The first beat is immediate: a phone that has just paired has
 * shown no sign of life yet, and waiting a full interval for the
 * first beat would leave the link unproven for exactly the window
 * in which the owner is watching it. Every later beat waits one
 * full [HEARTBEAT_INTERVAL_MILLIS], whatever the last outcome
 * was: beating faster after a failure cannot make a silent link
 * stale sooner — staleness is judged from the brain's last
 * recorded beat — it would only spend the phone's battery
 * re-asking a question the interval already asks.
 *
 * The notification state is read from the latest outcome alone. A
 * caller that has not beaten yet holds no evidence about the
 * link, so it reads as starting, never as linked: absence of a
 * beat is not evidence of life, the same rule [bridgeHealthAt]
 * applies to a link that has never heartbeated. A rejected
 * credential is its own state because its recovery differs from
 * every other failure — re-pairing, not waiting — and the
 * persistent notification is where the owner learns that.
 */

/** Delay before the first beat after the scheduled caller starts. */
const val HEARTBEAT_FIRST_DELAY_MILLIS: Long = 0L

/**
 * The delay before the next beat: none for the first beat after
 * the caller starts, one full heartbeat interval for every beat
 * after that, whatever the previous outcome was.
 */
fun nextHeartbeatDelayMillis(isFirstBeat: Boolean): Long {
    return if (isFirstBeat) HEARTBEAT_FIRST_DELAY_MILLIS else HEARTBEAT_INTERVAL_MILLIS
}

/** What the persistent heartbeat notification reports about the link. */
enum class HeartbeatNotificationState {
    /** No beat has completed yet; nothing is claimed about the link. */
    STARTING,

    /** The latest beat was recorded by the brain. */
    LINKED,

    /** The latest beat never reached the endpoint. */
    UNREACHABLE,

    /** The endpoint no longer accepts this device's credential. */
    REJECTED,

    /** The endpoint answered, but not with the recording ack. */
    PROBLEM
}

/**
 * The notification state for the latest heartbeat [outcome], or
 * the starting state when no beat has completed yet ([outcome]
 * is null).
 */
fun heartbeatNotificationStateFor(outcome: HeartbeatOutcome?): HeartbeatNotificationState {
    return when (outcome) {
        null -> HeartbeatNotificationState.STARTING
        HeartbeatOutcome.Recorded -> HeartbeatNotificationState.LINKED
        is HeartbeatOutcome.Failed -> when (outcome.failure) {
            HeartbeatFailure.NETWORK_UNREACHABLE -> HeartbeatNotificationState.UNREACHABLE
            HeartbeatFailure.CREDENTIAL_REJECTED -> HeartbeatNotificationState.REJECTED
            HeartbeatFailure.ENDPOINT_REJECTED,
            HeartbeatFailure.MALFORMED_RESPONSE -> HeartbeatNotificationState.PROBLEM
        }
    }
}
