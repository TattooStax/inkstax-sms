package org.fossify.messages.transport

import android.content.Context
import inkstax.sms.transport.InboundSms
import inkstax.sms.transport.heldInboundMessageFor
import inkstax.sms.transport.heldQueueAfterArrival

/**
 * Serializes every arrival's hold-and-drain in this process: two
 * broadcasts landing together fold against the same stored queue,
 * and without one order between them the later store overwrites
 * the earlier hold and two drains can deliver the same head
 * before either persists its release.
 */
private val heldArrivalLock = Any()

/**
 * The arrival caller for the held inbound queue: an arriving
 * [message] is held through the stored queue and the queue is
 * then drained, so delivery to the brain is attempted on arrival
 * instead of waiting for a later trigger.
 *
 * Only an arrival that may be held at all is attempted — the
 * shared holding rule decides, so an MMS arrival stays not
 * forwarded and never held for delivery, a blank-sender arrival
 * the caller still owns stays unheld, and an arrival with no
 * source id has no identity a delivery could be acked against.
 * Only a paired phone attempts the hold: with no usable
 * credential there is no endpoint to deliver to, and an unpaired
 * or local-mode phone behaves exactly as it does without this
 * caller — nothing is held, nothing is delivered, and the local
 * binding's classification stands on its own.
 *
 * The result is the drain's result, or null when no drain was
 * attempted for either reason above. A failed hold store does
 * not stop the drain: the drain reads the queue back through
 * storage, a failed synchronous commit can still leave the
 * folded queue in the in-memory preferences, and the drain's
 * own release stores are verified, with a failed release
 * restoring the pre-release queue before it is reported.
 *
 * This call blocks on the network, once per held message
 * through the drain, so it belongs on a background thread — the
 * receivers call it from the background work their broadcasts
 * already run on. If the platform reclaims the process
 * mid-drain, the held entries the drain has not released stay
 * stored for the next drain.
 */
fun Context.holdAndDrainInboundArrival(message: InboundSms): HeldQueueDrainResult? {
    if (heldInboundMessageFor(message) == null) {
        return null
    }
    return synchronized(heldArrivalLock) {
        if (storedDeviceCredential() == null) {
            null
        } else {
            storeHeldInboundQueue(heldQueueAfterArrival(storedHeldInboundQueue(), message))
            drainHeldInboundQueue(System.currentTimeMillis())
        }
    }
}
