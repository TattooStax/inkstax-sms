package org.fossify.messages.transport

/**
 * The one lock serializing changes to the stored credential
 * against pairing-scoped work: storing or clearing the
 * credential (the only mutations, both in CredentialStorage)
 * runs under this lock, and a caller that captures a
 * credential together with state belonging to that pairing —
 * the verdict caller checking its credential as it holds a
 * report, the pending report drain capturing its queue
 * snapshot and checking its pairing again at release — holds
 * the same lock, so the pairing it captured cannot change
 * inside its guarded section. Without one order between them, a
 * disconnect and re-pair landing between a snapshot and its
 * release could stamp one pairing's queued work with the
 * next pairing's identity and send it to the wrong endpoint.
 * It is process-wide so it holds across the pairing,
 * disconnect, and drain callers alike.
 *
 * Only storage operations run under this lock, never a
 * network call: a report performed with a captured credential
 * happens outside it, and the release re-checks the pairing
 * inside it before folding anything.
 */
internal object CredentialGuard {
    val lock = Any()
}
