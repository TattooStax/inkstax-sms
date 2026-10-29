package inkstax.sms.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InboundDispositionTest {

    private fun sms(
        sender: String = "+15551234567",
        kind: InboundKind = InboundKind.SMS,
        providerId: Long? = 41L
    ) = InboundSms(
        sender = sender,
        body = "hello",
        receivedAtMillis = 1_700_000_000_000L,
        subscriptionId = 1,
        kind = kind,
        providerId = providerId,
        threadId = 7L
    )

    @Test
    fun `sms text is handled`() {
        val outcome = inboundDispositionFor(sms())
        assertEquals(InboundDisposition.HANDLED, outcome.disposition)
        assertNull(outcome.reason)
    }

    @Test
    fun `mms is not forwarded in v1 instead of being dropped`() {
        val outcome = inboundDispositionFor(sms(kind = InboundKind.MMS))
        assertEquals(InboundDisposition.NOT_FORWARDED, outcome.disposition)
        assertEquals(InboundOutcome.REASON_MMS_NOT_FORWARDED_IN_V1, outcome.reason)
    }

    @Test
    fun `blank sender is deferred`() {
        val outcome = inboundDispositionFor(sms(sender = "  "))
        assertEquals(InboundDisposition.DEFERRED, outcome.disposition)
        assertEquals(InboundOutcome.REASON_BLANK_SENDER, outcome.reason)
    }

    @Test
    fun `source id renders the provider row in contract shape`() {
        assertEquals("sms:41", sms().sourceId)
        assertEquals("mms:41", sms(kind = InboundKind.MMS).sourceId)
        assertNull(sms(providerId = null).sourceId)
    }
}
