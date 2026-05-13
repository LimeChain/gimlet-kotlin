package com.limechain.gimlet

import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * IDE-specific run-config façade for the Gimlet attach pipeline.
 *
 * The orchestrator (`GimletAttachOrchestrator`) owns the cross-IDE
 * concerns - port watching, session lifecycle, post-attach LLDB
 * command dispatch, line markers, registry lookup. Anything that
 * depends on an IDE-specific API gets pushed behind this interface so
 * the orchestrator stays in `:core` and the IDE-coupled code stays in
 * the leaf module.
 *
 * Today the only implementation is `:rustrover`'s `RustRoverAttachStrategy`,
 * registered via `<applicationService>` in its `META-INF/plugin.xml`.
 * The interface is kept (rather than inlined into the orchestrator)
 * so adding a second CIDR-hosting IDE later is an additive change.
 */
// Public (not internal) because Kotlin's `internal` is per-module and
// `:rustrover` is a separate gradle module - internal here would hide
// the interface from the leaf module that needs to implement it. The
// orchestrator (also in :core) is the only intended caller, but the
// interface itself crosses the module boundary.
interface AttachStrategy {
    /**
     * Build and submit the IDE-specific remote-attach run configuration
     * pointing at platform-tools LLDB at [lldbPath] and the SBPF
     * gdbstub on [tcpPort]. Implementations MUST switch to the EDT
     * internally - callers may invoke from any context.
     *
     * The orchestrator captures the resulting `CidrDebugProcess` via
     * `XDebuggerManagerListener.processStarted`, matching by
     * [sessionName]; implementations therefore MUST set the session
     * name on the run config exactly as supplied.
     */
    suspend fun submitAttach(
        project: Project,
        lldbPath: Path,
        tcpPort: Int,
        sessionName: String,
    )

    /**
     * Wrap an attach pass with whatever IDE-specific suspension hooks
     * keep CIDR from auto-resuming the inferior between the initial
     * gdb-remote stop and the orchestrator's post-attach commands
     * (`target modules add/load`, metadata read).
     *
     * RustRover relies on overriding the driver-level
     * `continueAfterAttach` setting (see the launcher's
     * `LLDBDriverConfiguration` subclass) rather than a registry
     * toggle, so this is a pass-through there. Default is also a
     * pass-through.
     */
    suspend fun <T> withKeepProcessSuspendedAfterAttach(block: suspend () -> T): T =
        block()

    /**
     * Whether the orchestrator should bail out if it doesn't observe
     * a `sessionPaused` event within the attach-wait window.
     *
     * Returns true by default - the RustRover launcher's
     * `continueAfterAttach=false` override keeps the inferior
     * suspended at the initial gdb-remote stop, so the pause event
     * is reliable and missing it within 15 s indicates a real attach
     * failure.
     */
    fun requiresInitialPauseAfterAttach(): Boolean = true

    /**
     * Called by the orchestrator after attach if [requiresInitialPauseAfterAttach]
     * is false. Implementations should force the inferior into a
     * stopped state - typically by issuing `process interrupt` on the
     * [debugProcess]'s LLDB driver - so subsequent gdb-remote `qRcmd`
     * packets (e.g.
     * `process plugin packet monitor metadata`) get dispatched by the
     * remote target instead of being buffered while the inferior runs.
     *
     * sbpf's gdbstub (and most gdbstub-crate-based targets) only
     * processes packets in the `Idle` state machine variant; while
     * `Running`, the loop is executing the interpreter and incoming
     * packets sit in the TCP buffer or get rejected with
     * `UNIMPLEMENTED`.
     *
     * Default is a no-op so platforms that don't auto-resume don't
     * pay the round-trip cost or risk spurious stops.
     */
    suspend fun forcePauseAfterAttach(
        debugProcess: com.jetbrains.cidr.execution.debugger.CidrDebugProcess,
    ) {
        // no-op by default
    }
}
