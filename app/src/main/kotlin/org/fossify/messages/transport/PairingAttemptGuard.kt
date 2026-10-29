package org.fossify.messages.transport

import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one in-flight flag for every operation that reads or writes
 * this device's pairing: a pairing attempt (its paired check, the
 * exchange, and the storage retry) and a disconnect's clear. It is
 * process-wide so it survives the hosting activity being recreated
 * mid-operation. Two such operations can never interleave, so
 * their writes can never combine into a credential that matches
 * neither — a disconnect landing mid-exchange would otherwise
 * clear a credential the exchange is about to store, or store one
 * over a disconnect the user just confirmed.
 */
internal object PairingAttemptGuard {
    val inFlight = AtomicBoolean(false)
}
