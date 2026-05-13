package com.limechain.gimlet

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.TextConsoleBuilder
import com.intellij.openapi.diagnostic.logger
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.jetbrains.cidr.execution.TrivialRunParameters
import com.jetbrains.cidr.execution.debugger.CidrDebugProcess
import com.jetbrains.cidr.execution.debugger.backend.DebuggerDriver
import com.jetbrains.cidr.execution.debugger.backend.DebuggerDriverConfiguration
import com.jetbrains.cidr.execution.debugger.backend.DebuggerSourceFileHash
import com.jetbrains.cidr.execution.debugger.backend.lldb.LLDBDriverConfiguration
import com.jetbrains.cidr.execution.debugger.memory.Address
import com.jetbrains.cidr.execution.debugger.remote.CidrRemoteDebugParameters
import java.io.File
import java.nio.file.Path

private val LOG = logger<GimletRemoteGdbDebugProcess>()

/**
 * Local equivalent of `CidrRemoteGDBDebugProcess` (which is `final`).
 * Lives in `:core` so any future second leaf can reuse it. Two reasons
 * we don't use the stock class:
 *
 *  1. **Duplicate initial stop suppression.** LLDBFrontend can report
 *     a second initial SIGTRAP before CIDR has finished propagating
 *     the first suspend context to `XDebugSession`. Stock
 *     [CidrDebugProcess] logs that as
 *     `notifyPositionReached happened while previous one is still being
 *     processed`. Before the first IDE pause is observed, only the
 *     first signal stop is useful - the second is the same event
 *     re-reported by the driver as it settles. Logic lives in
 *     [InitialStopFilter] so it's unit-testable in isolation.
 *  2. **Custom `loadForRemote` driver call.** Faithfully reproduces
 *     `CidrRemoteGDBDebugProcess.doLoadTarget` (decompiled from
 *     nativeDebug-plugin-frontend.jar): pass `connect://host:port` as
 *     the URL, empty `symbolFile` (.so.debug is DWARF-only and would
 *     be rejected by `target create`; we load symbols post-attach via
 *     `target modules add/load -s 0x0`), empty `sysroot`, and the
 *     param's path mappings.
 *
 * **Class is `public` (not `internal`)** because Kotlin's `internal`
 * visibility is per-module and the `:rustrover` leaf module needs to
 * construct it. Treat as implementation detail; external consumers
 * should not subclass.
 */
class GimletRemoteGdbDebugProcess(
    driverConfiguration: DebuggerDriverConfiguration,
    private val parameters: CidrRemoteDebugParameters,
    session: XDebugSession,
    consoleBuilder: TextConsoleBuilder,
    backendFilterProvider: ConsoleFilterProvider,
) : CidrDebugProcess(
    TrivialRunParameters(driverConfiguration, GeneralCommandLine()),
    session,
    consoleBuilder,
    backendFilterProvider,
) {
    private val initialStopFilter = InitialStopFilter(initialPaused = session.isPaused)

    init {
        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() {
                initialStopFilter.onSessionPaused()
            }
        })
    }

    /**
     * Detach (preserve the inferior) instead of kill when the user
     * stops the session. SBPF gdbstub is single-shot - sbpf treats LLDB
     * disconnect as session-over and resumes the VM toward the next
     * gdbstub LISTEN (CPI inner program, or test cleanup). Kill would
     * tear down the cargo test process; detach lets it continue. Same
     * default as `CidrRemoteGDBDebugProcess`.
     */
    override fun isDetachDefault(): Boolean = true

    override fun doLoadTarget(driver: DebuggerDriver): DebuggerDriver.Inferior {
        val project = project
        val symbolFile = parameters.symbolFile
            .takeIf { it.isNotEmpty() }
            ?.let { File(parameters.expandSymbolFile(project)) }
        val sysroot = parameters.sysroot
            .takeIf { it.isNotEmpty() }
            ?.let { File(parameters.expandSysroot(project)) }
        return driver.loadForRemote(
            parameters.expandRemoteCommand(project),
            symbolFile,
            sysroot,
            parameters.driverPathMapping(),
        )
    }

    override fun handleSignal(
        stopPlace: DebuggerDriver.StopPlace,
        signal: String,
        meaning: String,
    ) {
        if (initialStopFilter.shouldDrop()) {
            logDuplicateDrop(signal, meaning)
            return
        }
        super.handleSignal(stopPlace, signal, meaning)
    }

    override fun handleException(
        stopPlace: DebuggerDriver.StopPlace,
        address: Address,
        file: String?,
        hash: DebuggerSourceFileHash?,
        line: Int,
        description: String,
    ) {
        if (initialStopFilter.shouldDrop()) {
            logDuplicateDrop("exception", description)
            return
        }
        super.handleException(stopPlace, address, file, hash, line, description)
    }

    /**
     * Empirically (sbpf + LLDB 20 + RustRover 261) the duplicate
     * initial stop carries the same signal kind / meaning as the
     * first - both are the gdb-remote `T05` (SIGTRAP) the gdbstub
     * sends after `vAttach`. They're indistinguishable at this
     * layer; the args are surfaced in the log line so we can spot a
     * regression where they diverge.
     */
    private fun logDuplicateDrop(signal: String, meaning: String) {
        LOG.info(
            "Gimlet: dropped duplicate initial stop before first IDE pause " +
                "(signal=$signal, meaning=$meaning)",
        )
    }
}

/**
 * [LLDBDriverConfiguration] subclass that does two things:
 *
 *  1. Calls `setCustomLLDBPath` in `init` so CIDR's
 *     `CustomLLDBSupportKt.initCustomMacLldb` (or `initCustomLinuxLldb`)
 *     hooks fire at command-line construction time. On macOS that
 *     constructs `<temp>/LLDB.framework/LLDB → liblldb.dylib` and sets
 *     `DYLD_FRAMEWORK_PATH=<temp>` so the IDE-bundled LLDBFrontend
 *     dlopens platform-tools' SBPF-aware liblldb fork. Linux uses
 *     `LD_LIBRARY_PATH` analogously.
 *
 *  2. Disables CIDR's auto-continue-after-attach. The base
 *     [com.jetbrains.cidr.execution.debugger.backend.DebuggerDriverConfiguration]
 *     defaults to resuming the inferior after the initial gdb-remote
 *     stop reply. We override the three suspension-related methods
 *     here so the inferior stays paused at the initial stop, giving
 *     the orchestrator a chance to load symbols and read gdbstub
 *     metadata before user code runs again.
 *
 * Class is `public` (not `internal`) for the same per-module
 * visibility reason as [GimletRemoteGdbDebugProcess].
 */
class GimletLLDBDriverConfiguration(
    lldbBinary: Path,
) : LLDBDriverConfiguration() {
    init {
        setCustomLLDBPath(lldbBinary.toString())
    }

    override fun isContinueAfterAttachNeeded(): Boolean = false

    override fun canChangeContinueAfterAttach(): Boolean = true

    /**
     * No-op by design. The orchestrator constructs a fresh instance
     * for each attach pass and never reads back through the getter
     * after construction, so silently dropping the write doesn't break
     * any internal CIDR contract on the paths exercised by gimlet.
     * The intent is "Gimlet always wants the target stopped after
     * attach; ignore anyone trying to flip the policy on a transient
     * run config" - declaring it overridable
     * ([canChangeContinueAfterAttach] returns true) but no-op'ing the
     * setter is the cleanest way to express that.
     */
    override fun setContinueAfterAttachNeeded(value: Boolean?) {
        // intentionally empty
    }
}
