package inkstax.sms.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldQueueTest {

    private val t0 = 1_000_000_000L

    private fun sms(id: Long, body: String = "hello", sender: String = "+15551234567") =
        InboundSms(
            sender = sender,
            body = body,
            receivedAtMillis = t0 + id,
            subscriptionId = 1,
            providerId = id
        )

    @Test
    fun `a handled sms with a provider id is held under its source id`() {
        val held = heldInboundMessageFor(sms(7))
        assertEquals("sms:7", held?.sourceId)
        assertEquals(sms(7), held?.message)
    }

    @Test
    fun `an mms arrival is not held because v1 does not forward it`() {
        val mms = sms(8).copy(kind = InboundKind.MMS)
        assertNull(heldInboundMessageFor(mms))
        assertTrue(heldQueueAfterArrival(emptyList(), mms).isEmpty())
    }

    @Test
    fun `a blank sender arrival is not held because the caller still owns it`() {
        val blank = sms(9, sender = "  ")
        assertNull(heldInboundMessageFor(blank))
        assertTrue(heldQueueAfterArrival(emptyList(), blank).isEmpty())
    }

    @Test
    fun `an arrival with no provider id is not held because no ack could release it`() {
        val noId = sms(10).copy(providerId = null)
        assertNull(heldInboundMessageFor(noId))
        assertTrue(heldQueueAfterArrival(emptyList(), noId).isEmpty())
    }

    @Test
    fun `a negative receipt time is not held because it could never be delivered`() {
        val negative = sms(11).copy(receivedAtMillis = -1L)
        assertNull(heldInboundMessageFor(negative))
        assertTrue(heldQueueAfterArrival(emptyList(), negative).isEmpty())
    }

    @Test
    fun `a receipt time of zero is held`() {
        val zero = sms(12).copy(receivedAtMillis = 0L)
        assertEquals("sms:12", heldInboundMessageFor(zero)?.sourceId)
    }

    @Test
    fun `arrivals are held in arrival order and the head is next`() {
        var queue = heldQueueAfterArrival(emptyList(), sms(3))
        queue = heldQueueAfterArrival(queue, sms(1))
        queue = heldQueueAfterArrival(queue, sms(2))
        assertEquals(listOf("sms:3", "sms:1", "sms:2"), queue.map { it.sourceId })
        assertEquals("sms:3", nextHeldInbound(queue)?.sourceId)
    }

    @Test
    fun `a re-seen arrival is the held message not a second entry`() {
        var queue = heldQueueAfterArrival(emptyList(), sms(4, body = "first"))
        queue = heldQueueAfterArrival(queue, sms(4, body = "seen again"))
        assertEquals(1, queue.size)
        assertEquals("first", queue.single().message.body)
    }

    @Test
    fun `only the brain ack releases the held entry it names`() {
        var queue = heldQueueAfterArrival(emptyList(), sms(1))
        queue = heldQueueAfterArrival(queue, sms(2))
        val after = heldQueueAfterDelivery(queue, "sms:1", InboundDeliveryOutcome.Acknowledged)
        assertEquals(listOf("sms:2"), after.map { it.sourceId })
    }

    @Test
    fun `every failed delivery outcome leaves the entry held in place`() {
        var queue = heldQueueAfterArrival(emptyList(), sms(1))
        queue = heldQueueAfterArrival(queue, sms(2))
        for (failure in InboundDeliveryFailure.entries) {
            val after = heldQueueAfterDelivery(
                queue, "sms:1", InboundDeliveryOutcome.Failed(failure)
            )
            assertEquals(listOf("sms:1", "sms:2"), after.map { it.sourceId })
        }
    }

    @Test
    fun `an ack naming a source id that is not held changes nothing`() {
        val queue = heldQueueAfterArrival(emptyList(), sms(1))
        val after = heldQueueAfterDelivery(queue, "sms:99", InboundDeliveryOutcome.Acknowledged)
        assertEquals(queue, after)
    }

    @Test
    fun `an empty queue has nothing to deliver`() {
        assertNull(nextHeldInbound(emptyList()))
        assertEquals(
            emptyList<HeldInboundMessage>(),
            heldQueueAfterDelivery(emptyList(), "sms:1", InboundDeliveryOutcome.Acknowledged)
        )
    }
}
