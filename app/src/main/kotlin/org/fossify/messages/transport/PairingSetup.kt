package org.fossify.messages.transport

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import inkstax.sms.transport.PairingExchangeFailure
import inkstax.sms.transport.pairingOfferFromQrPayload
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R

/**
 * The pairing scanner: the in-app half of the onboarding ceremony
 * the :transport definitions describe. Pointing the camera at the
 * brain's QR is the normal pairing flow — no typing, no
 * copy-paste — so this is the one caller of the pairing exchange
 * binding: a scan hands its text to [pairingOfferFromQrPayload],
 * and only a payload that reads as a pairing offer starts an
 * exchange. Any other QR is reported as not a pairing code and
 * nothing is sent anywhere, so scanning a stray code can never
 * pair this phone with an endpoint nobody issued.
 *
 * The scanner itself is the embedded ZXing reader: it is not a
 * Google Mobile Services component, so the local build's GMS-free
 * invariant holds with the scanner in it, and it asks for the
 * camera permission itself as part of the scan.
 *
 * Registration must happen before the hosting activity is
 * started, the same rule every activity-result launcher follows;
 * the host registers the launcher once as a field and launches it
 * from its pairing entry point. The host may pass a paired
 * callback: it runs on the UI thread when an exchange completes
 * the pairing, because the exchange finishes on a background
 * thread after the host has already resumed, so state the host
 * refreshed on resume cannot see the new credential.
 *
 * Two seam rules bind this caller. PAIRED is terminal (see
 * Pairing.kt in :transport): a phone that already holds a stored
 * credential pairs nothing new — re-pairing is a disconnect
 * first, never a second scan silently replacing a working
 * pairing or burning another single-use token. And one attempt
 * runs at a time: the shared guard is process-wide so it survives
 * the hosting activity being recreated mid-exchange, and it
 * covers the paired check, the exchange, and the storage retry
 * together, so two attempts can never interleave their writes
 * into a credential that matches neither. The disconnect caller
 * shares the same guard for its clear, so a disconnect and an
 * exchange can never interleave either.
 */
fun ComponentActivity.registerPairingScanner(
    onPaired: () -> Unit = {}
): ActivityResultLauncher<ScanOptions> {
    return registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        handleScannedPairingPayload(contents, onPaired)
    }
}

/** Launches one pairing scan with the pairing prompt. */
fun Activity.launchPairingScan(launcher: ActivityResultLauncher<ScanOptions>) {
    val options = ScanOptions()
        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        .setPrompt(getString(R.string.pairing_scan_prompt))
        .setBeepEnabled(false)
        .setBarcodeImageEnabled(false)
        .setOrientationLocked(false)
    launcher.launch(options)
}

/**
 * Acts on one scanned payload. A payload that is not a pairing
 * offer pairs nothing. An offer runs its exchange on a background
 * thread because the binding blocks on the network, and the
 * outcome is reported back on the UI thread in the failure
 * vocabulary the :transport definitions own.
 */
private fun Activity.handleScannedPairingPayload(contents: String, onPaired: () -> Unit) {
    if (!PairingAttemptGuard.inFlight.compareAndSet(false, true)) {
        toast(R.string.pairing_in_progress)
        return
    }
    val offer = pairingOfferFromQrPayload(contents)
    if (offer == null) {
        PairingAttemptGuard.inFlight.set(false)
        toast(R.string.pairing_qr_not_recognized)
        return
    }
    if (storedDeviceCredential() != null) {
        PairingAttemptGuard.inFlight.set(false)
        toast(R.string.pairing_already_paired)
        return
    }
    toast(R.string.pairing_in_progress)
    ensureBackgroundThread {
        try {
            var pairedNow = false
            val messageId = when (val result = performPairingExchange(offer)) {
                is PairingExchangeBindingResult.Stored -> {
                    pairedNow = true
                    R.string.pairing_succeeded
                }
                is PairingExchangeBindingResult.StorageFailed -> {
                    // The exchange burned the single-use token to mint
                    // this credential, so the one recovery that does
                    // not need a fresh scan is storing the credential
                    // the result carries, once more, right here.
                    if (storeDeviceCredential(result.credential)) {
                        pairedNow = true
                        R.string.pairing_succeeded
                    } else {
                        R.string.pairing_storage_failed
                    }
                }
                is PairingExchangeBindingResult.Failed -> pairingFailureMessage(result.failure)
            }
            runOnUiThread {
                toast(messageId)
                if (pairedNow) {
                    // A paired phone starts beating at once: the
                    // link this pairing created is the heartbeat
                    // service's whole reason to run.
                    startHeartbeatService()
                    // The host resumed before this background exchange
                    // finished, so any paired-state UI it refreshed on
                    // resume predates the stored credential; the
                    // completion callback is what brings it current.
                    onPaired()
                }
            }
        } finally {
            PairingAttemptGuard.inFlight.set(false)
        }
    }
}

private fun pairingFailureMessage(failure: PairingExchangeFailure): Int {
    return when (failure) {
        PairingExchangeFailure.TOKEN_EXPIRED,
        PairingExchangeFailure.TOKEN_ALREADY_USED -> R.string.pairing_token_lapsed
        PairingExchangeFailure.NETWORK_UNREACHABLE -> R.string.pairing_unreachable
        PairingExchangeFailure.ENDPOINT_REJECTED,
        PairingExchangeFailure.MALFORMED_RESPONSE -> R.string.pairing_failed
    }
}
