package org.fossify.messages.transport

import android.content.Context
import inkstax.sms.transport.DeviceCredential
import inkstax.sms.transport.PairingExchangeFailure
import inkstax.sms.transport.PairingExchangeOutcome
import inkstax.sms.transport.PairingOffer
import inkstax.sms.transport.pairingExchangeOutcomeFor
import inkstax.sms.transport.pairingExchangeRequestFor
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val EXCHANGE_CONNECT_TIMEOUT_MILLIS = 10_000
private const val EXCHANGE_READ_TIMEOUT_MILLIS = 15_000

/**
 * The largest exchange answer this binding will read. A credential
 * answer is a small flat JSON object; a larger answer is not one,
 * however it is labelled — and the endpoint comes from a scanned
 * code, so an unbounded read would let any endpoint exhaust the
 * app's memory before the answer is even judged.
 */
private const val EXCHANGE_MAX_RESPONSE_BYTES = 16_384

/** The result of one pairing exchange run by this binding. */
sealed interface PairingExchangeBindingResult {
    /** The exchange minted a credential and it is stored on this device. */
    data class Stored(val credential: DeviceCredential) : PairingExchangeBindingResult

    /**
     * The exchange minted a credential but storing it failed. The
     * credential travels with the result because the exchange burned
     * the single-use token to mint it: the caller can hold this value
     * and retry storing it, instead of losing the pairing to a
     * storage fault and needing a fresh scan for a token that was
     * spent on a key that does work.
     */
    data class StorageFailed(val credential: DeviceCredential) : PairingExchangeBindingResult

    /** The exchange minted no credential; the failure says why. */
    data class Failed(val failure: PairingExchangeFailure) : PairingExchangeBindingResult
}

/**
 * Runs one pairing exchange for a scanned [offer] and stores what it
 * mints. This is the binding that performs the exchange the
 * :transport definitions describe: the request is the one
 * [pairingExchangeRequestFor] builds, the answer is read the one way
 * [pairingExchangeOutcomeFor] reads it, and a minted credential goes
 * to the app-side credential storage, which verifies it on read-back
 * before it counts as stored.
 *
 * The offer's endpoint is HTTPS (the pairing reader accepts no other
 * scheme), so the token and the minted secret travel only over TLS.
 * Neither value is logged or persisted here — the token is spent by
 * this call, and the secret is persisted only by the credential
 * storage, never by the exchange itself.
 *
 * This call blocks on the network, so it belongs on a background
 * thread. A transport fault while asking reads as no answer at
 * all, which the outcome reader names unreachable — the attempt
 * stays open and the same offer can be retried inside the token's
 * lifetime.
 *
 * Redirects are never followed: the exchange answers on the
 * scanned endpoint or not at all, and a redirect status is read
 * by the outcome reader as an answer that is not a usable
 * credential. The body is read only on a success status, and only
 * up to a bounded size — every other outcome is decided by the
 * status alone, so a spent or expired token is reported as such
 * even when its error body cannot be read, never retried as if
 * the endpoint were merely unreachable.
 */
@Suppress("SwallowedException")
fun Context.performPairingExchange(offer: PairingOffer): PairingExchangeBindingResult {
    val request = pairingExchangeRequestFor(offer)
    var connection: HttpURLConnection? = null
    var statusCode: Int? = null
    var responseBody: String? = null
    try {
        connection = URL(request.url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = EXCHANGE_CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = EXCHANGE_READ_TIMEOUT_MILLIS
        connection.instanceFollowRedirects = false
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
        statusCode = connection.responseCode
        if (statusCode == HttpURLConnection.HTTP_OK) {
            responseBody = runCatching { readExchangeResponseBody(connection) }.getOrNull()
        }
    } catch (e: IOException) {
        statusCode = null
        responseBody = null
    } finally {
        connection?.disconnect()
    }
    return when (val outcome = pairingExchangeOutcomeFor(offer, statusCode, responseBody)) {
        is PairingExchangeOutcome.Failed -> PairingExchangeBindingResult.Failed(outcome.failure)
        is PairingExchangeOutcome.Succeeded -> {
            if (storeDeviceCredential(outcome.credential)) {
                PairingExchangeBindingResult.Stored(outcome.credential)
            } else {
                PairingExchangeBindingResult.StorageFailed(outcome.credential)
            }
        }
    }
}

/**
 * Reads a success answer's body, or returns null when it runs past
 * [EXCHANGE_MAX_RESPONSE_BYTES] — an oversized answer is no
 * credential answer, and the outcome reader names a missing body
 * on a success status malformed. Bytes past the limit are never
 * buffered, so the read stops instead of filling memory first.
 */
private fun readExchangeResponseBody(connection: HttpURLConnection): String? {
    val bytes = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    connection.inputStream.use { stream ->
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) {
                break
            }
            if (bytes.size() + count > EXCHANGE_MAX_RESPONSE_BYTES) {
                return null
            }
            bytes.write(buffer, 0, count)
        }
    }
    return bytes.toString(Charsets.UTF_8.name())
}
