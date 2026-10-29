package inkstax.sms.transport

/**
 * Pairing exchange wire definitions for the bridge seam.
 *
 * PairingExchange.kt defines what a successful exchange mints and
 * which failures end an attempt; this file defines the one wire
 * shape every binding uses to run that exchange, so the request a
 * binding sends and the reading of the answer it gets back cannot
 * drift between bindings. There is no network call here — a
 * binding performs the HTTPS POST, supplies the status and body
 * it received (or no status when the endpoint could not be
 * reached at all), and acts on the outcome: on success it hands
 * the credential to its credential storage; on failure it maps
 * the failure through [pairingEventForExchangeFailure].
 *
 * The exchange is a POST to the exchange path on the scanned
 * offer's own endpoint, carrying only the pairing token as JSON.
 * The token is the whole request: it is single-use and
 * scope-limited to registering one companion for that endpoint,
 * so the request needs — and is allowed — nothing else. The
 * token is a credential in transit: never logged, never
 * persisted, and escaped exactly when the body is built, because
 * a token rewritten by sloppy escaping is a different token and
 * the exchange would burn or refuse the real one.
 *
 * A successful answer is one flat JSON object of string values:
 * `endpoint_base`, `device_id`, and `secret`, read strictly and
 * then judged by [exchangedCredentialFor] — the wire reader
 * accepts the shape, the exchange definitions accept the values,
 * so a credential for another endpoint or the token handed back
 * as the secret is a malformed response, never a pairing. Extra
 * string fields are ignored: the brain may add instance identity
 * to the same response without breaking companions already in
 * the field. Anything structurally other than that flat object —
 * a nested value, a number, a repeated key — is malformed too.
 *
 * Status is read before the body. Gone and Conflict carry the
 * token's fate (its lifetime ended, or it was already exchanged),
 * so they outrank whatever body accompanies them. Other client
 * errors are the endpoint refusing the exchange. A status that
 * is none of these — a redirect the binding did not follow, a
 * server error page — is an answer that is not a usable
 * credential, which the failure vocabulary already names.
 */

/** Path of the token exchange on the brain's endpoint. */
const val PAIRING_EXCHANGE_PATH: String = "/companion/exchange"

/** JSON field that carries the pairing token in the exchange request. */
const val PAIRING_EXCHANGE_TOKEN_FIELD: String = "token"

/** JSON field that carries the endpoint base in the exchange response. */
const val PAIRING_EXCHANGE_ENDPOINT_FIELD: String = "endpoint_base"

/** JSON field that carries the device id in the exchange response. */
const val PAIRING_EXCHANGE_DEVICE_FIELD: String = "device_id"

/** JSON field that carries the device secret in the exchange response. */
const val PAIRING_EXCHANGE_SECRET_FIELD: String = "secret"

/**
 * One exchange request, ready for a binding to POST: the URL on
 * the scanned offer's endpoint, and the JSON body carrying the
 * offer's token and nothing else.
 */
data class PairingExchangeRequest(
    val url: String,
    val body: String
)

/**
 * The exchange request for a scanned [offer]. The token is
 * escaped exactly as a JSON string value, so the body carries
 * the token itself whatever characters it contains.
 */
fun pairingExchangeRequestFor(offer: PairingOffer): PairingExchangeRequest {
    return PairingExchangeRequest(
        url = offer.endpointBase + PAIRING_EXCHANGE_PATH,
        body = "{\"$PAIRING_EXCHANGE_TOKEN_FIELD\":${jsonString(offer.token)}}"
    )
}

/** The outcome of one exchange attempt, as the seam reads it. */
sealed interface PairingExchangeOutcome {
    /** The exchange minted this credential for the scanned offer. */
    data class Succeeded(val credential: DeviceCredential) : PairingExchangeOutcome

    /** The exchange minted no credential; the failure says why. */
    data class Failed(val failure: PairingExchangeFailure) : PairingExchangeOutcome
}

/**
 * Reads one exchange attempt's result as a [PairingExchangeOutcome].
 * A null [statusCode] means no answer arrived at all — the
 * endpoint could not be reached. On a success status the body
 * must still parse as the flat response shape and pass
 * [exchangedCredentialFor]; a well-formed answer that mints no
 * credential for this offer is a malformed response, so the
 * phone never ends up half-paired on a key that does not work.
 */
fun pairingExchangeOutcomeFor(
    offer: PairingOffer,
    statusCode: Int?,
    responseBody: String?
): PairingExchangeOutcome {
    if (statusCode == null) {
        return PairingExchangeOutcome.Failed(PairingExchangeFailure.NETWORK_UNREACHABLE)
    }
    return when {
        statusCode == HTTP_OK -> {
            val fields = flatJsonStringObject(responseBody)
            val credential = fields?.let {
                exchangedCredentialFor(
                    offer = offer,
                    returnedEndpointBase = it[PAIRING_EXCHANGE_ENDPOINT_FIELD],
                    returnedDeviceId = it[PAIRING_EXCHANGE_DEVICE_FIELD],
                    returnedSecret = it[PAIRING_EXCHANGE_SECRET_FIELD]
                )
            }
            if (credential != null) {
                PairingExchangeOutcome.Succeeded(credential)
            } else {
                PairingExchangeOutcome.Failed(PairingExchangeFailure.MALFORMED_RESPONSE)
            }
        }
        statusCode == HTTP_GONE -> PairingExchangeOutcome.Failed(PairingExchangeFailure.TOKEN_EXPIRED)
        statusCode == HTTP_CONFLICT -> PairingExchangeOutcome.Failed(PairingExchangeFailure.TOKEN_ALREADY_USED)
        statusCode in HTTP_CLIENT_ERROR_RANGE -> PairingExchangeOutcome.Failed(PairingExchangeFailure.ENDPOINT_REJECTED)
        else -> PairingExchangeOutcome.Failed(PairingExchangeFailure.MALFORMED_RESPONSE)
    }
}

private const val HTTP_OK: Int = 200
private const val HTTP_CONFLICT: Int = 409
private const val HTTP_GONE: Int = 410
private val HTTP_CLIENT_ERROR_RANGE: IntRange = 400..499

/**
 * Renders a value as a JSON string literal, escaping exactly the
 * characters JSON requires: the quote, the backslash, and the
 * control characters. Everything else is carried as-is — the
 * value a reader recovers from the literal is the value itself.
 */
private fun jsonString(value: String): String {
    val rendered = StringBuilder(value.length + 2)
    rendered.append('"')
    for (char in value) {
        when (char) {
            '"' -> rendered.append("\\\"")
            '\\' -> rendered.append("\\\\")
            '\b' -> rendered.append("\\b")
            '\u000C' -> rendered.append("\\f")
            '\n' -> rendered.append("\\n")
            '\r' -> rendered.append("\\r")
            '\t' -> rendered.append("\\t")
            else -> if (char.code < 0x20) {
                rendered.append("\\u")
                rendered.append(char.code.toString(16).padStart(4, '0'))
            } else {
                rendered.append(char)
            }
        }
    }
    rendered.append('"')
    return rendered.toString()
}

/**
 * Reads a flat JSON object whose values are all strings, or
 * returns null when the text is anything else. Strict on
 * purpose: keys and values must be JSON strings, a key may
 * appear only once, and nothing may follow the closing brace —
 * a response read loosely here would be trusted with a device
 * credential two lines later.
 */
private fun flatJsonStringObject(raw: String?): Map<String, String>? {
    val reader = FlatJsonReader(raw.orEmpty())
    val fields = reader.readObject() ?: return null
    return if (reader.isAtEnd()) fields else null
}

private class FlatJsonReader(private val text: String) {
    private var index: Int = 0

    fun isAtEnd(): Boolean {
        skipWhitespace()
        return index >= text.length
    }

    fun readObject(): Map<String, String>? {
        skipWhitespace()
        if (!consume('{')) {
            return null
        }
        val fields = LinkedHashMap<String, String>()
        skipWhitespace()
        if (consume('}')) {
            return fields
        }
        while (true) {
            skipWhitespace()
            val key = readString() ?: return null
            skipWhitespace()
            if (!consume(':')) {
                return null
            }
            skipWhitespace()
            val value = readString() ?: return null
            if (fields.put(key, value) != null) {
                return null
            }
            skipWhitespace()
            if (consume('}')) {
                return fields
            }
            if (!consume(',')) {
                return null
            }
        }
    }

    private fun readString(): String? {
        if (!consume('"')) {
            return null
        }
        val value = StringBuilder()
        while (index < text.length) {
            val char = text[index]
            index++
            when (char) {
                '"' -> return value.toString()
                '\\' -> {
                    val escaped = readEscape() ?: return null
                    value.append(escaped)
                }
                else -> {
                    if (char.code < 0x20) {
                        return null
                    }
                    value.append(char)
                }
            }
        }
        return null
    }

    private fun readEscape(): Char? {
        if (index >= text.length) {
            return null
        }
        val escape = text[index]
        index++
        return when (escape) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> readUnicodeEscape()
            else -> null
        }
    }

    private fun readUnicodeEscape(): Char? {
        if (index + 4 > text.length) {
            return null
        }
        var code = 0
        repeat(4) {
            val digit = Character.digit(text[index], 16)
            if (digit < 0) {
                return null
            }
            code = code * 16 + digit
            index++
        }
        return code.toChar()
    }

    private fun consume(expected: Char): Boolean {
        if (index < text.length && text[index] == expected) {
            index++
            return true
        }
        return false
    }

    private fun skipWhitespace() {
        while (index < text.length && text[index] in " \t\n\r") {
            index++
        }
    }
}
