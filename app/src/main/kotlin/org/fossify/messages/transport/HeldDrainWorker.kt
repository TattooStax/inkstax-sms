package org.fossify.messages.transport

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * One drain of the held inbound queue, run as lifecycle-managed
 * work: the platform owns this work's lifetime, so a drain
 * outlives the broadcast that enqueued it, and work the process
 * dies before running is run afterwards instead of being lost
 * with the process.
 *
 * A drain that stops at its head is a delivery outcome, not a
 * failure of the work: every outcome the drain can report
 * leaves the unacked entries held in order, and retrying from
 * here cannot fix the outcomes that block a drain — a rejected
 * credential needs re-pairing, and an unreachable endpoint is
 * retried by the next arrival's enqueue (and by the network
 * constraint on any work still pending). The work therefore
 * reports success once its one drain attempt has run, whatever
 * the drain's own result was.
 */
class HeldDrainWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        applicationContext.drainHeldInboundQueue(System.currentTimeMillis())
        return Result.success()
    }
}
