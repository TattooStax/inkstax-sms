package inkstax.sms.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureHonestyTest {

    private val t0 = 1_000_000_000L

    @Test
    fun `heartbeat timing constants match the binding law`() {
        assertEquals(15 * 60 * 1000L, HEARTBEAT_INTERVAL_MILLIS)
        assertEquals(30 * 60 * 1000L, STALE_ALERT_AFTER_MILLIS)
    }

    @Test
    fun `link is connected within one heartbeat interval`() {
        assertEquals(BridgeHealth.CONNECTED, bridgeHealthAt(t0, t0))
        assertEquals(BridgeHealth.CONNECTED, bridgeHealthAt(t0, t0 + HEARTBEAT_INTERVAL_MILLIS))
    }

    @Test
    fun `one missed heartbeat is overdue but not yet stale`() {
        assertEquals(BridgeHealth.OVERDUE, bridgeHealthAt(t0, t0 + HEARTBEAT_INTERVAL_MILLIS + 1))
        assertEquals(BridgeHealth.OVERDUE, bridgeHealthAt(t0, t0 + STALE_ALERT_AFTER_MILLIS - 1))
        assertFalse(BridgeHealth.OVERDUE.requiresStaleAlert)
    }

    @Test
    fun `silence at the stale deadline is stale and raises the alert`() {
        assertEquals(BridgeHealth.STALE, bridgeHealthAt(t0, t0 + STALE_ALERT_AFTER_MILLIS))
        assertEquals(BridgeHealth.STALE, bridgeHealthAt(t0, t0 + STALE_ALERT_AFTER_MILLIS + 1))
        assertTrue(BridgeHealth.STALE.requiresStaleAlert)
        assertFalse(BridgeHealth.CONNECTED.requiresStaleAlert)
    }

    @Test
    fun `a link that never heartbeated is stale not healthy`() {
        assertEquals(BridgeHealth.STALE, bridgeHealthAt(null, t0))
    }

    @Test
    fun `a heartbeat timestamped in the future counts as just seen`() {
        assertEquals(BridgeHealth.CONNECTED, bridgeHealthAt(t0 + 60_000L, t0))
    }

    @Test
    fun `inbound stays held until the brain acks and is never re-held`() {
        assertEquals(InboundAckState.HELD, inboundAckStateAfter(InboundAckState.HELD, false))
        assertEquals(InboundAckState.RELEASED, inboundAckStateAfter(InboundAckState.HELD, true))
        assertEquals(InboundAckState.RELEASED, inboundAckStateAfter(InboundAckState.RELEASED, false))
        assertEquals(InboundAckState.RELEASED, inboundAckStateAfter(InboundAckState.RELEASED, true))
    }

    @Test
    fun `outbound stays queued until the phone acks it sent`() {
        assertEquals(
            OutboundAckState.SENT,
            outboundAckStateAfter(OutboundAckState.QUEUED, OutboundAckEvent.PHONE_ACKED_SENT)
        )
        assertEquals(
            OutboundAckState.FAILED,
            outboundAckStateAfter(OutboundAckState.QUEUED, OutboundAckEvent.PHONE_REPORTED_FAILURE)
        )
    }

    @Test
    fun `outbound terminal states ignore late or duplicate acks`() {
        assertEquals(
            OutboundAckState.SENT,
            outboundAckStateAfter(OutboundAckState.SENT, OutboundAckEvent.PHONE_REPORTED_FAILURE)
        )
        assertEquals(
            OutboundAckState.FAILED,
            outboundAckStateAfter(OutboundAckState.FAILED, OutboundAckEvent.PHONE_ACKED_SENT)
        )
    }

    @Test
    fun `a hand-off within the late window is not late`() {
        assertFalse(isReceivedLate(t0, t0))
        assertFalse(isReceivedLate(t0, t0 + LATE_ARRIVAL_AFTER_MILLIS))
    }

    @Test
    fun `a hand-off past the late window is received late`() {
        assertTrue(isReceivedLate(t0, t0 + LATE_ARRIVAL_AFTER_MILLIS + 1))
    }

    @Test
    fun `a hand-off timestamped before receipt is never late`() {
        assertFalse(isReceivedLate(t0, t0 - 60_000L))
    }
}
