package inkstax.sms.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboundAckWireTest {

    private val credential = DeviceCredential("https://brain.example", "device-1", "secret-abc")
    private val reportedAt = 1_700_000_060_000L

    @Test
    fun `a sent report posts the verdict to the outbound ack path on the credential endpoint`() {
        val request = outboundAckRequestFor(credential, "sms:41", TransportOutcome.Sent, reportedAt)
        assertEquals("https://brain.example/companion/outbound-ack", request?.url)
        assertEquals(
            """{"device_id":"device-1","source_id":"sms:41","outcome":"sent","reported_at_millis":"1700000060000"}""",
            request?.body
        )
    }

    @Test
    fun `a failed report carries the sending error code and no free text detail`() {
        val request = outboundAckRequestFor(
            credential,
            "sms:41",
            TransportOutcome.Failed(TransportOutcome.ERROR_SENDING_MESSAGE, "radio exploded"),
            reportedAt
        )
        assertEquals(
            """{"device_id":"device-1","source_id":"sms:41","outcome":"failed","error_code":"-3","reported_at_millis":"1700000060000"}""",
            request?.body
        )
        assertFalse(request!!.body.contains("radio exploded"))
    }

    @Test
    fun `the secret travels only in the authorization value never in the body`() {
        val request = outboundAckRequestFor(credential, "sms:41", TransportOutcome.Sent, reportedAt)
        assertFalse(request!!.body.contains("secret-abc"))
        assertFalse(request.url.contains("secret-abc"))
        assertFalse(request.authorization.contains("secret-abc"))
    }

    @Test
    fun `any minted secret encodes to a valid authorization value that decodes back to itself`() {
        val quoted = DeviceCredential("https://brain.example", "device-1", "sec\"ret-Abc")
        val quotedAuth = outboundAckRequestFor(quoted, "sms:41", TransportOutcome.Sent, reportedAt)?.authorization
        assertTrue(quotedAuth != null)
        val control = DeviceCredential("https://brain.example", "device-1", "a\u0001secret")
        val authorization = outboundAckRequestFor(control, "sms:41", TransportOutcome.Sent, reportedAt)?.authorization
        val decoded = java.util.Base64.getUrlDecoder().decode(authorization!!.removePrefix("Bearer "))
        assertEquals("a\u0001secret", String(decoded, Charsets.UTF_8))
    }

    @Test
    fun `a credential endpoint with a trailing slash still builds the one outbound ack path`() {
        val slashed = DeviceCredential("https://brain.example/", "device-1", "secret-abc")
        assertEquals(
            "https://brain.example/companion/outbound-ack",
            outboundAckRequestFor(slashed, "sms:41", TransportOutcome.Sent, reportedAt)?.url
        )
    }

    @Test
    fun `the request rendering never shows the secret raw or encoded`() {
        val rendered = outboundAckRequestFor(credential, "sms:41", TransportOutcome.Sent, reportedAt).toString()
        assertFalse(rendered.contains("secret-abc"))
        assertFalse(rendered.contains("c2VjcmV0LWFiYw"))
        assertTrue(rendered.contains("<redacted>"))
    }

    @Test
    fun `the request body escapes the source id exactly`() {
        val request = outboundAckRequestFor(credential, "sms:\"41\"", TransportOutcome.Sent, reportedAt)
        assertEquals(
            """{"device_id":"device-1","source_id":"sms:\"41\"","outcome":"sent","reported_at_millis":"1700000060000"}""",
            request?.body
        )
    }

    @Test
    fun `a credential the seam does not accept builds no report`() {
        assertNull(outboundAckRequestFor(DeviceCredential("http://brain.example", "device-1", "secret-abc"), "sms:41", TransportOutcome.Sent, reportedAt))
        assertNull(outboundAckRequestFor(DeviceCredential("https://brain.example/path", "device-1", "secret-abc"), "sms:41", TransportOutcome.Sent, reportedAt))
        assertNull(outboundAckRequestFor(DeviceCredential("https://brain.example", "", "secret-abc"), "sms:41", TransportOutcome.Sent, reportedAt))
        assertNull(outboundAckRequestFor(DeviceCredential("https://brain.example", "device-1", ""), "sms:41", TransportOutcome.Sent, reportedAt))
        assertNull(outboundAckRequestFor(DeviceCredential("https://brain.example", "device-1", " secret-abc "), "sms:41", TransportOutcome.Sent, reportedAt))
    }

    @Test
    fun `an identity that is blank or carries whitespace builds no report`() {
        assertNull(outboundAckRequestFor(credential, "", TransportOutcome.Sent, reportedAt))
        assertNull(outboundAckRequestFor(credential, "  ", TransportOutcome.Sent, reportedAt))
        assertNull(outboundAckRequestFor(credential, "sms: 41", TransportOutcome.Sent, reportedAt))
        assertNull(outboundAckRequestFor(credential, " sms:41", TransportOutcome.Sent, reportedAt))
    }

    @Test
    fun `a negative report time builds no report and zero is a timestamp`() {
        assertNull(outboundAckRequestFor(credential, "sms:41", TransportOutcome.Sent, -1L))
        assertTrue(outboundAckRequestFor(credential, "sms:41", TransportOutcome.Sent, 0L) != null)
    }

    @Test
    fun `no content is the brain recording the report`() {
        assertEquals(OutboundAckOutcome.Recorded, outboundAckOutcomeFor(204))
    }

    @Test
    fun `no answer at all is unreachable and the report stays unlanded`() {
        assertEquals(
            OutboundAckOutcome.Failed(OutboundAckFailure.NETWORK_UNREACHABLE),
            outboundAckOutcomeFor(null)
        )
    }

    @Test
    fun `a credential refusal is credential rejected not a transient failure`() {
        assertEquals(OutboundAckOutcome.Failed(OutboundAckFailure.CREDENTIAL_REJECTED), outboundAckOutcomeFor(401))
        assertEquals(OutboundAckOutcome.Failed(OutboundAckFailure.CREDENTIAL_REJECTED), outboundAckOutcomeFor(403))
    }

    @Test
    fun `other client errors are the endpoint refusing the report`() {
        for (status in listOf(400, 404, 422)) {
            assertEquals(OutboundAckOutcome.Failed(OutboundAckFailure.ENDPOINT_REJECTED), outboundAckOutcomeFor(status))
        }
    }

    @Test
    fun `an unexpected status is an answer that is not a recording`() {
        val malformed = OutboundAckOutcome.Failed(OutboundAckFailure.MALFORMED_RESPONSE)
        assertEquals(malformed, outboundAckOutcomeFor(200))
        assertEquals(malformed, outboundAckOutcomeFor(201))
        assertEquals(malformed, outboundAckOutcomeFor(302))
        assertEquals(malformed, outboundAckOutcomeFor(500))
    }

    @Test
    fun `the phone verdict moves a queued reply to its terminal state`() {
        assertEquals(
            OutboundAckState.SENT,
            outboundAckStateAfterSend(OutboundAckState.QUEUED, TransportOutcome.Sent)
        )
        assertEquals(
            OutboundAckState.FAILED,
            outboundAckStateAfterSend(OutboundAckState.QUEUED, TransportOutcome.Failed(TransportOutcome.ERROR_SENDING_MESSAGE))
        )
    }

    @Test
    fun `a terminal state ignores a late or duplicate verdict`() {
        assertEquals(
            OutboundAckState.SENT,
            outboundAckStateAfterSend(OutboundAckState.SENT, TransportOutcome.Failed(TransportOutcome.ERROR_SENDING_MESSAGE))
        )
        assertEquals(
            OutboundAckState.FAILED,
            outboundAckStateAfterSend(OutboundAckState.FAILED, TransportOutcome.Sent)
        )
    }
}
