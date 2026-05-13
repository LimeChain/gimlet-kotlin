package com.limechain.gimlet

/**
 * Three-state model surfaced to the UI by [GimletStateMonitor].
 *
 * - [IDLE]     - nothing is bound to the configured TCP port. User hasn't
 *                run a debug-enabled test yet, or the previous session
 *                cleaned up.
 * - [READY]    - port is bound (gdbstub is listening). The user can click
 *                Attach to start a debug session.
 * - [ATTACHED] - Gimlet has an active CIDR debug session. Click Stop to
 *                end it.
 */
internal enum class GimletState { IDLE, READY, ATTACHED }
