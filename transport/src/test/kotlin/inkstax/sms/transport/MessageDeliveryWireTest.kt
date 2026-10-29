package inkstax.sms.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageDeliveryWireTest {

    private val credential = DeviceCredential("https://brain.example", "device-1", "secret-abc")
    private val receivedAt = 1_700_000_000_000L
    private val message = InboundSms(
        sender = "+17015550123",
        body = "Hello, I would like a tattoo",
        receivedAtMillis = receivedAt,
        subscriptionId = 1,
        providerId = 41L,
        threadId = 7L
    )

    @Test
    fun `the request posts the message to the inbound path on the credential endpoint`() {
        val request = inboundDeliveryRequestFor(credential, message, receivedAt)
        assertEquals("https://brain.example/companion/inbound", request?.url)
        assertEquals(
            """{"device_id":"device-1","source_id":"sms:41","sender":"+17015550123","body":"Hello, I would like a tattoo","received_at_millis":"1700000000000","received_late":"false"}""",
            request?.body
        )
    }

    @Test
    fun `the secret travels only in the authorization value never in the body`() {
        val request = inboundDeliveryRequestFor(credential, message, receivedAt)
        assertEquals("Bearer c2VjcmV0LWFiYw", request?.authorization)
        assertFalse(request!!.body.contains("secret-abc"))
        assertFalse(request.url.contains("secret-abc"))
        assertFalse(request.authorization.contains("secret-abc"))
    }

    @Test
    fun `any minted secret encodes to a valid authorization value that decodes back to itself`() {
        val quoted = DeviceCredential("https://brain.example", "device-1", "sec\"ret-Abc")
        assertEquals("Bearer c2VjInJldC1BYmM", inboundDeliveryRequestFor(quoted, message, receivedAt)?.authorization)
        val control = DeviceCredential("https://brain.example", "device-1", "asecret")
        val authorization = inboundDeliveryRequestFor(control, message, receivedAt)?.authorization
        val decoded = java.util.Base64.getUrlDecoder().decode(authorization!!.removePrefix("Bearer "))
        assertEquals("asecret", String(decoded, Charsets.UTF_8))
    }

    @Test
    fun `a credential endpoint with a trailing slash still builds the one inbound path`() {
        val slashed = DeviceCredential("https://brain.example/", "device-1", "secret-abc")
        assertEquals(
            "https://brain.example/companion/inbound",
            inboundDeliveryRequestFor(slashed, message, receivedAt)?.url
        )
    }

    @Test
    fun `the request rendering never shows the secret raw or encoded`() {
        val rendered = inboundDeliveryRequestFor(credential, message, receivedAt).toString()
        assertFalse(rendered.contains("secret-abc"))
        assertFalse(rendered.contains("c2VjcmV0LWFiYw"))
        assertTrue(rendered.contains("<redacted>"))
    }

    @Test
    fun `the request body escapes the sender and body exactly`() {
        val tricky = message.copy(sender = "+1 \"quoted\"", body = "line one\nline\\two")
        val request = inboundDeliveryRequestFor(credential, tricky, receivedAt)
        assertEquals(
            """{"device_id":"device-1","source_id":"sms:41","sender":"+1 \"quoted\"","body":"line one\nline\\two","received_at_millis":"1700000000000","received_late":"false"}""",
            request?.body
        )
    }

    @Test
    fun `a credential the seam does not accept builds no request`() {
        assertNull(inboundDeliveryRequestFor(DeviceCredential("http://brain.example", "device-1", "secret-abc"), message, receivedAt))
        assertNull(inboundDeliveryRequestFor(DeviceCredential("https://brain.example/path", "device-1", "secret-abc"), message, receivedAt))
        assertNull(inboundDeliveryRequestFor(DeviceCredential("https://brain.example", "", "secret-abc"), message, receivedAt))
        assertNull(inboundDeliveryRequestFor(DeviceCredential("https://brain.example", "device-1", ""), message, receivedAt))
        assertNull(inboundDeliveryRequestFor(DeviceCredential("https://brain.example", "device-1", " secret-abc "), message, receivedAt))
    }

    @Test
    fun `negative timestamps build no request and zero is a timestamp`() {
        assertNull(inboundDeliveryRequestFor(credential, message.copy(receivedAtMillis = -1L), receivedAt))
        assertNull(inboundDeliveryRequestFor(credential, message, -1L))
        assertTrue(inboundDeliveryRequestFor(credential, message.copy(receivedAtMillis = 0L), 0L) != null)
    }

    @Test
    fun `a message the disposition rule does not handle builds no request`() {
        assertNull(inboundDeliveryRequestFor(credential, message.copy(kind = InboundKind.MMS), receivedAt))
        assertNull(inboundDeliveryRequestFor(credential, message.copy(sender = "  "), receivedAt))
        assertNull(inboundDeliveryRequestFor(credential, message.copy(providerId = null), receivedAt))
    }

    @Test
    fun `a hand-off more than one interval after receipt is marked received late at the boundary`() {
        val atBoundary = inboundDeliveryRequestFor(credential, message, receivedAt + LATE_ARRIVAL_AFTER_MILLIS)
        assertTrue(atBoundary!!.body.contains("\"received_late\":\"false\""))
        val pastBoundary = inboundDeliveryRequestFor(credential, message, receivedAt + LATE_ARRIVAL_AFTER_MILLIS + 1L)
        assertTrue(pastBoundary!!.body.contains("\"received_late\":\"true\""))
        val skewed = inboundDeliveryRequestFor(credential, message, receivedAt - 1L)
        assertTrue(skewed!!.body.contains("\"received_late\":\"false\""))
    }

    @Test
    fun `no content is the brain ack`() {
        assertEquals(InboundDeliveryOutcome.Acknowledged, inboundDeliveryOutcomeFor(204))
    }

    @Test
    fun `no answer at all is unreachable and the message stays held`() {
        assertEquals(
            InboundDeliveryOutcome.Failed(InboundDeliveryFailure.NETWORK_UNREACHABLE),
            inboundDeliveryOutcomeFor(null)
        )
    }

    @Test
    fun `a credential refusal is credential rejected not a transient failure`() {
        assertEquals(InboundDeliveryOutcome.Failed(InboundDeliveryFailure.CREDENTIAL_REJECTED), inboundDeliveryOutcomeFor(401))
        assertEquals(InboundDeliveryOutcome.Failed(InboundDeliveryFailure.CREDENTIAL_REJECTED), inboundDeliveryOutcomeFor(403))
    }

    @Test
    fun `other client errors are the endpoint refusing the delivery`() {
        for (status in listOf(400, 404, 422)) {
            assertEquals(InboundDeliveryOutcome.Failed(InboundDeliveryFailure.ENDPOINT_REJECTED), inboundDeliveryOutcomeFor(status))
        }
    }

    @Test
    fun `an unexpected status is an answer that is not an ack`() {
        val malformed = InboundDeliveryOutcome.Failed(InboundDeliveryFailure.MALFORMED_RESPONSE)
        assertEquals(malformed, inboundDeliveryOutcomeFor(200))
        assertEquals(malformed, inboundDeliveryOutcomeFor(201))
        assertEquals(malformed, inboundDeliveryOutcomeFor(302))
        assertEquals(malformed, inboundDeliveryOutcomeFor(500))
    }

    @Test
    fun `only the brain ack releases a held message and a released message is never re-held`() {
        assertEquals(
            InboundAckState.RELEASED,
            inboundAckStateAfterDelivery(InboundAckState.HELD, InboundDeliveryOutcome.Acknowledged)
        )
        assertEquals(
            InboundAckState.HELD,
            inboundAckStateAfterDelivery(
                InboundAckState.HELD,
                InboundDeliveryOutcome.Failed(InboundDeliveryFailure.NETWORK_UNREACHABLE)
            )
        )
        assertEquals(
            InboundAckState.RELEASED,
            inboundAckStateAfterDelivery(
                InboundAckState.RELEASED,
                InboundDeliveryOutcome.Failed(InboundDeliveryFailure.NETWORK_UNREACHABLE)
            )
        )
    }
}
