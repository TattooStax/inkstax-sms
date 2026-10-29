package inkstax.sms.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingReportQueueTest {

    private val t0 = 1_000_000_000L

    @Test
    fun `a sent verdict is held under its source id`() {
        val pending = pendingOutboundReportFor(
            "sms:7", OutboundAckEvent.PHONE_ACKED_SENT, null, t0
        )
        assertEquals("sms:7", pending?.sourceId)
        assertEquals(OutboundAckEvent.PHONE_ACKED_SENT, pending?.event)
        assertNull(pending?.errorCode)
        assertEquals(t0, pending?.reportedAtMillis)
    }

    @Test
    fun `a failed verdict is held with the error code the send failed with`() {
        val pending = pendingOutboundReportFor(
            "sms:8", OutboundAckEvent.PHONE_REPORTED_FAILURE, 3, t0
        )
        assertEquals(3, pending?.errorCode)
    }

    @Test
    fun `a failed verdict with the unknown error code is held`() {
        val pending = pendingOutboundReportFor(
            "sms:9", OutboundAckEvent.PHONE_REPORTED_FAILURE, TransportOutcome.UNKNOWN_ERROR, t0
        )
        assertEquals(TransportOutcome.UNKNOWN_ERROR, pending?.errorCode)
    }

    @Test
    fun `a sent verdict carrying an error code is not held because it contradicts itself`() {
        assertNull(
            pendingOutboundReportFor("sms:10", OutboundAckEvent.PHONE_ACKED_SENT, 3, t0)
        )
    }

    @Test
    fun `a failed verdict with no error code is not held because it contradicts itself`() {
        assertNull(
            pendingOutboundReportFor("sms:11", OutboundAckEvent.PHONE_REPORTED_FAILURE, null, t0)
        )
    }

    @Test
    fun `a blank source id is not held because no report could be filed against it`() {
        assertNull(
            pendingOutboundReportFor("  ", OutboundAckEvent.PHONE_ACKED_SENT, null, t0)
        )
        assertTrue(
            pendingReportQueueAfterVerdict(
                emptyList(), "", OutboundAckEvent.PHONE_ACKED_SENT, null, t0
            ).isEmpty()
        )
    }

    @Test
    fun `a source id carrying whitespace is not held because the seam mints none`() {
        assertNull(
            pendingOutboundReportFor("sms: 12", OutboundAckEvent.PHONE_ACKED_SENT, null, t0)
        )
    }

    @Test
    fun `a negative report time is not held because it could never be reported`() {
        assertNull(
            pendingOutboundReportFor("sms:13", OutboundAckEvent.PHONE_ACKED_SENT, null, -1L)
        )
        assertTrue(
            pendingReportQueueAfterVerdict(
                emptyList(), "sms:13", OutboundAckEvent.PHONE_ACKED_SENT, null, -1L
            ).isEmpty()
        )
    }

    @Test
    fun `a report time of zero is held`() {
        assertEquals(
            "sms:14",
            pendingOutboundReportFor("sms:14", OutboundAckEvent.PHONE_ACKED_SENT, null, 0L)
                ?.sourceId
        )
    }

    @Test
    fun `verdicts are held in reached order and the head is next`() {
        var queue = pendingReportQueueAfterVerdict(
            emptyList(), "sms:3", OutboundAckEvent.PHONE_ACKED_SENT, null, t0 + 3
        )
        queue = pendingReportQueueAfterVerdict(
            queue, "sms:1", OutboundAckEvent.PHONE_REPORTED_FAILURE, 2, t0 + 1
        )
        queue = pendingReportQueueAfterVerdict(
            queue, "sms:2", OutboundAckEvent.PHONE_ACKED_SENT, null, t0 + 2
        )
        assertEquals(listOf("sms:3", "sms:1", "sms:2"), queue.map { it.sourceId })
        assertEquals("sms:3", nextPendingOutboundReport(queue)?.sourceId)
    }

    @Test
    fun `a re-seen verdict is the pending report not a second entry`() {
        var queue = pendingReportQueueAfterVerdict(
            emptyList(), "sms:4", OutboundAckEvent.PHONE_REPORTED_FAILURE, 5, t0
        )
        queue = pendingReportQueueAfterVerdict(
            queue, "sms:4", OutboundAckEvent.PHONE_ACKED_SENT, null, t0 + 1
        )
        assertEquals(1, queue.size)
        assertEquals(OutboundAckEvent.PHONE_REPORTED_FAILURE, queue.single().event)
        assertEquals(5, queue.single().errorCode)
    }

    @Test
    fun `only the brain recording releases the pending entry it names`() {
        var queue = pendingReportQueueAfterVerdict(
            emptyList(), "sms:1", OutboundAckEvent.PHONE_ACKED_SENT, null, t0
        )
        queue = pendingReportQueueAfterVerdict(
            queue, "sms:2", OutboundAckEvent.PHONE_ACKED_SENT, null, t0 + 1
        )
        val after = pendingReportQueueAfterReport(queue, "sms:1", OutboundAckOutcome.Recorded)
        assertEquals(listOf("sms:2"), after.map { it.sourceId })
    }

    @Test
    fun `every failed report outcome leaves the entry pending in place`() {
        var queue = pendingReportQueueAfterVerdict(
            emptyList(), "sms:1", OutboundAckEvent.PHONE_ACKED_SENT, null, t0
        )
        queue = pendingReportQueueAfterVerdict(
            queue, "sms:2", OutboundAckEvent.PHONE_ACKED_SENT, null, t0 + 1
        )
        for (failure in OutboundAckFailure.entries) {
            val after = pendingReportQueueAfterReport(
                queue, "sms:1", OutboundAckOutcome.Failed(failure)
            )
            assertEquals(listOf("sms:1", "sms:2"), after.map { it.sourceId })
        }
    }

    @Test
    fun `a recording naming a source id that is not pending changes nothing`() {
        val queue = pendingReportQueueAfterVerdict(
            emptyList(), "sms:1", OutboundAckEvent.PHONE_ACKED_SENT, null, t0
        )
        val after = pendingReportQueueAfterReport(queue, "sms:99", OutboundAckOutcome.Recorded)
        assertEquals(queue, after)
    }

    @Test
    fun `an empty queue has nothing to report`() {
        assertNull(nextPendingOutboundReport(emptyList()))
        assertEquals(
            emptyList<PendingOutboundReport>(),
            pendingReportQueueAfterReport(emptyList(), "sms:1", OutboundAckOutcome.Recorded)
        )
    }
}
