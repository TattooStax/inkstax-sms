package org.fossify.messages.transport

/** The result of one drain of the held inbound queue. */
sealed interface HeldQueueDrainResult {
    /** Nothing was held, so nothing was delivered. */
    data object Empty : HeldQueueDrainResult

    /**
     * Every held message was delivered, the brain acked each
     * one, and each release was persisted and read back, so the
     * stored queue is empty. [deliveredCount] is how many
     * messages this drain released.
     */
    data class Drained(val deliveredCount: Int) : HeldQueueDrainResult

    /**
     * The drain stopped at the head of the queue: [stoppedBy] is
     * the delivery binding's result for that head, and it is not
     * the brain's ack, so the head and everything behind it stay
     * held in order. [deliveredCount] messages ahead of it were
     * acked and their releases persisted in this drain, and
     * [remainingCount] messages remain held, head first, for a
     * later drain against the same source ids.
     */
    data class Blocked(
        val stoppedBy: InboundDeliveryBindingResult,
        val deliveredCount: Int,
        val remainingCount: Int
    ) : HeldQueueDrainResult

    /**
     * The brain acked a message but its release could not be
     * persisted and read back. The drain restores the
     * pre-release queue before reporting this outcome, because
     * a failed synchronous commit can still leave the in-memory
     * preferences changed — without the restore, a later drain
     * in the same process would find no head to retry even
     * though the release never became durable. The message
     * stays held and a later drain redelivers it against the
     * same source id, which the brain files once — a storage
     * fault costs a redelivery, never a lost message and never
     * a silently reordered one. [deliveredCount] counts only
     * the releases this drain did persist, and [remainingCount]
     * is the stored queue's size read back after the restore.
     */
    data class StorageFailed(
        val deliveredCount: Int,
        val remainingCount: Int
    ) : HeldQueueDrainResult
}
