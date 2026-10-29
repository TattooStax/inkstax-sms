package inkstax.sms.transport

/**
 * Pairing exchange and device-credential definitions for the bridge
 * seam.
 *
 * Pairing (see Pairing.kt) ends with one exchange: the companion
 * presents the scanned single-use token once, and the brain returns
 * the device credential this phone will use from then on. These pure
 * definitions are the one vocabulary every binding uses for that
 * moment, so what counts as a credential, when an exchange counts
 * as having succeeded, and when a stored credential counts as this
 * device's cannot drift between bindings. There is no network call,
 * no scanner, and no storage here — a binding performs the exchange,
 * supplies the returned values and any failure, persists what these
 * definitions accept, and acts on the states.
 *
 * The credential is not the token. The token is a short-lived,
 * single-use offer that is burned by the exchange; the credential
 * is the long-lived per-device secret the exchange mints. A
 * returned value that equals the token is therefore never a
 * credential — accepting it would turn a ten-minute offer into a
 * permanent key and defeat the token's single-use rule.
 *
 * A credential belongs to exactly one endpoint and one device. A
 * credential minted for a different endpoint than the offer was
 * scanned from is not this pairing's credential, however well
 * formed it looks, because the phone scanned one brain's QR and
 * must not end up paired to another.
 *
 * A stored credential counts only on the device that exchanged for
 * it. Credential fields live in storage Android backup can restore
 * onto a different phone; the on-device token (kept in storage
 * backup never carries) names the device id, so a restored
 * credential without its matching token reads back as no
 * credential and the phone pairs afresh, instead of a backup
 * silently moving one phone's identity onto another.
 */

/**
 * The per-device credential a successful pairing exchange mints.
 * The secret is a credential — it is never logged, never shown
 * back to the user, and never sent anywhere except the endpoint
 * it was minted for.
 */
data class DeviceCredential(
    val endpointBase: String,
    val deviceId: String,
    val secret: String
)

/**
 * Whether a candidate endpoint base is one a credential may belong
 * to: an HTTPS URL with a host and nothing else — no path, query,
 * fragment, or embedded user info. Endpoint bases in this seam are
 * origins, as Pairing.kt returns them from a scanned offer.
 */
private fun isEndpointBase(candidate: String?): Boolean {
    val value = candidate?.trim().orEmpty()
    if (value.isEmpty() || value.any { it.isWhitespace() }) {
        return false
    }
    val uri = try {
        java.net.URI(value)
    } catch (e: java.net.URISyntaxException) {
        return false
    }
    return uri.scheme.equals("https", ignoreCase = true) &&
        !uri.host.isNullOrBlank() &&
        uri.userInfo == null &&
        uri.rawQuery == null &&
        uri.rawFragment == null &&
        (uri.path.isNullOrEmpty() || uri.path == "/")
}

/** Whether a credential field is a usable opaque value: present and free of whitespace. */
private fun isOpaqueValue(candidate: String?): Boolean {
    val value = candidate?.trim().orEmpty()
    return value.isNotEmpty() && value.none { it.isWhitespace() }
}

/**
 * The credential a pairing exchange returned, or null when the
 * exchange did not mint a usable credential for this offer. Every
 * condition must hold: the returned endpoint is exactly the
 * offer's endpoint, the device id and secret are present opaque
 * values, and the secret is not the pairing token itself. Any
 * other return — a blank secret, a credential for another
 * endpoint, the token handed back as the secret — reads back as
 * no credential, so a malformed or mismatched exchange response
 * can never leave the phone half-paired with a key that does not
 * work or should not be trusted.
 */
fun exchangedCredentialFor(
    offer: PairingOffer,
    returnedEndpointBase: String?,
    returnedDeviceId: String?,
    returnedSecret: String?
): DeviceCredential? {
    val endpoint = returnedEndpointBase?.trim().orEmpty()
    val deviceId = returnedDeviceId?.trim().orEmpty()
    val secret = returnedSecret?.trim().orEmpty()
    if (endpoint != offer.endpointBase || !isEndpointBase(endpoint)) {
        return null
    }
    if (!isOpaqueValue(deviceId) || !isOpaqueValue(secret)) {
        return null
    }
    if (secret == offer.token) {
        return null
    }
    return DeviceCredential(endpointBase = endpoint, deviceId = deviceId, secret = secret)
}

/** Why a pairing exchange did not mint a credential. */
enum class PairingExchangeFailure {
    /** The token's lifetime ran out before the exchange completed. */
    TOKEN_EXPIRED,

    /** The token was already exchanged once; it never works twice. */
    TOKEN_ALREADY_USED,

    /** The endpoint refused the exchange for another reason. */
    ENDPOINT_REJECTED,

    /** The endpoint could not be reached at all. */
    NETWORK_UNREACHABLE,

    /** The endpoint answered, but not with a usable credential. */
    MALFORMED_RESPONSE
}

/**
 * The pairing event an exchange failure maps to. A spent or lapsed
 * token ends the attempt — the recovery is a fresh scan, as
 * Pairing.kt's state table provides — so those failures map to
 * the token lapsing. Every other failure leaves the attempt open
 * inside the token's lifetime, so a phone that briefly loses its
 * network can retry the same offer instead of making the user
 * fetch a new QR for a transient fault.
 */
fun pairingEventForExchangeFailure(failure: PairingExchangeFailure): PairingEvent {
    return when (failure) {
        PairingExchangeFailure.TOKEN_EXPIRED,
        PairingExchangeFailure.TOKEN_ALREADY_USED -> PairingEvent.TOKEN_LAPSED
        PairingExchangeFailure.ENDPOINT_REJECTED,
        PairingExchangeFailure.NETWORK_UNREACHABLE,
        PairingExchangeFailure.MALFORMED_RESPONSE -> PairingEvent.EXCHANGE_FAILED
    }
}

/**
 * Reads a stored credential back as this device's credential, or
 * null when it does not count. The stored fields must form a
 * usable credential (an HTTPS endpoint origin and opaque device
 * id and secret), and the on-device token must name the same
 * device id — a credential restored from backup without its token,
 * or with another device's token, reads back as no credential and
 * the phone pairs afresh. A corrupt stored value likewise reads
 * back as no credential rather than a credential that will fail
 * against the brain on every call.
 */
fun deviceCredentialFromStored(
    storedEndpointBase: String?,
    storedDeviceId: String?,
    storedSecret: String?,
    onDeviceTokenDeviceId: String?
): DeviceCredential? {
    val endpoint = storedEndpointBase?.trim().orEmpty()
    val deviceId = storedDeviceId?.trim().orEmpty()
    val secret = storedSecret?.trim().orEmpty()
    if (!isEndpointBase(endpoint) || !isOpaqueValue(deviceId) || !isOpaqueValue(secret)) {
        return null
    }
    if (onDeviceTokenDeviceId?.trim().orEmpty() != deviceId) {
        return null
    }
    return DeviceCredential(endpointBase = endpoint, deviceId = deviceId, secret = secret)
}

/**
 * Whether a credential may be used for an endpoint: it exists and
 * it was minted for exactly that endpoint. A binding checks this
 * before every use, so a re-pointed or restored configuration can
 * never send one brain's credential to another brain.
 */
fun isCredentialUsableFor(credential: DeviceCredential?, endpointBase: String?): Boolean {
    if (credential == null) {
        return false
    }
    return credential.endpointBase == endpointBase?.trim().orEmpty() &&
        isEndpointBase(credential.endpointBase) &&
        isOpaqueValue(credential.deviceId) &&
        isOpaqueValue(credential.secret)
}

/**
 * The next pairing state when the user disconnects this device.
 * Disconnect is the only way out of the terminal paired state:
 * it returns the phone to unpaired, and the stored credential is
 * discarded with it — a disconnected phone keeps no working key.
 * Disconnecting a phone that is not paired changes nothing.
 */
fun pairingStateAfterDisconnect(current: PairingState): PairingState {
    return if (current == PairingState.PAIRED) {
        PairingState.UNPAIRED
    } else {
        current
    }
}
