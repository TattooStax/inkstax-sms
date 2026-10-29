package org.fossify.messages.transport

import android.content.Context
import android.content.SharedPreferences
import inkstax.sms.transport.OutboundAckEvent
import inkstax.sms.transport.PendingOutboundReport
import inkstax.sms.transport.pendingOutboundReportFor
import inkstax.sms.transport.pendingReportQueueAfterVerdict
import org.fossify.commons.extensions.getSharedPrefs

private const val PENDING_QUEUE_COUNT = "pending_report_count"
private const val PENDING_SOURCE_PREFIX = "pending_report_source_"
private const val PENDING_EVENT_PREFIX = "pending_report_event_"
private const val PENDING_ERROR_PRESENT_PREFIX = "pending_report_error_present_"
private const val PENDING_ERROR_PREFIX = "pending_report_error_"
private const val PENDING_REPORTED_AT_PREFIX = "pending_report_reported_at_"
private const val PENDING_PAIRING_ENDPOINT = "pending_report_pairing_endpoint"
private const val PENDING_PAIRING_DEVICE = "pending_report_pairing_device"

/**
 * The pending outbound report queue, read back through the
 * :transport rules: every stored entry is rebuilt as a verdict
 * and re-held only if [pendingOutboundReportFor] still holds
 * it, folded in stored order through
 * [pendingReportQueueAfterVerdict] so a restored queue obeys
 * the same rules as a live one — an entry a definition change
 * no longer holds, an incomplete entry, and a second entry
 * under an already-pending source id are all absent from the
 * result, never reported from storage on storage's say-so
 * alone. The queue lives in the app's shared preferences (the
 * same store Config and the stored credential use) because it
 * must survive the process: a pending report is one the phone
 * owes the brain a recording for, and losing it with the
 * process would lose a verdict the phone already reached.
 *
 * The queue is bound to the pairing that held it: the stored
 * pairing identity (the credential's endpoint and device id,
 * written beside the queue in the same commit) must name this
 * device's current credential for the queue to read back at
 * all. Pending reports were taken in for one endpoint; under
 * a different pairing they would be reported to an endpoint
 * they were never owed to, and with no usable credential there
 * is no pairing to report for — so a queue stored under
 * another pairing, or under none that can still be named,
 * reads as empty, and the next store under the current
 * pairing replaces it wholesale.
 */
fun Context.storedPendingOutboundReportQueue(): List<PendingOutboundReport> {
    val credential = storedDeviceCredential() ?: return emptyList()
    val prefs = getSharedPrefs()
    if (prefs.getString(PENDING_PAIRING_ENDPOINT, null) != credential.endpointBase ||
        prefs.getString(PENDING_PAIRING_DEVICE, null) != credential.deviceId
    ) {
        return emptyList()
    }
    val count = prefs.getInt(PENDING_QUEUE_COUNT, 0)
    var queue: List<PendingOutboundReport> = emptyList()
    for (index in 0 until count) {
        val pending = storedPendingReportAt(prefs, index) ?: continue
        queue = pendingReportQueueAfterVerdict(
            queue, pending.sourceId, pending.event, pending.errorCode, pending.reportedAtMillis
        )
    }
    return queue
}

/**
 * Persists [queue] as the pending outbound report queue and
 * returns whether the queue is durably stored and reads back
 * as the same queue afterwards. The stored form is the queue
 * the definitions hold: the input is folded through
 * [pendingReportQueueAfterVerdict] first, so what is written
 * is exactly what a read-back can return — an unreportable
 * verdict is never written into a queue it could never drain
 * from, and a duplicate source id is never written twice.
 *
 * The whole queue is written in one synchronous commit — the
 * count, every entry's fields, and the removal of any slots
 * past the new end that a previously longer queue left behind
 * — for the same reason the stored credential is written as
 * one batch: separately applied writes could leave a mixed
 * queue behind, and an unchecked asynchronous write cannot be
 * reported honestly. The final read-back decides the reported
 * result — stored means verified, not attempted.
 */
fun Context.storePendingOutboundReportQueue(queue: List<PendingOutboundReport>): Boolean {
    var normalized: List<PendingOutboundReport> = emptyList()
    for (pending in queue) {
        normalized = pendingReportQueueAfterVerdict(
            normalized, pending.sourceId, pending.event, pending.errorCode, pending.reportedAtMillis
        )
    }
    val prefs = getSharedPrefs()
    val previousCount = prefs.getInt(PENDING_QUEUE_COUNT, 0)
    val editor = prefs.edit()
    // The pairing identity travels in the same commit as the
    // queue it binds, so a queue and the pairing it belongs to
    // can never be stored apart: with a usable credential the
    // identity is that credential, and without one the identity
    // is removed, because a queue no current pairing can name
    // must not become reportable under a later pairing.
    val credential = storedDeviceCredential()
    if (credential != null) {
        editor.putString(PENDING_PAIRING_ENDPOINT, credential.endpointBase)
        editor.putString(PENDING_PAIRING_DEVICE, credential.deviceId)
    } else {
        editor.remove(PENDING_PAIRING_ENDPOINT)
        editor.remove(PENDING_PAIRING_DEVICE)
    }
    editor.putInt(PENDING_QUEUE_COUNT, normalized.size)
    normalized.forEachIndexed { index, pending -> editor.putPendingReport(index, pending) }
    for (index in normalized.size until previousCount) {
        editor.removePendingReport(index)
    }
    if (!editor.commit()) {
        return false
    }
    return storedPendingOutboundReportQueue() == normalized
}

/**
 * The pending report stored at [index], or null when its
 * record is incomplete or unreadable: a partly written or
 * damaged entry must not be reconstructed into a verdict the
 * definitions never held, and one damaged entry must not
 * abort the restoration of the entries behind it, so a
 * decoding failure reads as that entry's absence. The
 * nullable error code travels as a presence flag beside the
 * value, because a numeric sentinel could collide with a real
 * error code and mint a verdict the phone never reached — and
 * a presence flag whose value is missing is an incomplete
 * entry, never a licence to read the absent value as zero.
 */
private fun storedPendingReportAt(prefs: SharedPreferences, index: Int): PendingOutboundReport? {
    return runCatching { decodedPendingReportAt(prefs, index) }.getOrNull()
}

private fun decodedPendingReportAt(prefs: SharedPreferences, index: Int): PendingOutboundReport? {
    val sourceId = prefs.getString(PENDING_SOURCE_PREFIX + index, null) ?: return null
    val event = runCatching {
        OutboundAckEvent.valueOf(prefs.getString(PENDING_EVENT_PREFIX + index, null) ?: return null)
    }.getOrNull() ?: return null
    if (!prefs.contains(PENDING_REPORTED_AT_PREFIX + index)) {
        return null
    }
    if (!prefs.contains(PENDING_ERROR_PRESENT_PREFIX + index)) {
        return null
    }
    if (!storedNullableIntIsReadable(prefs, PENDING_ERROR_PRESENT_PREFIX + index, PENDING_ERROR_PREFIX + index)) {
        return null
    }
    return pendingOutboundReportFor(
        sourceId = sourceId,
        event = event,
        errorCode = storedNullableInt(prefs, PENDING_ERROR_PRESENT_PREFIX + index, PENDING_ERROR_PREFIX + index),
        reportedAtMillis = prefs.getLong(PENDING_REPORTED_AT_PREFIX + index, 0L)
    )
}

/** Whether a presence flag and its value agree: absent needs no value, present needs one. */
private fun storedNullableIntIsReadable(prefs: SharedPreferences, presentKey: String, valueKey: String): Boolean {
    if (!prefs.getBoolean(presentKey, false)) {
        return true
    }
    return prefs.contains(valueKey)
}

/** The nullable int named by a presence flag: the value when present, null when absent. */
private fun storedNullableInt(prefs: SharedPreferences, presentKey: String, valueKey: String): Int? {
    if (!prefs.getBoolean(presentKey, false)) {
        return null
    }
    return prefs.getInt(valueKey, 0)
}

private fun SharedPreferences.Editor.putPendingReport(index: Int, pending: PendingOutboundReport) {
    putString(PENDING_SOURCE_PREFIX + index, pending.sourceId)
    putString(PENDING_EVENT_PREFIX + index, pending.event.name)
    putBoolean(PENDING_ERROR_PRESENT_PREFIX + index, pending.errorCode != null)
    putInt(PENDING_ERROR_PREFIX + index, pending.errorCode ?: 0)
    putLong(PENDING_REPORTED_AT_PREFIX + index, pending.reportedAtMillis)
}

private fun SharedPreferences.Editor.removePendingReport(index: Int) {
    remove(PENDING_SOURCE_PREFIX + index)
    remove(PENDING_EVENT_PREFIX + index)
    remove(PENDING_ERROR_PRESENT_PREFIX + index)
    remove(PENDING_ERROR_PREFIX + index)
    remove(PENDING_REPORTED_AT_PREFIX + index)
}
