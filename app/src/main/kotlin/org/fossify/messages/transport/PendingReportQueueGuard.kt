package org.fossify.messages.transport

/**
 * The one lock for every read-modify-write of the stored
 * pending outbound report queue: a verdict's hold and a drain's
 * release both read the queue, fold one change onto it, and
 * store the result, and without one order between them the
 * later store overwrites the earlier change — a hold landing
 * while a drain reports would be erased by that report's
 * release, and the verdict would leave the queue without ever
 * being recorded. It is process-wide so it holds across the
 * send-result caller, the drain's caller, and any later caller
 * of either side.
 *
 * Only the storage operations run under this lock, never a
 * report: a drain's network call happens between its read and
 * its release, outside the lock, and its release folds onto
 * the queue as stored at that moment — so a hold never waits
 * on the network behind a report, and a report's release never
 * waits on anything but storage.
 */
internal object PendingReportQueueGuard {
    val lock = Any()
}
