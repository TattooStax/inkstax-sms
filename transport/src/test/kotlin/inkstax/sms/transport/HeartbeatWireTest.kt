package inkstax.sms.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatWireTest {

    private val credential = DeviceCredential("https://brain.example", "device-1", "secret-abc")
    private val sentAt = 1_700_000_000_000L

    @Test
    fun `the request posts the device id and send time to the heartbeat path on the credential endpoint`() {
        val request = heartbeatRequestFor(credential, sentAt)
        assertEquals("https://brain.example/companion/heartbeat", request?.url)
        assertEquals("""{"device_id":"device-1","sent_at_millis":"1700000000000"}""", request?.body)
    }

    @Test
    fun `the secret travels only in the authorization value never in the body`() {
        val request = heartbeatRequestFor(credential, sentAt)
        assertEquals("Bearer c2VjcmV0LWFiYw", request?.authorization)
        assertFalse(request!!.body.contains("secret-abc"))
        assertFalse(request.url.contains("secret-abc"))
        assertFalse(request.authorization.contains("secret-abc"))
    }

    @Test
    fun `any minted secret encodes to a valid authorization value that decodes back to itself`() {
        val quoted = DeviceCredential("https://brain.example", "device-1", "sec\"ret-Abc")
        assertEquals("Bearer c2VjInJldC1BYmM", heartbeatRequestFor(quoted, sentAt)?.authorization)
        val control = DeviceCredential("https://brain.example", "device-1", "a\u0001secret")
        val authorization = heartbeatRequestFor(control, sentAt)?.authorization
        assertEquals("Bearer YQFzZWNyZXQ", authorization)
        val decoded = java.util.Base64.getUrlDecoder().decode(authorization!!.removePrefix("Bearer "))
        assertEquals("a\u0001secret", String(decoded, Charsets.UTF_8))
    }

    @Test
    fun `a credential endpoint with a trailing slash still builds the one heartbeat path`() {
        val slashed = DeviceCredential("https://brain.example/", "device-1", "secret-abc")
        assertEquals(
            "https://brain.example/companion/heartbeat",
            heartbeatRequestFor(slashed, sentAt)?.url
        )
    }

    @Test
    fun `the request rendering never shows the secret raw or encoded`() {
        val rendered = heartbeatRequestFor(credential, sentAt).toString()
        assertFalse(rendered.contains("secret-abc"))
        assertFalse(rendered.contains("c2VjcmV0LWFiYw"))
        assertTrue(rendered.contains("<redacted>"))
        assertTrue(rendered.startsWith("HeartbeatRequest(url=https://brain.example/companion/heartbeat"))
        assertTrue(rendered.endsWith("authorization=Bearer <redacted>)"))
    }

    @Test
    fun `the request body escapes the device id exactly`() {
        val tricky = DeviceCredential("https://brain.example", "dev\"ice\\1", "secret-abc")
        val request = heartbeatRequestFor(tricky, sentAt)
        assertEquals("""{"device_id":"dev\"ice\\1","sent_at_millis":"1700000000000"}""", request?.body)
        val control = DeviceCredential("https://brain.example", "a\u0001b", "secret-abc")
        assertEquals(
            """{"device_id":"a\u0001b","sent_at_millis":"1700000000000"}""",
            heartbeatRequestFor(control, sentAt)?.body
        )
    }

    @Test
    fun `a credential the seam does not accept builds no request`() {
        assertNull(heartbeatRequestFor(DeviceCredential("http://brain.example", "device-1", "secret-abc"), sentAt))
        assertNull(heartbeatRequestFor(DeviceCredential("https://brain.example/path", "device-1", "secret-abc"), sentAt))
        assertNull(heartbeatRequestFor(DeviceCredential("https://brain.example", "", "secret-abc"), sentAt))
        assertNull(heartbeatRequestFor(DeviceCredential("https://brain.example", "device-1", ""), sentAt))
        assertNull(heartbeatRequestFor(DeviceCredential("https://brain.example", "device-1", " secret-abc "), sentAt))
        assertNull(heartbeatRequestFor(DeviceCredential("https://brain.example", " device-1 ", "secret-abc"), sentAt))
    }

    @Test
    fun `a negative send time builds no request and zero is a send time`() {
        assertNull(heartbeatRequestFor(credential, -1L))
        assertTrue(heartbeatRequestFor(credential, 0L) != null)
    }

    @Test
    fun `no content is a recorded beat`() {
        assertEquals(HeartbeatOutcome.Recorded, heartbeatOutcomeFor(204))
    }

    @Test
    fun `no answer at all is unreachable`() {
        assertEquals(
            HeartbeatOutcome.Failed(HeartbeatFailure.NETWORK_UNREACHABLE),
            heartbeatOutcomeFor(null)
        )
    }

    @Test
    fun `a credential refusal is credential rejected not a transient failure`() {
        assertEquals(
            HeartbeatOutcome.Failed(HeartbeatFailure.CREDENTIAL_REJECTED),
            heartbeatOutcomeFor(401)
        )
        assertEquals(
            HeartbeatOutcome.Failed(HeartbeatFailure.CREDENTIAL_REJECTED),
            heartbeatOutcomeFor(403)
        )
    }

    @Test
    fun `other client errors are the endpoint refusing the heartbeat`() {
        for (status in listOf(400, 404, 422)) {
            assertEquals(
                HeartbeatOutcome.Failed(HeartbeatFailure.ENDPOINT_REJECTED),
                heartbeatOutcomeFor(status)
            )
        }
    }

    @Test
    fun `an unexpected status is an answer that is not a recorded beat`() {
        val malformed = HeartbeatOutcome.Failed(HeartbeatFailure.MALFORMED_RESPONSE)
        assertEquals(malformed, heartbeatOutcomeFor(200))
        assertEquals(malformed, heartbeatOutcomeFor(201))
        assertEquals(malformed, heartbeatOutcomeFor(302))
        assertEquals(malformed, heartbeatOutcomeFor(500))
    }
}
