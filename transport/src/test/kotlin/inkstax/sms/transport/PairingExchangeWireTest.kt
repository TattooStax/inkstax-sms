package inkstax.sms.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingExchangeWireTest {

    private val offer = PairingOffer("https://brain.example", "tok123")

    private fun successBody(
        endpoint: String = "https://brain.example",
        deviceId: String = "device-1",
        secret: String = "secret-abc"
    ): String {
        return """{"endpoint_base":"$endpoint","device_id":"$deviceId","secret":"$secret"}"""
    }

    @Test
    fun `the request posts only the token to the exchange path on the scanned endpoint`() {
        val request = pairingExchangeRequestFor(offer)
        assertEquals("https://brain.example/companion/exchange", request.url)
        assertEquals("""{"token":"tok123"}""", request.body)
    }

    @Test
    fun `the request body escapes the token exactly`() {
        val tricky = PairingOffer("https://brain.example", "to\"k\\en\n")
        assertEquals("""{"token":"to\"k\\en\n"}""", pairingExchangeRequestFor(tricky).body)
        val control = PairingOffer("https://brain.example", "a\u0001b")
        assertEquals("""{"token":"a\u0001b"}""", pairingExchangeRequestFor(control).body)
    }

    @Test
    fun `a success answer for the scanned endpoint is the credential`() {
        val outcome = pairingExchangeOutcomeFor(offer, 200, successBody())
        assertEquals(
            PairingExchangeOutcome.Succeeded(DeviceCredential("https://brain.example", "device-1", "secret-abc")),
            outcome
        )
    }

    @Test
    fun `a success answer may carry extra string fields and escaped values`() {
        val body = "{ \"endpoint_base\" : \"https://brain.example\", \"device_id\" : \"device-1\"," +
            " \"secret\" : \"sec\\\"ret-\\u0041bc\", \"instance\" : \"shop\" }"
        val outcome = pairingExchangeOutcomeFor(offer, 200, body)
        assertEquals(
            PairingExchangeOutcome.Succeeded(DeviceCredential("https://brain.example", "device-1", "sec\"ret-Abc")),
            outcome
        )
    }

    @Test
    fun `a success answer that mints no credential for this offer is malformed`() {
        val malformed = PairingExchangeOutcome.Failed(PairingExchangeFailure.MALFORMED_RESPONSE)
        assertEquals(malformed, pairingExchangeOutcomeFor(offer, 200, successBody(endpoint = "https://other.example")))
        assertEquals(malformed, pairingExchangeOutcomeFor(offer, 200, successBody(secret = "tok123")))
        assertEquals(malformed, pairingExchangeOutcomeFor(offer, 200, successBody(secret = "")))
        assertEquals(malformed, pairingExchangeOutcomeFor(offer, 200, successBody(deviceId = " device-1 ")))
    }

    @Test
    fun `a success answer that is not the flat string object shape is malformed`() {
        val malformed = PairingExchangeOutcome.Failed(PairingExchangeFailure.MALFORMED_RESPONSE)
        assertEquals(malformed, pairingExchangeOutcomeFor(offer, 200, null))
        assertEquals(malformed, pairingExchangeOutcomeFor(offer, 200, ""))
        assertEquals(malformed, pairingExchangeOutcomeFor(offer, 200, "{}"))
        assertEquals(malformed, pairingExchangeOutcomeFor(offer, 200, """{"endpoint_base":"https://brain.example"}"""))
        assertEquals(
            malformed,
            pairingExchangeOutcomeFor(offer, 200, """{"endpoint_base":"https://brain.example","device_id":7,"secret":"secret-abc"}""")
        )
        assertEquals(
            malformed,
            pairingExchangeOutcomeFor(offer, 200, """{"endpoint_base":{"host":"brain.example"},"device_id":"device-1","secret":"secret-abc"}""")
        )
        assertEquals(
            malformed,
            pairingExchangeOutcomeFor(
                offer, 200,
                """{"endpoint_base":"https://brain.example","endpoint_base":"https://brain.example","device_id":"device-1","secret":"secret-abc"}"""
            )
        )
        assertEquals(malformed, pairingExchangeOutcomeFor(offer, 200, successBody(secret = "sec\\u００４１ret")))
        assertEquals(malformed, pairingExchangeOutcomeFor(offer, 200, successBody() + " trailing"))
        assertEquals(malformed, pairingExchangeOutcomeFor(offer, 200, successBody() + ","))
    }

    @Test
    fun `no answer at all is unreachable`() {
        assertEquals(
            PairingExchangeOutcome.Failed(PairingExchangeFailure.NETWORK_UNREACHABLE),
            pairingExchangeOutcomeFor(offer, null, null)
        )
    }

    @Test
    fun `gone and conflict carry the token fate whatever the body says`() {
        assertEquals(
            PairingExchangeOutcome.Failed(PairingExchangeFailure.TOKEN_EXPIRED),
            pairingExchangeOutcomeFor(offer, 410, successBody())
        )
        assertEquals(
            PairingExchangeOutcome.Failed(PairingExchangeFailure.TOKEN_ALREADY_USED),
            pairingExchangeOutcomeFor(offer, 409, null)
        )
    }

    @Test
    fun `other client errors are the endpoint refusing the exchange`() {
        for (status in listOf(400, 401, 403, 404, 422)) {
            assertEquals(
                PairingExchangeOutcome.Failed(PairingExchangeFailure.ENDPOINT_REJECTED),
                pairingExchangeOutcomeFor(offer, status, """{"error":"no"}""")
            )
        }
    }

    @Test
    fun `an unexpected status is an answer that is not a usable credential`() {
        val malformed = PairingExchangeOutcome.Failed(PairingExchangeFailure.MALFORMED_RESPONSE)
        assertEquals(malformed, pairingExchangeOutcomeFor(offer, 201, successBody()))
        assertEquals(malformed, pairingExchangeOutcomeFor(offer, 302, null))
        assertEquals(malformed, pairingExchangeOutcomeFor(offer, 500, "server error"))
    }

    @Test
    fun `wire failures feed the shared attempt rule`() {
        assertEquals(
            PairingState.EXPIRED,
            pairingStateAfter(
                PairingState.AWAITING_EXCHANGE,
                pairingEventForExchangeFailure(PairingExchangeFailure.TOKEN_EXPIRED)
            )
        )
        val serverError = pairingExchangeOutcomeFor(offer, 500, null) as PairingExchangeOutcome.Failed
        assertEquals(
            PairingState.AWAITING_EXCHANGE,
            pairingStateAfter(PairingState.AWAITING_EXCHANGE, pairingEventForExchangeFailure(serverError.failure))
        )
    }
}
