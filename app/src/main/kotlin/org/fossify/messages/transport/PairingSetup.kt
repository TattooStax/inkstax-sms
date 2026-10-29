package org.fossify.messages.transport

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import inkstax.sms.transport.PairingExchangeFailure
import inkstax.sms.transport.pairingOfferFromQrPayload
import java.util.concurrent.atomic.AtomicBoolean
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
 * from its pairing entry point.
 *
 * Two seam rules bind this caller. PAIRED is terminal (see
 * Pairing.kt in :transport): a phone that already holds a stored
 * credential pairs nothing new — re-pairing is a disconnect
 * first, never a second scan silently replacing a working
 * pairing or burning another single-use token. And one attempt
 * runs at a time: the guard below is process-wide so it survives
 * the hosting activity being recreated mid-exchange, and it
 * covers the paired check, the exchange, and the storage retry
 * together, so two attempts can never interleave their writes
 * into a credential that matches neither.
 */
private object PairingAttemptGuard {
    val inFlight = AtomicBoolean(false)
}
fun ComponentActivity.registerPairingScanner(): ActivityResultLauncher<ScanOptions> {
    return registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        handleScannedPairingPayload(contents)
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
private fun Activity.handleScannedPairingPayload(contents: String) {
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
            val messageId = when (val result = performPairingExchange(offer)) {
                is PairingExchangeBindingResult.Stored -> R.string.pairing_succeeded
                is PairingExchangeBindingResult.StorageFailed -> {
                    // The exchange burned the single-use token to mint
                    // this credential, so the one recovery that does
                    // not need a fresh scan is storing the credential
                    // the result carries, once more, right here.
                    if (storeDeviceCredential(result.credential)) {
                        R.string.pairing_succeeded
                    } else {
                        R.string.pairing_storage_failed
                    }
                }
                is PairingExchangeBindingResult.Failed -> pairingFailureMessage(result.failure)
            }
            runOnUiThread { toast(messageId) }
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
