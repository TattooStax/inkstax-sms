package org.fossify.messages.transport

import android.content.Context
import android.content.SharedPreferences
import inkstax.sms.transport.HeldInboundMessage
import inkstax.sms.transport.InboundKind
import inkstax.sms.transport.InboundSms
import inkstax.sms.transport.heldQueueAfterArrival
import org.fossify.commons.extensions.getSharedPrefs

private const val HELD_QUEUE_COUNT = "held_inbound_count"
private const val HELD_SENDER_PREFIX = "held_inbound_sender_"
private const val HELD_BODY_PREFIX = "held_inbound_body_"
private const val HELD_RECEIVED_AT_PREFIX = "held_inbound_received_at_"
private const val HELD_SUBSCRIPTION_PREFIX = "held_inbound_subscription_"
private const val HELD_KIND_PREFIX = "held_inbound_kind_"
private const val HELD_PROVIDER_ID_PREFIX = "held_inbound_provider_id_"
private const val HELD_PROVIDER_PRESENT_PREFIX = "held_inbound_provider_present_"
private const val HELD_THREAD_ID_PREFIX = "held_inbound_thread_id_"
private const val HELD_THREAD_PRESENT_PREFIX = "held_inbound_thread_present_"

/**
 * The held inbound queue, read back through the :transport rules:
 * every stored entry is rebuilt as a message and re-held only if
 * heldInboundMessageFor still holds it, folded in stored order
 * through [heldQueueAfterArrival] so a restored queue obeys the
 * same rules as a live one — an entry a definition change no
 * longer holds, an incomplete entry, and a second entry under an
 * already-held source id are all absent from the result, never
 * delivered from storage on storage's say-so alone. The queue
 * lives in the app's shared preferences (the same store Config
 * and the stored credential use) because it must survive the
 * process: a held message is one the phone owes the brain an
 * ack-released delivery for, and losing it with the process
 * would lose a message the sender already sent.
 */
fun Context.storedHeldInboundQueue(): List<HeldInboundMessage> {
    val prefs = getSharedPrefs()
    val count = prefs.getInt(HELD_QUEUE_COUNT, 0)
    var queue: List<HeldInboundMessage> = emptyList()
    for (index in 0 until count) {
        val message = storedHeldMessageAt(prefs, index) ?: continue
        queue = heldQueueAfterArrival(queue, message)
    }
    return queue
}

/**
 * Persists [queue] as the held inbound queue and returns whether
 * the queue is durably stored and reads back as the same queue
 * afterwards. The stored form is the queue the definitions hold:
 * the input is folded through [heldQueueAfterArrival] first, so
 * what is written is exactly what a read-back can return — an
 * unholdable entry is never written into a queue it could never
 * drain from, and a duplicate source id is never written twice.
 *
 * The whole queue is written in one synchronous commit — the
 * count, every entry's fields, and the removal of any slots past
 * the new end that a previously longer queue left behind — for
 * the same reason the stored credential is written as one batch:
 * separately applied writes could leave a mixed queue behind,
 * and an unchecked asynchronous write cannot be reported
 * honestly. The final read-back decides the reported result —
 * stored means verified, not attempted.
 */
fun Context.storeHeldInboundQueue(queue: List<HeldInboundMessage>): Boolean {
    var normalized: List<HeldInboundMessage> = emptyList()
    for (held in queue) {
        normalized = heldQueueAfterArrival(normalized, held.message)
    }
    val prefs = getSharedPrefs()
    val previousCount = prefs.getInt(HELD_QUEUE_COUNT, 0)
    val editor = prefs.edit()
    editor.putInt(HELD_QUEUE_COUNT, normalized.size)
    normalized.forEachIndexed { index, held -> editor.putHeldMessage(index, held.message) }
    for (index in normalized.size until previousCount) {
        editor.removeHeldMessage(index)
    }
    if (!editor.commit()) {
        return false
    }
    return storedHeldInboundQueue() == normalized
}

/**
 * The message stored at [index], or null when its record is
 * incomplete or unreadable: a partly written or damaged entry
 * must not be reconstructed into a message the definitions never
 * held, and one damaged entry must not abort the restoration of
 * the entries behind it, so a decoding failure reads as that
 * entry's absence. The nullable provider and thread ids travel
 * as a presence flag beside the value, because a numeric
 * sentinel could collide with a real row id and mint an identity
 * the provider never gave the message — and a presence flag
 * whose value is missing is an incomplete entry, never a
 * licence to read the absent value as zero.
 */
private fun storedHeldMessageAt(prefs: SharedPreferences, index: Int): InboundSms? {
    return runCatching { decodedHeldMessageAt(prefs, index) }.getOrNull()
}

private fun decodedHeldMessageAt(prefs: SharedPreferences, index: Int): InboundSms? {
    val sender = prefs.getString(HELD_SENDER_PREFIX + index, null) ?: return null
    val body = prefs.getString(HELD_BODY_PREFIX + index, null) ?: return null
    if (!prefs.contains(HELD_RECEIVED_AT_PREFIX + index)) {
        return null
    }
    if (!prefs.contains(HELD_SUBSCRIPTION_PREFIX + index)) {
        return null
    }
    val kind = runCatching {
        InboundKind.valueOf(prefs.getString(HELD_KIND_PREFIX + index, null) ?: return null)
    }.getOrNull() ?: return null
    if (!prefs.contains(HELD_PROVIDER_PRESENT_PREFIX + index)) {
        return null
    }
    if (!prefs.contains(HELD_THREAD_PRESENT_PREFIX + index)) {
        return null
    }
    if (!storedNullableLongIsReadable(prefs, HELD_PROVIDER_PRESENT_PREFIX + index, HELD_PROVIDER_ID_PREFIX + index)) {
        return null
    }
    if (!storedNullableLongIsReadable(prefs, HELD_THREAD_PRESENT_PREFIX + index, HELD_THREAD_ID_PREFIX + index)) {
        return null
    }
    return InboundSms(
        sender = sender,
        body = body,
        receivedAtMillis = prefs.getLong(HELD_RECEIVED_AT_PREFIX + index, 0L),
        subscriptionId = prefs.getInt(HELD_SUBSCRIPTION_PREFIX + index, 0),
        kind = kind,
        providerId = storedNullableLong(prefs, HELD_PROVIDER_PRESENT_PREFIX + index, HELD_PROVIDER_ID_PREFIX + index),
        threadId = storedNullableLong(prefs, HELD_THREAD_PRESENT_PREFIX + index, HELD_THREAD_ID_PREFIX + index)
    )
}

/** Whether a presence flag and its value agree: absent needs no value, present needs one. */
private fun storedNullableLongIsReadable(prefs: SharedPreferences, presentKey: String, valueKey: String): Boolean {
    if (!prefs.getBoolean(presentKey, false)) {
        return true
    }
    return prefs.contains(valueKey)
}

/** The nullable long named by a presence flag: the value when present, null when absent. */
private fun storedNullableLong(prefs: SharedPreferences, presentKey: String, valueKey: String): Long? {
    if (!prefs.getBoolean(presentKey, false)) {
        return null
    }
    return prefs.getLong(valueKey, 0L)
}

private fun SharedPreferences.Editor.putHeldMessage(index: Int, message: InboundSms) {
    putString(HELD_SENDER_PREFIX + index, message.sender)
    putString(HELD_BODY_PREFIX + index, message.body)
    putLong(HELD_RECEIVED_AT_PREFIX + index, message.receivedAtMillis)
    putInt(HELD_SUBSCRIPTION_PREFIX + index, message.subscriptionId)
    putString(HELD_KIND_PREFIX + index, message.kind.name)
    putBoolean(HELD_PROVIDER_PRESENT_PREFIX + index, message.providerId != null)
    putLong(HELD_PROVIDER_ID_PREFIX + index, message.providerId ?: 0L)
    putBoolean(HELD_THREAD_PRESENT_PREFIX + index, message.threadId != null)
    putLong(HELD_THREAD_ID_PREFIX + index, message.threadId ?: 0L)
}

private fun SharedPreferences.Editor.removeHeldMessage(index: Int) {
    remove(HELD_SENDER_PREFIX + index)
    remove(HELD_BODY_PREFIX + index)
    remove(HELD_RECEIVED_AT_PREFIX + index)
    remove(HELD_SUBSCRIPTION_PREFIX + index)
    remove(HELD_KIND_PREFIX + index)
    remove(HELD_PROVIDER_ID_PREFIX + index)
    remove(HELD_PROVIDER_PRESENT_PREFIX + index)
    remove(HELD_THREAD_ID_PREFIX + index)
    remove(HELD_THREAD_PRESENT_PREFIX + index)
}
