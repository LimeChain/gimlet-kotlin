package com.limechain.gimlet.rustrover

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.jetbrains.cidr.execution.CidrLauncher
import com.jetbrains.cidr.execution.debugger.CidrDebugProcess
import com.jetbrains.cidr.execution.debugger.backend.DebuggerDriverConfiguration
import com.jetbrains.cidr.execution.debugger.remote.CidrRemoteDebugParameters
import com.limechain.gimlet.GimletLLDBDriverConfiguration
import com.limechain.gimlet.GimletRemoteGdbDebugProcess
import java.nio.file.Path

private val LOG = logger<GimletCidrLauncher>()

/**
 * Replaces the Rust plugin's `RemoteLauncher` for our submission. Uses
 * the shared [GimletRemoteGdbDebugProcess] / [GimletLLDBDriverConfiguration]
 * from `:core`.
 *
 * Why our own launcher rather than `RsRemoteRunConfiguration`'s
 * built-in: see `GimletDebugProcess.kt`'s class docs.
 * `CidrRemoteGDBDebugProcess` and the Rust plugin's `RemoteLauncher`
 * are both `final`; the duplicate-initial-stop filter and the
 * platform-tools framework swap both require a custom path here.
 */
internal class GimletCidrLauncher(
    private val project: Project,
    private val lldbBinary: Path,
    @Suppress("unused") private val lldbFramework: Path,
    private val tcpPort: Int,
) : CidrLauncher() {

    override fun getProject(): Project = project

    override fun createProcess(state: CommandLineState): ProcessHandler =
        throw UnsupportedOperationException(
            "GimletCidrLauncher is debug-only; createProcess should never be invoked.",
        )

    override fun createDebugProcess(
        state: CommandLineState,
        session: XDebugSession,
    ): XDebugProcess {
        // GimletRemoteGdbDebugProcess construction (and the
        // TextConsoleBuilderFactory.createBuilder call) require EDT -
        // they touch IDE UI surface. The platform invokes
        // CidrLauncher.createDebugProcess from a coroutine worker, so
        // without an explicit switch we hit
        //   "Access is allowed from Event Dispatch Thread (EDT) only".
        // The Rust plugin's own RemoteLauncher routes through
        // `runBlockingCancellable { ... }` for the same effect; we use
        // invokeAndWait from a non-suspend context.
        var process: CidrDebugProcess? = null
        var failure: Throwable? = null
        ApplicationManager.getApplication().invokeAndWait {
            try {
                process = createDebugProcessOnEdt(session)
            } catch (t: Throwable) {
                failure = t
            }
        }
        failure?.let {
            throw (it as? ExecutionException) ?: ExecutionException(it)
        }
        return process
            ?: throw ExecutionException("Gimlet RR launcher: createDebugProcess returned null without a failure")
    }

    private fun createDebugProcessOnEdt(session: XDebugSession): CidrDebugProcess {
        val driverConfig: DebuggerDriverConfiguration =
            GimletLLDBDriverConfiguration(lldbBinary)
        val params = CidrRemoteDebugParameters(
            /* remoteCommand = */ "connect://127.0.0.1:$tcpPort",
            /* symbolFile   = */ "",
            /* sysroot      = */ "",
        )
        val consoleBuilder = TextConsoleBuilderFactory.getInstance().createBuilder(project)
        val filterProvider = ConsoleFilterProvider { _ -> emptyArray<Filter>() }

        val process = GimletRemoteGdbDebugProcess(
            driverConfig,
            params,
            session,
            consoleBuilder,
            filterProvider,
        )
        configProcessHandler(
            process.processHandler,
            process.isDetachDefault,
            /* reportExitCode = */ false,
            project,
        )
        LOG.info(
            "Gimlet RR launcher: GimletRemoteGdbDebugProcess constructed for connect://127.0.0.1:$tcpPort " +
                "(custom platform-tools LLDB path; continue-after-attach disabled)",
        )
        return process
    }
}
