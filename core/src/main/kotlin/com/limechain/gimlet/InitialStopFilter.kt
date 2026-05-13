package com.limechain.gimlet

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Suppresses the duplicate initial stop CIDR's LLDBFrontend sometimes
 * reports before [com.jetbrains.cidr.execution.debugger.CidrDebugProcess]
 * has finished propagating the first suspend context to
 * [com.intellij.xdebugger.XDebugSession]. Without this guard the second
 * signal triggers the
 * `notifyPositionReached happened while previous one is still being
 * processed` assertion. See [GimletRemoteGdbDebugProcess] for the
 * surrounding wiring.
 *
 * Lifecycle:
 *  * Construction: armed if `initialPaused` is false (the usual case -
 *    no IDE pause has happened yet, so we expect the duplicate). If
 *    the session is already paused at construction the filter starts
 *    permanently disabled.
 *  * [shouldDrop]: forwards the very first stop (returns false) and
 *    drops every subsequent stop (returns true) while armed.
 *  * [onSessionPaused]: permanently disables the filter. Every stop
 *    after this returns false.
 *
 * Thread-safe: the duplicate stop and the IDE-pause callback can race
 * on different CIDR-internal threads. Backed by [AtomicBoolean] +
 * compare-and-set so the "first stop forwards, all others drop"
 * invariant holds under concurrent callers.
 */
internal class InitialStopFilter(initialPaused: Boolean) {

    private val initialPausePending = AtomicBoolean(!initialPaused)
    private val initialSignalForwarded = AtomicBoolean(false)

    /**
     * Mark the IDE as having shown its first paused frame. From this
     * point on every stop propagates - real signals (breakpoints,
     * runtime exceptions, manual process interrupts) must never be
     * dropped.
     */
    fun onSessionPaused() {
        initialPausePending.set(false)
    }

    /**
     * Returns true iff the current stop must be dropped to avoid
     * CIDR's duplicate-stop assertion. The very first stop while
     * armed is always forwarded (returns false). After
     * [onSessionPaused] every call returns false.
     */
    fun shouldDrop(): Boolean {
        if (!initialPausePending.get()) return false
        if (initialSignalForwarded.compareAndSet(false, true)) return false
        return true
    }
}
