package org.fossify.messages.transport

/**
 * The one lock for every read-modify-write of the stored held
 * inbound queue: an arrival's hold and a drain's release both
 * read the queue, fold one change onto it, and store the
 * result, and without one order between them the later store
 * overwrites the earlier change — a hold landing while a drain
 * delivers would be erased by that delivery's release, and the
 * message would leave the queue without ever being delivered.
 * It is process-wide so it holds across the receivers, the
 * drain's worker, and any later caller of either side.
 *
 * Only the storage operations run under this lock, never a
 * delivery: a drain's network call happens between its read
 * and its release, outside the lock, and its release folds onto
 * the queue as stored at that moment — so a hold never waits
 * on the network behind a delivery, and a delivery's release
 * never waits on anything but storage.
 */
internal object HeldQueueGuard {
    val lock = Any()
}
