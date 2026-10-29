package inkstax.sms.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingExchangeTest {

    private val offer = PairingOffer("https://brain.example", "tok123")

    @Test
    fun `a well formed exchange return for the scanned endpoint is the credential`() {
        val credential = exchangedCredentialFor(offer, "https://brain.example", "device-1", "secret-abc")
        assertEquals(DeviceCredential("https://brain.example", "device-1", "secret-abc"), credential)
    }

    @Test
    fun `a credential for another endpoint is no credential`() {
        assertNull(exchangedCredentialFor(offer, "https://other.example", "device-1", "secret-abc"))
        assertNull(exchangedCredentialFor(offer, "https://brain.example:8443", "device-1", "secret-abc"))
        assertNull(exchangedCredentialFor(offer, null, "device-1", "secret-abc"))
    }

    @Test
    fun `the pairing token handed back as the secret is no credential`() {
        assertNull(exchangedCredentialFor(offer, "https://brain.example", "device-1", "tok123"))
    }

    @Test
    fun `blank or whitespace bearing exchange values are no credential`() {
        assertNull(exchangedCredentialFor(offer, "https://brain.example", "", "secret-abc"))
        assertNull(exchangedCredentialFor(offer, "https://brain.example", "device-1", ""))
        assertNull(exchangedCredentialFor(offer, "https://brain.example", "device-1", null))
        assertNull(exchangedCredentialFor(offer, "https://brain.example", "device 1", "secret-abc"))
        assertNull(exchangedCredentialFor(offer, "https://brain.example", "device-1", "secret abc"))
    }

    @Test
    fun `a non origin endpoint is no credential`() {
        assertNull(exchangedCredentialFor(offer.copy(endpointBase = "https://brain.example/path"), "https://brain.example/path", "device-1", "secret-abc"))
        assertNull(exchangedCredentialFor(offer, "http://brain.example", "device-1", "secret-abc"))
    }

    @Test
    fun `spent or lapsed token failures end the attempt`() {
        assertEquals(PairingEvent.TOKEN_LAPSED, pairingEventForExchangeFailure(PairingExchangeFailure.TOKEN_EXPIRED))
        assertEquals(PairingEvent.TOKEN_LAPSED, pairingEventForExchangeFailure(PairingExchangeFailure.TOKEN_ALREADY_USED))
        assertEquals(
            PairingState.EXPIRED,
            pairingStateAfter(
                PairingState.AWAITING_EXCHANGE,
                pairingEventForExchangeFailure(PairingExchangeFailure.TOKEN_ALREADY_USED)
            )
        )
    }

    @Test
    fun `transient exchange failures leave the attempt open`() {
        for (failure in listOf(
            PairingExchangeFailure.ENDPOINT_REJECTED,
            PairingExchangeFailure.NETWORK_UNREACHABLE,
            PairingExchangeFailure.MALFORMED_RESPONSE
        )) {
            assertEquals(PairingEvent.EXCHANGE_FAILED, pairingEventForExchangeFailure(failure))
            assertEquals(
                PairingState.AWAITING_EXCHANGE,
                pairingStateAfter(PairingState.AWAITING_EXCHANGE, pairingEventForExchangeFailure(failure))
            )
        }
    }

    @Test
    fun `a stored credential with its on-device token reads back`() {
        val credential = deviceCredentialFromStored("https://brain.example", "device-1", "secret-abc", "device-1")
        assertEquals(DeviceCredential("https://brain.example", "device-1", "secret-abc"), credential)
    }

    @Test
    fun `a stored credential without its matching on-device token is no credential`() {
        assertNull(deviceCredentialFromStored("https://brain.example", "device-1", "secret-abc", null))
        assertNull(deviceCredentialFromStored("https://brain.example", "device-1", "secret-abc", ""))
        assertNull(deviceCredentialFromStored("https://brain.example", "device-1", "secret-abc", "device-2"))
    }

    @Test
    fun `a corrupt stored credential is no credential`() {
        assertNull(deviceCredentialFromStored(null, "device-1", "secret-abc", "device-1"))
        assertNull(deviceCredentialFromStored("http://brain.example", "device-1", "secret-abc", "device-1"))
        assertNull(deviceCredentialFromStored("https://brain.example", "device-1", "", "device-1"))
        assertNull(deviceCredentialFromStored("https://brain.example", "", "secret-abc", ""))
    }

    @Test
    fun `a credential is usable only for the endpoint it was minted for`() {
        val credential = DeviceCredential("https://brain.example", "device-1", "secret-abc")
        assertTrue(isCredentialUsableFor(credential, "https://brain.example"))
        assertFalse(isCredentialUsableFor(credential, "https://other.example"))
        assertFalse(isCredentialUsableFor(credential, null))
        assertFalse(isCredentialUsableFor(null, "https://brain.example"))
        assertFalse(isCredentialUsableFor(credential.copy(secret = ""), "https://brain.example"))
    }

    @Test
    fun `disconnect unpairs a paired phone and changes nothing otherwise`() {
        assertEquals(PairingState.UNPAIRED, pairingStateAfterDisconnect(PairingState.PAIRED))
        assertEquals(PairingState.UNPAIRED, pairingStateAfterDisconnect(PairingState.UNPAIRED))
        assertEquals(PairingState.AWAITING_EXCHANGE, pairingStateAfterDisconnect(PairingState.AWAITING_EXCHANGE))
        assertEquals(PairingState.EXPIRED, pairingStateAfterDisconnect(PairingState.EXPIRED))
    }
}
