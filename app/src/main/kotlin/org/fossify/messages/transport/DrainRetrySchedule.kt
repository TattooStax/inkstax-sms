package org.fossify.messages.transport

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import inkstax.sms.transport.DRAIN_RETRY_INTERVAL_MILLIS
import java.util.concurrent.TimeUnit

private const val HELD_DRAIN_RETRY_WORK_NAME = "held_inbound_drain_retry"
private const val PENDING_REPORT_DRAIN_RETRY_WORK_NAME = "pending_outbound_report_drain_retry"

/**
 * The scheduled retry trigger for a blocked drain: while this
 * phone is paired, periodic lifecycle-managed work retries both
 * drains at the interval the :transport definitions set, so a
 * drain that stopped at its head is attempted again even when
 * no new message arrives and no new verdict is reached.
 *
 * The retry reuses the two drain workers the arrival and verdict
 * callers already enqueue. There is no third drain and no second
 * queue path: a periodic run is the same drain, with the same
 * guards and the same release rules, attempted again. The two
 * queues are scheduled together because they block for the same
 * reasons and recover together; scheduling only one would leave
 * the other waiting for an unrelated event. Periodic work runs
 * under its own unique names, outside the one-time chains the
 * callers append to, so the workers themselves serialize every
 * trigger of a queue under that queue's execution guard (see
 * DrainExecutionGuard) — a retry due while a caller-triggered
 * drain is still running waits for it instead of delivering or
 * reporting the same head a second time.
 *
 * Periodic work is kept, not replaced, when this is called
 * again: pairing, a boot, and every Settings visit all ensure
 * the schedule, and keeping the existing work preserves its
 * next run time instead of pushing the retry a full interval
 * later on every Settings visit. The network constraint is the
 * drains' own precondition, so a retry that falls due while the
 * phone is offline waits for the network instead of burning its
 * attempt against an endpoint it cannot reach.
 *
 * Pairing starts the schedule and disconnect cancels it,
 * together with the heartbeat schedule both mirror: a phone with
 * no credential has no endpoint to drain to, and its held queues
 * read back only under the pairing that held them, so retrying
 * after a disconnect could only ever read empty queues. A drain
 * of an empty queue is a defined no-op, so a paired phone with
 * nothing held pays one empty read per queue per interval.
 */
fun Context.ensureDrainRetrySchedule() {
    if (storedDeviceCredential() == null) {
        return
    }
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
    val heldRequest = PeriodicWorkRequestBuilder<HeldDrainWorker>(
        DRAIN_RETRY_INTERVAL_MILLIS, TimeUnit.MILLISECONDS
    ).setConstraints(constraints).build()
    val pendingRequest = PeriodicWorkRequestBuilder<PendingReportDrainWorker>(
        DRAIN_RETRY_INTERVAL_MILLIS, TimeUnit.MILLISECONDS
    ).setConstraints(constraints).build()
    val workManager = WorkManager.getInstance(this)
    workManager.enqueueUniquePeriodicWork(
        HELD_DRAIN_RETRY_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, heldRequest
    )
    workManager.enqueueUniquePeriodicWork(
        PENDING_REPORT_DRAIN_RETRY_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, pendingRequest
    )
}

/** Cancels any scheduled drain retry, as disconnect requires. */
fun Context.cancelDrainRetrySchedule() {
    val workManager = WorkManager.getInstance(this)
    workManager.cancelUniqueWork(HELD_DRAIN_RETRY_WORK_NAME)
    workManager.cancelUniqueWork(PENDING_REPORT_DRAIN_RETRY_WORK_NAME)
}
