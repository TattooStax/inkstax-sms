package inkstax.sms.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {

    private val t0 = 1_000_000_000L

    @Test
    fun `pairing token lifetime matches the binding law`() {
        assertEquals(10 * 60 * 1000L, PAIRING_TOKEN_TTL_MILLIS)
        assertEquals(6, PAIRING_CODE_LENGTH)
    }

    @Test
    fun `the issued QR shape reads back as an offer`() {
        val offer = pairingOfferFromQrPayload("https://brain.example/companion/start?t=abc123_-XYZ")
        assertEquals(PairingOffer("https://brain.example", "abc123_-XYZ"), offer)
    }

    @Test
    fun `an offer keeps an explicit endpoint port`() {
        val offer = pairingOfferFromQrPayload("https://brain.example:8443/companion/start?t=tok")
        assertEquals("https://brain.example:8443", offer?.endpointBase)
        assertEquals("tok", offer?.token)
    }

    @Test
    fun `an offer keeps an IPv6 endpoint address bracketed`() {
        val offer = pairingOfferFromQrPayload("https://[2001:db8::1]:8443/companion/start?t=tok")
        assertEquals("https://[2001:db8::1]:8443", offer?.endpointBase)
    }

    @Test
    fun `surrounding whitespace on the scanned payload is ignored`() {
        val offer = pairingOfferFromQrPayload("  https://brain.example/companion/start?t=tok \n")
        assertEquals(PairingOffer("https://brain.example", "tok"), offer)
    }

    @Test
    fun `non pairing payloads read back as no offer`() {
        assertNull(pairingOfferFromQrPayload(null))
        assertNull(pairingOfferFromQrPayload(""))
        assertNull(pairingOfferFromQrPayload("not a url"))
        assertNull(pairingOfferFromQrPayload("https://brain.example/some/other/page"))
        assertNull(pairingOfferFromQrPayload("https://brain.example/companion/start"))
    }

    @Test
    fun `an offer must be https with a host and no embedded user info`() {
        assertNull(pairingOfferFromQrPayload("http://brain.example/companion/start?t=tok"))
        assertNull(pairingOfferFromQrPayload("https://user@brain.example/companion/start?t=tok"))
        assertNull(pairingOfferFromQrPayload("https:///companion/start?t=tok"))
    }

    @Test
    fun `an offer carries exactly one non-blank token`() {
        assertNull(pairingOfferFromQrPayload("https://brain.example/companion/start?t="))
        assertNull(pairingOfferFromQrPayload("https://brain.example/companion/start?x=tok"))
        assertNull(pairingOfferFromQrPayload("https://brain.example/companion/start?t=one&t=two"))
        assertNull(pairingOfferFromQrPayload("https://brain.example/companion/start?t=tok&x=1"))
    }

    @Test
    fun `a percent-encoded token is decoded before it is returned`() {
        val offer = pairingOfferFromQrPayload("https://brain.example/companion/start?t=abc%2F123%3D")
        assertEquals("abc/123=", offer?.token)
        assertEquals("abc1", pairingOfferFromQrPayload("https://brain.example/companion/start?t=abc%31")?.token)
    }

    @Test
    fun `an encoded blank or whitespace token is still no offer`() {
        assertNull(pairingOfferFromQrPayload("https://brain.example/companion/start?t=%20"))
        assertNull(pairingOfferFromQrPayload("https://brain.example/companion/start?t=a%20b"))
    }

    @Test
    fun `a malformed percent escape is no offer`() {
        assertNull(pairingOfferFromQrPayload("https://brain.example/companion/start?t=abc%ZZ"))
        assertNull(pairingOfferFromQrPayload("https://brain.example/companion/start?t=abc%"))
        assertNull(pairingOfferFromQrPayload("https://brain.example/companion/start?t=abc%2"))
    }

    @Test
    fun `a percent escape spelled with non-ASCII digits is no offer`() {
        // Arabic-Indic digits, Devanagari digits, and fullwidth hex
        // letters all carry values a broad digit test would accept;
        // none of them is the ASCII hex the brain issued, so an
        // escape spelled with them is malformed, not a token byte.
        assertNull(pairingOfferFromQrPayload("https://brain.example/companion/start?t=abc%\u0661\u0662"))
        assertNull(pairingOfferFromQrPayload("https://brain.example/companion/start?t=abc%\u0967\u0968"))
        assertNull(pairingOfferFromQrPayload("https://brain.example/companion/start?t=abc%\uFF21\uFF22"))
        assertNull(pairingOfferFromQrPayload("https://brain.example/companion/start?t=abc%1\u0662"))
    }

    @Test
    fun `the decoder itself rejects escapes spelled with non-ASCII digits`() {
        // Straight at percentDecoded, with no URI parse in front of
        // it: these spellings decode into bytes under a broad digit
        // test, so this assertion holds the decoder's own
        // strictness, not the parser's.
        assertNull(percentDecoded("abc%\u0661\u0662"))
        assertNull(percentDecoded("abc%\u0967\u0968"))
        assertNull(percentDecoded("abc%\uFF21\uFF22"))
        assertNull(percentDecoded("abc%1\u0662"))
        assertNull(percentDecoded("abc%ZZ"))
        assertNull(percentDecoded("abc%"))
    }

    @Test
    fun `the decoder accepts ASCII escapes and leaves the rest exact`() {
        assertEquals("abc/123=", percentDecoded("abc%2F123%3D"))
        assertEquals("abc1", percentDecoded("abc%31"))
        assertEquals("a+b", percentDecoded("a+b"))
        assertEquals("plain", percentDecoded("plain"))
    }

    @Test
    fun `a pairing URL with a fragment is no offer`() {
        assertNull(pairingOfferFromQrPayload("https://brain.example/companion/start?t=tok#extra"))
        assertNull(pairingOfferFromQrPayload("https://brain.example/companion/start?t=tok#"))
    }

    @Test
    fun `a fresh unused token is usable`() {
        assertEquals(PairingTokenState.USABLE, pairingTokenStateAt(t0, t0, alreadyUsed = false))
        assertEquals(
            PairingTokenState.USABLE,
            pairingTokenStateAt(t0, t0 + PAIRING_TOKEN_TTL_MILLIS - 1, alreadyUsed = false)
        )
    }

    @Test
    fun `a token is expired at exactly its lifetime`() {
        assertEquals(
            PairingTokenState.EXPIRED,
            pairingTokenStateAt(t0, t0 + PAIRING_TOKEN_TTL_MILLIS, alreadyUsed = false)
        )
        assertEquals(
            PairingTokenState.EXPIRED,
            pairingTokenStateAt(t0, t0 + PAIRING_TOKEN_TTL_MILLIS + 1, alreadyUsed = false)
        )
    }

    @Test
    fun `a used token stays already used even past its lifetime`() {
        assertEquals(PairingTokenState.ALREADY_USED, pairingTokenStateAt(t0, t0, alreadyUsed = true))
        assertEquals(
            PairingTokenState.ALREADY_USED,
            pairingTokenStateAt(t0, t0 + PAIRING_TOKEN_TTL_MILLIS + 1, alreadyUsed = true)
        )
    }

    @Test
    fun `a token timestamped in the future counts as just issued`() {
        assertEquals(PairingTokenState.USABLE, pairingTokenStateAt(t0 + 60_000L, t0, alreadyUsed = false))
    }

    @Test
    fun `pairing follows the scanned offer to paired`() {
        val scanned = pairingStateAfter(PairingState.UNPAIRED, PairingEvent.OFFER_SCANNED)
        assertEquals(PairingState.AWAITING_EXCHANGE, scanned)
        assertEquals(PairingState.PAIRED, pairingStateAfter(scanned, PairingEvent.EXCHANGE_SUCCEEDED))
    }

    @Test
    fun `an exchange cannot succeed without a scanned offer`() {
        assertEquals(PairingState.UNPAIRED, pairingStateAfter(PairingState.UNPAIRED, PairingEvent.EXCHANGE_SUCCEEDED))
        assertEquals(PairingState.EXPIRED, pairingStateAfter(PairingState.EXPIRED, PairingEvent.EXCHANGE_SUCCEEDED))
    }

    @Test
    fun `a failed exchange leaves the attempt open and a lapsed token expires it`() {
        assertEquals(
            PairingState.AWAITING_EXCHANGE,
            pairingStateAfter(PairingState.AWAITING_EXCHANGE, PairingEvent.EXCHANGE_FAILED)
        )
        assertEquals(
            PairingState.EXPIRED,
            pairingStateAfter(PairingState.AWAITING_EXCHANGE, PairingEvent.TOKEN_LAPSED)
        )
    }

    @Test
    fun `a fresh scan after expiry starts a new attempt`() {
        assertEquals(PairingState.AWAITING_EXCHANGE, pairingStateAfter(PairingState.EXPIRED, PairingEvent.OFFER_SCANNED))
    }

    @Test
    fun `paired is terminal and a second scan replaces nothing`() {
        assertEquals(PairingState.PAIRED, pairingStateAfter(PairingState.PAIRED, PairingEvent.OFFER_SCANNED))
        assertEquals(PairingState.PAIRED, pairingStateAfter(PairingState.PAIRED, PairingEvent.TOKEN_LAPSED))
    }

    @Test
    fun `the fallback code is exactly six digits`() {
        assertTrue(isPairingCodeFormat("123456"))
        assertTrue(isPairingCodeFormat(" 123456 "))
        assertFalse(isPairingCodeFormat(null))
        assertFalse(isPairingCodeFormat(""))
        assertFalse(isPairingCodeFormat("12345"))
        assertFalse(isPairingCodeFormat("1234567"))
        assertFalse(isPairingCodeFormat("12345a"))
        assertFalse(isPairingCodeFormat("123 456"))
    }
}
