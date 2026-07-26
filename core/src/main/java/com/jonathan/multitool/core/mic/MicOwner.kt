package com.jonathan.multitool.core.mic

import java.util.concurrent.atomic.AtomicReference

/**
 * The microphone has exactly one owner at a time. Two AudioRecord clients in one process is
 * undefined behaviour on most devices — the second silently gets nothing — and the shell can
 * navigate from a spectrum tool straight into the drone detector, so the claim is explicit.
 */
object MicOwner {
    private val owner = AtomicReference<String?>(null)

    val current: String? get() = owner.get()

    /** True if the caller now owns the mic (re-entrant for the same tag). */
    fun acquire(tag: String): Boolean =
        owner.compareAndSet(null, tag) || owner.get() == tag

    fun release(tag: String) {
        owner.compareAndSet(tag, null)
    }
}
