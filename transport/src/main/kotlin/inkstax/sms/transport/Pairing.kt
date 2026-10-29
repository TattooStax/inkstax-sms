package inkstax.sms.transport

import java.net.URI
import java.net.URISyntaxException

/**
 * Onboarding pairing definitions for the bridge seam.
 *
 * Pairing must be finishable by someone who can send a text: point
 * the phone camera at one QR code to reach the download, then scan
 * the same QR inside the app to pair — no typing, no copy-paste,
 * no codes in the normal flow. These pure definitions are the one
 * vocabulary every binding uses for that ceremony, so the payload
 * a QR carries, the life of the token inside it, and the states a
 * pairing attempt moves through cannot drift between bindings.
 * There is no scanner, no network exchange, and no credential
 * storage here — a binding supplies the scanned text, timestamps,
 * and exchange outcomes, and acts on the states.
 *
 * The QR encodes an HTTPS URL on the brain's own endpoint:
 * `https://<endpoint>/companion/start?t=<token>`. The same QR is
 * presented twice (camera first, in-app scanner second) because
 * deep-link parameters do not survive an install — the user
 * re-presenting the code is what carries the token across it. The
 * token is single-use and short-lived: it registers ONE companion
 * for ONE endpoint, is exchanged once for the device credential
 * the binding stores, and is burned by that exchange. Anything
 * that is not exactly that shape is not a pairing offer, and a
 * lapsed or spent token can never pair — the recovery is a fresh
 * QR, never a longer-lived token.
 *
 * The only typed fallback is a 6-digit code entered on the brain
 * device. It exists for support cases (a camera that cannot scan),
 * carries the same single-use, short-lived token rules, and is
 * never part of the normal flow.
 */

/** How long a pairing token stays usable after the brain issues it. */
const val PAIRING_TOKEN_TTL_MILLIS: Long = 10 * 60 * 1000L

/** Path the pairing URL carries on the brain's endpoint. */
const val PAIRING_PATH: String = "/companion/start"

/** Query parameter that carries the pairing token in the pairing URL. */
const val PAIRING_TOKEN_PARAM: String = "t"

/** Length of the typed fallback pairing code. */
const val PAIRING_CODE_LENGTH: Int = 6

/**
 * A pairing offer read from a QR code: the brain endpoint the
 * companion will talk to, and the single-use token it exchanges.
 * The token is a credential in transit — it is never logged,
 * never persisted, and never shown back to the user.
 */
data class PairingOffer(
    val endpointBase: String,
    val token: String
)

/**
 * Reads a scanned QR payload as a [PairingOffer], or returns null
 * when it is not one. Only the exact issued shape counts: an HTTPS
 * URL with a host, the pairing path, no embedded user info, no
 * fragment, and a single token parameter. The token value is
 * percent-decoded before it is validated or returned — the offer
 * carries the token itself, never its encoded spelling, and an
 * encoded blank is still blank. Anything else — a plain web link,
 * an http URL, a token split across repeated parameters — reads
 * back as no offer, so a stray QR can never start a pairing
 * against an endpoint nobody issued.
 */
fun pairingOfferFromQrPayload(rawPayload: String?): PairingOffer? {
    val payload = rawPayload?.trim().orEmpty()
    if (payload.isEmpty()) {
        return null
    }
    val uri = try {
        URI(payload)
    } catch (e: URISyntaxException) {
        return null
    }
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
        return null
    }
    if (uri.userInfo != null || uri.rawFragment != null || uri.path != PAIRING_PATH) {
        return null
    }
    val queryPairs = uri.rawQuery?.split('&')?.filter { it.isNotEmpty() }.orEmpty()
    if (queryPairs.size != 1) {
        return null
    }
    val pair = queryPairs.single().split('=', limit = 2)
    if (pair.size != 2 || pair[0] != PAIRING_TOKEN_PARAM) {
        return null
    }
    val token = percentDecoded(pair[1]) ?: return null
    if (token.isBlank() || token.any { it.isWhitespace() }) {
        return null
    }
    val hostPart = if (uri.host.contains(':') && !uri.host.startsWith('[')) "[${uri.host}]" else uri.host
    val portPart = if (uri.port >= 0) ":${uri.port}" else ""
    return PairingOffer(endpointBase = "https://$hostPart$portPart", token = token)
}

/**
 * Percent-decodes one query value, or returns null on a malformed
 * escape. Only `%` escapes are decoded; a literal `+` stays a plus,
 * because the token is a credential, not form text, and rewriting
 * it would hand the exchange a different token than the QR carried.
 */
private fun percentDecoded(value: String): String? {
    if ('%' !in value) {
        return value
    }
    val bytes = ArrayList<Byte>(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '%') {
            if (index + 2 >= value.length) {
                return null
            }
            val high = Character.digit(value[index + 1], 16)
            val low = Character.digit(value[index + 2], 16)
            if (high < 0 || low < 0) {
                return null
            }
            bytes.add((high * 16 + low).toByte())
            index += 3
        } else {
            char.toString().toByteArray(Charsets.UTF_8).forEach { bytes.add(it) }
            index++
        }
    }
    return String(bytes.toByteArray(), Charsets.UTF_8)
}

enum class PairingTokenState {
    /** Issued, unused, and still inside its lifetime. */
    USABLE,

    /** Its lifetime ran out before it was exchanged. */
    EXPIRED,

    /** Already exchanged once; a single-use token never works twice. */
    ALREADY_USED
}

/**
 * The state of a pairing token at [nowMillis]. A token that was
 * already exchanged is [PairingTokenState.ALREADY_USED] even if it
 * would also be expired by now — spent is the truer fact. A token
 * timestamped in the future (clock skew between brain and phone)
 * counts as just issued: skew must never expire a fresh code. At
 * exactly [PAIRING_TOKEN_TTL_MILLIS] after issue the token is
 * expired: ten minutes of life means it no longer works at the
 * ten-minute mark.
 */
fun pairingTokenStateAt(issuedAtMillis: Long, nowMillis: Long, alreadyUsed: Boolean): PairingTokenState {
    if (alreadyUsed) {
        return PairingTokenState.ALREADY_USED
    }
    val ageMillis = maxOf(0L, nowMillis - issuedAtMillis)
    return if (ageMillis < PAIRING_TOKEN_TTL_MILLIS) {
        PairingTokenState.USABLE
    } else {
        PairingTokenState.EXPIRED
    }
}

enum class PairingState {
    /** No pairing has been attempted on this device. */
    UNPAIRED,

    /** An offer was scanned and its token is awaiting exchange. */
    AWAITING_EXCHANGE,

    /** The token was exchanged; this device is paired. */
    PAIRED,

    /** The scanned token lapsed before its exchange completed. */
    EXPIRED
}

enum class PairingEvent {
    OFFER_SCANNED,
    EXCHANGE_SUCCEEDED,
    EXCHANGE_FAILED,
    TOKEN_LAPSED
}

/**
 * The next pairing state after an event. Pairing follows the
 * scanned offer only: an exchange can succeed only from
 * [PairingState.AWAITING_EXCHANGE], a failed exchange attempt
 * leaves the attempt open so it can retry inside the token's
 * lifetime, and [PairingState.PAIRED] is terminal — re-pairing is
 * a disconnect first, never a second scan silently replacing the
 * credential. From [PairingState.EXPIRED] a fresh scan starts a
 * new attempt, because a re-issued QR is the designed recovery.
 */
fun pairingStateAfter(current: PairingState, event: PairingEvent): PairingState {
    return when (current) {
        PairingState.UNPAIRED -> when (event) {
            PairingEvent.OFFER_SCANNED -> PairingState.AWAITING_EXCHANGE
            else -> PairingState.UNPAIRED
        }
        PairingState.AWAITING_EXCHANGE -> when (event) {
            PairingEvent.EXCHANGE_SUCCEEDED -> PairingState.PAIRED
            PairingEvent.TOKEN_LAPSED -> PairingState.EXPIRED
            else -> PairingState.AWAITING_EXCHANGE
        }
        PairingState.EXPIRED -> when (event) {
            PairingEvent.OFFER_SCANNED -> PairingState.AWAITING_EXCHANGE
            else -> PairingState.EXPIRED
        }
        PairingState.PAIRED -> PairingState.PAIRED
    }
}

/**
 * Whether a typed fallback code has the pairing code's shape:
 * exactly [PAIRING_CODE_LENGTH] digits once surrounding whitespace
 * from typing is trimmed. The format check is all that happens
 * here — a well-shaped code still has to be exchanged, and the
 * token rules above decide whether it is accepted.
 */
fun isPairingCodeFormat(candidate: String?): Boolean {
    val code = candidate?.trim().orEmpty()
    return code.length == PAIRING_CODE_LENGTH && code.all { it.isDigit() }
}
