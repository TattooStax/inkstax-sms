package org.fossify.messages.transport

/** The result of one drain of the pending outbound report queue. */
sealed interface PendingReportQueueDrainResult {
    /** Nothing was pending, so nothing was reported. */
    data object Empty : PendingReportQueueDrainResult

    /**
     * Every pending report was reported, the brain recorded
     * each one, and each release was persisted and read back,
     * so the stored queue is empty. [reportedCount] is how many
     * reports this drain landed.
     */
    data class Drained(val reportedCount: Int) : PendingReportQueueDrainResult

    /**
     * The drain stopped at the head of the queue: [stoppedBy]
     * is the outbound ack binding's result for that head, and
     * it is not the brain recording the report, so the head and
     * everything behind it stay pending in order.
     * [reportedCount] reports ahead of it were recorded and
     * their releases persisted in this drain, and
     * [remainingCount] reports remain pending, head first, for
     * a later drain against the same source ids.
     */
    data class Blocked(
        val stoppedBy: OutboundAckBindingResult,
        val reportedCount: Int,
        val remainingCount: Int
    ) : PendingReportQueueDrainResult

    /**
     * The brain recorded a report but its release could not be
     * persisted and read back. The drain restores the
     * pre-release queue before reporting this outcome, because
     * a failed synchronous commit can still leave the in-memory
     * preferences changed — without the restore, a later drain
     * in the same process would find no head to retry even
     * though the release never became durable. The report stays
     * pending and a later drain re-reports it against the same
     * source id, which the brain files once — a storage fault
     * costs a re-report, never a lost verdict and never a
     * silently reordered one. [reportedCount] counts only the
     * releases this drain did persist, and [remainingCount] is
     * the stored queue's size read back after the restore.
     */
    data class StorageFailed(
        val reportedCount: Int,
        val remainingCount: Int
    ) : PendingReportQueueDrainResult
}
