package inkstax.sms.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class DrainRetryTest {

    @Test
    fun retryIntervalIsFifteenMinutes() {
        assertEquals(15 * 60 * 1000L, DRAIN_RETRY_INTERVAL_MILLIS)
        assertEquals(DRAIN_RETRY_INTERVAL_MILLIS, drainRetryIntervalMillis())
    }

    @Test
    fun retryIntervalMatchesHeartbeatInterval() {
        // A blocked drain is retried at the pace the brain already
        // uses to judge the link, never faster or slower.
        assertEquals(HEARTBEAT_INTERVAL_MILLIS, drainRetryIntervalMillis())
    }
}
