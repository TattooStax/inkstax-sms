package inkstax.sms.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class HeartbeatScheduleTest {

    @Test
    fun firstBeatIsImmediate() {
        assertEquals(0L, HEARTBEAT_FIRST_DELAY_MILLIS)
        assertEquals(HEARTBEAT_FIRST_DELAY_MILLIS, nextHeartbeatDelayMillis(isFirstBeat = true))
    }

    @Test
    fun laterBeatsWaitOneFullInterval() {
        assertEquals(HEARTBEAT_INTERVAL_MILLIS, nextHeartbeatDelayMillis(isFirstBeat = false))
        assertEquals(15 * 60 * 1000L, nextHeartbeatDelayMillis(isFirstBeat = false))
    }

    @Test
    fun noBeatYetReadsAsStartingNeverLinked() {
        assertEquals(HeartbeatNotificationState.STARTING, heartbeatNotificationStateFor(null))
    }

    @Test
    fun recordedBeatReadsAsLinked() {
        assertEquals(
            HeartbeatNotificationState.LINKED,
            heartbeatNotificationStateFor(HeartbeatOutcome.Recorded)
        )
    }

    @Test
    fun unreachableBeatReadsAsUnreachable() {
        assertEquals(
            HeartbeatNotificationState.UNREACHABLE,
            heartbeatNotificationStateFor(HeartbeatOutcome.Failed(HeartbeatFailure.NETWORK_UNREACHABLE))
        )
    }

    @Test
    fun rejectedCredentialReadsAsRejected() {
        assertEquals(
            HeartbeatNotificationState.REJECTED,
            heartbeatNotificationStateFor(HeartbeatOutcome.Failed(HeartbeatFailure.CREDENTIAL_REJECTED))
        )
    }

    @Test
    fun otherFailuresReadAsProblem() {
        assertEquals(
            HeartbeatNotificationState.PROBLEM,
            heartbeatNotificationStateFor(HeartbeatOutcome.Failed(HeartbeatFailure.ENDPOINT_REJECTED))
        )
        assertEquals(
            HeartbeatNotificationState.PROBLEM,
            heartbeatNotificationStateFor(HeartbeatOutcome.Failed(HeartbeatFailure.MALFORMED_RESPONSE))
        )
    }
}
