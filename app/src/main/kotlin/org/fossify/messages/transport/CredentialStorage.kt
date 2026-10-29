package org.fossify.messages.transport

import android.content.Context
import inkstax.sms.transport.DeviceCredential
import inkstax.sms.transport.deviceCredentialFromStored
import java.io.File
import org.fossify.commons.extensions.getSharedPrefs

private const val CREDENTIAL_TOKEN_FILE = "device_credential_token"
private const val PAIRED_ENDPOINT_BASE = "paired_endpoint_base"
private const val PAIRED_DEVICE_ID = "paired_device_id"
private const val PAIRED_DEVICE_SECRET = "paired_device_secret"

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
 *
 * The fields live in the app's shared preferences (the same store
 * Config uses), written from here rather than through Config
 * properties because a credential must be written as one checked,
 * synchronous batch — three separately applied writes could leave
 * a mixed record behind, and an unchecked asynchronous write
 * cannot be reported honestly.
 */
fun Context.storedDeviceCredential(): DeviceCredential? {
    val tokenDeviceId = runCatching {
        File(noBackupFilesDir, CREDENTIAL_TOKEN_FILE).readText()
    }.getOrNull()
    return deviceCredentialFromStored(
        storedEndpointBase = getSharedPrefs().getString(PAIRED_ENDPOINT_BASE, null),
        storedDeviceId = getSharedPrefs().getString(PAIRED_DEVICE_ID, null),
        storedSecret = getSharedPrefs().getString(PAIRED_DEVICE_SECRET, null),
        onDeviceTokenDeviceId = tokenDeviceId
    )
}

/**
 * Records a credential the pairing exchange minted, together with
 * its on-device token, and returns whether the credential is
 * durably stored and reads back as this device's afterwards. Only
 * values [deviceCredentialFromStored] would accept on read-back
 * may be stored, so a malformed exchange result can never be
 * persisted and then trusted; callers pass the credential
 * [inkstax.sms.transport.exchangedCredentialFor] returned.
 *
 * The fields are written first, in one synchronous commit: if
 * that commit fails, nothing is reported stored and no token is
 * written. The token file is written last because it is the
 * commit marker — until it names the new device id, the stored
 * fields read back as no credential, never as a working key. A
 * failed token write likewise leaves the phone unpaired rather
 * than half-paired, and is reported as failure: the exchange
 * burned a single-use token to mint this credential, so a caller
 * told the truth can still hold the returned value and retry
 * persistence instead of losing it. The final read-back decides
 * the reported result — stored means verified, not attempted.
 */
fun Context.storeDeviceCredential(credential: DeviceCredential): Boolean {
    val accepted = deviceCredentialFromStored(
        storedEndpointBase = credential.endpointBase,
        storedDeviceId = credential.deviceId,
        storedSecret = credential.secret,
        onDeviceTokenDeviceId = credential.deviceId
    ) ?: return false
    val committed = getSharedPrefs().edit()
        .putString(PAIRED_ENDPOINT_BASE, accepted.endpointBase)
        .putString(PAIRED_DEVICE_ID, accepted.deviceId)
        .putString(PAIRED_DEVICE_SECRET, accepted.secret)
        .commit()
    if (!committed) {
        return false
    }
    val tokenWritten = runCatching {
        File(noBackupFilesDir, CREDENTIAL_TOKEN_FILE).writeText(accepted.deviceId)
        true
    }.getOrDefault(false)
    if (!tokenWritten) {
        return false
    }
    return storedDeviceCredential() == accepted
}

/**
 * Discards the stored credential and its token, and returns
 * whether the phone reads back as unpaired afterwards. Disconnect
 * is the only way out of the paired state, and a disconnected
 * phone keeps no working key (see :transport's
 * pairingStateAfterDisconnect) — the secret itself is discarded
 * too, not merely made unusable, because a key that lingers in
 * storage after its owner disconnected can be revived by a later
 * token write for the same device id.
 *
 * The token is removed first, so from that moment the stored
 * fields — whatever of them remains — no longer count as a
 * credential on this device. The fields are then removed in one
 * synchronous commit for the same reason storing uses one: an
 * unchecked asynchronous removal cannot be reported honestly.
 * Success means verified, not attempted: the token file is gone,
 * the removal committed, and the read-back finds no credential.
 * A caller told the clear failed knows the phone may still be
 * paired and can offer a retry instead of announcing a disconnect
 * that did not happen.
 */
fun Context.clearDeviceCredential(): Boolean {
    val tokenFile = File(noBackupFilesDir, CREDENTIAL_TOKEN_FILE)
    runCatching {
        tokenFile.delete()
    }
    val tokenGone = !tokenFile.exists()
    val committed = getSharedPrefs().edit()
        .remove(PAIRED_ENDPOINT_BASE)
        .remove(PAIRED_DEVICE_ID)
        .remove(PAIRED_DEVICE_SECRET)
        .commit()
    return tokenGone && committed && storedDeviceCredential() == null
}
