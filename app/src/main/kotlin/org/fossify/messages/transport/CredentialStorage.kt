package org.fossify.messages.transport

import android.content.Context
import inkstax.sms.transport.DeviceCredential
import inkstax.sms.transport.deviceCredentialFromStored
import java.io.File
import org.fossify.messages.extensions.config

private const val CREDENTIAL_TOKEN_FILE = "device_credential_token"

/**
 * This device's paired credential, read back through the :transport
 * rule: the stored fields count only with the on-device token naming
 * the same device id. The token lives in no-backup storage because
 * app backup can restore the preference fields onto another phone,
 * and a device credential is one phone's identity — a restored
 * credential must re-pair, never move (see
 * [deviceCredentialFromStored] in :transport).
 *
 * The token is read exactly, never trimmed: credential values are
 * exact, and a token that does not name the stored device id as-is
 * is no token for it.
 */
fun Context.storedDeviceCredential(): DeviceCredential? {
    val tokenDeviceId = runCatching {
        File(noBackupFilesDir, CREDENTIAL_TOKEN_FILE).readText()
    }.getOrNull()
    return deviceCredentialFromStored(
        storedEndpointBase = config.pairedEndpointBase,
        storedDeviceId = config.pairedDeviceId,
        storedSecret = config.pairedDeviceSecret,
        onDeviceTokenDeviceId = tokenDeviceId
    )
}

/**
 * Records a credential the pairing exchange minted, together with
 * its on-device token, and returns whether the credential reads
 * back as this device's afterwards. Only values
 * [deviceCredentialFromStored] would accept on read-back may be
 * stored, so a malformed exchange result can never be persisted
 * and then trusted; callers pass the credential
 * [inkstax.sms.transport.exchangedCredentialFor] returned.
 *
 * The token file is written last because it is the commit marker:
 * until it names the new device id, the stored fields — however
 * mixed an interrupted write leaves them — read back as no
 * credential, never as a working key assembled from two pairings.
 * A failed token write therefore leaves the phone unpaired rather
 * than half-paired, and the final read-back is what decides the
 * result this function reports: stored means verified, not
 * attempted.
 */
fun Context.storeDeviceCredential(credential: DeviceCredential): Boolean {
    val accepted = deviceCredentialFromStored(
        storedEndpointBase = credential.endpointBase,
        storedDeviceId = credential.deviceId,
        storedSecret = credential.secret,
        onDeviceTokenDeviceId = credential.deviceId
    ) ?: return false
    config.pairedEndpointBase = accepted.endpointBase
    config.pairedDeviceSecret = accepted.secret
    config.pairedDeviceId = accepted.deviceId
    runCatching {
        File(noBackupFilesDir, CREDENTIAL_TOKEN_FILE).writeText(accepted.deviceId)
    }
    return storedDeviceCredential() == accepted
}

/**
 * Discards the stored credential and its token. Disconnect is the
 * only way out of the paired state, and a disconnected phone keeps
 * no working key (see :transport's pairingStateAfterDisconnect).
 */
fun Context.clearDeviceCredential() {
    config.pairedEndpointBase = null
    config.pairedDeviceId = null
    config.pairedDeviceSecret = null
    runCatching {
        File(noBackupFilesDir, CREDENTIAL_TOKEN_FILE).delete()
    }
}
