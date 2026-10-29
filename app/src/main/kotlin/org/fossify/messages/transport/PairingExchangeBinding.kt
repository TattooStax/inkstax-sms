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
 * thread. A transport fault while asking or answering reads as no
 * answer at all, which the outcome reader names unreachable — the
 * attempt stays open and the same offer can be retried inside the
 * token's lifetime.
 */
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
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
        statusCode = connection.responseCode
        val stream = if (statusCode == HttpURLConnection.HTTP_OK) connection.inputStream else connection.errorStream
        responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
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
