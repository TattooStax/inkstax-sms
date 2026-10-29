package inkstax.sms.transport

/**
 * Pending outbound report queue definitions for the bridge seam.
 *
 * OutboundAckWire.kt defines the one report shape every binding
 * uses to tell the brain a reply actually sent or failed, and
 * this file defines the one queue shape every binding holds the
 * reports that have not landed yet in, so what may be held for
 * reporting, in what order it is reported, and what removes it
 * cannot drift between bindings. The queue is a plain immutable
 * list of values: there is no storage, no timer, and no network
 * code here — a binding keeps the list, reports its head through
 * the outbound ack binding, and folds each report outcome back
 * through these functions.
 *
 * Only a verdict the report wire itself would carry may be
 * held: a reply identity the seam could have minted (never
 * blank, never carrying whitespace), a report time that is not
 * negative (a caller fault the ack binding throws for, so the
 * entry could never be reported and would block the head of the
 * queue forever), and an error code that agrees with the
 * verdict — a sent verdict carries none and a failure verdict
 * carries the code the send failed with, the unknown code
 * included. A verdict failing any of those is unreportable, not
 * pending: holding it would park the queue behind a report that
 * can never land.
 *
 * The reply source id is the pending report's identity: a
 * verdict whose source id is already pending is the same
 * verdict seen again, never a second entry, because the phone's
 * verdict on a send is terminal — a duplicate or contradictory
 * late verdict changes nothing about the send, so the first
 * held verdict is the one the brain is owed, and a second entry
 * would file a second, divergent record against an identity the
 * brain files once.
 *
 * The queue reports in the order verdicts were reached, so the
 * brain files a conversation's outcomes in the order the phone
 * reached them. An entry leaves the queue only when the brain
 * records the report — the recording answer is the release —
 * and every failed outcome leaves the entry where it stands,
 * head first, for a re-report against the same source id. A
 * lost recording answer therefore costs a re-report the brain
 * can recognise by identity, never a lost verdict and never a
 * silently reordered one. Whether the verdict itself moved the
 * local ack state is a separate fact the wire already settles:
 * the phone's verdict moves that state whether or not its
 * report has landed yet.
 */

/**
 * One pending outbound report: the phone's verdict on the reply
 * filed under [sourceId] — the event itself, the sending error
 * code on a failure, and the time the phone reached the verdict,
 * which is the report time the wire carries.
 */
data class PendingOutboundReport(
    val sourceId: String,
    val event: OutboundAckEvent,
    val errorCode: Int?,
    val reportedAtMillis: Long
)

/**
 * The pending form of the phone's verdict on the reply filed
 * under [sourceId], or null when the verdict may not be held
 * for reporting: the report time is negative, the source id is
 * blank or carries whitespace, or the error code contradicts
 * the event — the same conditions the report wire refuses,
 * applied here at the point of holding so an unreportable
 * verdict never enters a queue it could never drain from.
 */
fun pendingOutboundReportFor(
    sourceId: String,
    event: OutboundAckEvent,
    errorCode: Int?,
    reportedAtMillis: Long
): PendingOutboundReport? {
    if (reportedAtMillis < 0L) {
        return null
    }
    if (sourceId.isBlank() || sourceId.any { it.isWhitespace() }) {
        return null
    }
    when (event) {
        OutboundAckEvent.PHONE_ACKED_SENT -> if (errorCode != null) {
            return null
        }
        OutboundAckEvent.PHONE_REPORTED_FAILURE -> if (errorCode == null) {
            return null
        }
    }
    return PendingOutboundReport(sourceId, event, errorCode, reportedAtMillis)
}

/**
 * The queue after the phone reaches a verdict on the reply
 * filed under [sourceId]: the verdict appended in reached order
 * when it may be held and its source id is not already pending,
 * and the queue unchanged otherwise — an unreportable verdict
 * stays with the caller, and a re-seen verdict is the pending
 * report itself, not a second filing of it.
 */
fun pendingReportQueueAfterVerdict(
    queue: List<PendingOutboundReport>,
    sourceId: String,
    event: OutboundAckEvent,
    errorCode: Int?,
    reportedAtMillis: Long
): List<PendingOutboundReport> {
    val pending = pendingOutboundReportFor(sourceId, event, errorCode, reportedAtMillis)
        ?: return queue
    if (queue.any { it.sourceId == pending.sourceId }) {
        return queue
    }
    return queue + pending
}

/** The next report to send: the head of the queue, in reached order. */
fun nextPendingOutboundReport(queue: List<PendingOutboundReport>): PendingOutboundReport? {
    return queue.firstOrNull()
}

/**
 * The queue after one report attempt on the entry filed under
 * [sourceId]: the entry is removed only when the brain recorded
 * the report. Every failed outcome leaves the queue untouched —
 * the entry keeps its place for a re-report, and an outcome
 * naming a source id that is not pending changes nothing.
 */
fun pendingReportQueueAfterReport(
    queue: List<PendingOutboundReport>,
    sourceId: String,
    outcome: OutboundAckOutcome
): List<PendingOutboundReport> {
    val entry = queue.firstOrNull { it.sourceId == sourceId } ?: return queue
    return if (outcome == OutboundAckOutcome.Recorded) {
        queue - entry
    } else {
        queue
    }
}
