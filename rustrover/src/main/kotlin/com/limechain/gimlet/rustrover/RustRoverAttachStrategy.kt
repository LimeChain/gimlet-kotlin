package com.limechain.gimlet.rustrover

import com.intellij.execution.DefaultExecutionTarget
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSessionListener
import com.jetbrains.cidr.execution.debugger.CidrDebugProcess
import com.limechain.gimlet.AttachStrategy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val LOG = logger<RustRoverAttachStrategy>()

/**
 * RustRover implementation of [AttachStrategy]. Builds a transient
 * [GimletRunConfiguration] (our parallel of the Rust plugin's
 * `RsRemoteRunConfiguration` - see that class's docs for why we can't
 * subclass) with platform-tools LLDB pinned via the configuration's
 * `lldbBinary` / `lldbFramework` fields, then submits it through
 * `ExecutionEnvironmentBuilder.buildAndExecute()`.
 *
 * The submission uses `DefaultExecutionTarget.INSTANCE` to dodge the
 * Rust plugin's test-gutter aggregator setting target=`"test"`, which
 * CIDR's remote runner rejects.
 *
 * **`withKeepProcessSuspendedAfterAttach` uses the default no-op.**
 * The suspension behavior is handled lower down by
 * [GimletCidrLauncher]'s driver configuration.
 *
 * **`requiresInitialPauseAfterAttach` returns true.** Our
 * [GimletCidrLauncher] overrides CIDR's default
 * `continueAfterAttach` driver knob, so the target should stay
 * suspended after attach. Missing the initial pause is therefore a
 * real attach failure.
 */
internal class RustRoverAttachStrategy : AttachStrategy {

    override fun requiresInitialPauseAfterAttach(): Boolean = true

    /**
     * Issue `process interrupt` on the debug process's LLDB driver
     * and wait briefly for the resulting stop event. sbpf's gdbstub
     * only dispatches `qRcmd` packets in its `Idle` state machine
     * variant - while the inferior is running, our metadata-read
     * `process plugin packet monitor metadata` returns UNIMPLEMENTED.
     *
     * `process interrupt` over gdb-remote sends Ctrl-C (`\x03`) to
     * the target; sbpf's state machine routes that through the
     * `CtrlCInterrupt` arm and back to `Idle` where the next packet
     * is processed normally.
     */
    override suspend fun forcePauseAfterAttach(debugProcess: CidrDebugProcess) {
        val session = debugProcess.session
        val sessionPaused = CompletableDeferred<Unit>()
        val sessionResumed = CompletableDeferred<Unit>()
        val listener = object : XDebugSessionListener {
            override fun sessionPaused() {
                if (!sessionPaused.isCompleted) sessionPaused.complete(Unit)
            }

            override fun sessionResumed() {
                if (!sessionResumed.isCompleted) sessionResumed.complete(Unit)
            }
        }
        session.addSessionListener(listener)
        try {
            val attached = withContext(Dispatchers.IO) {
                debugProcess.waitForAttach(ATTACH_READY_WAIT.inWholeMilliseconds.toInt())
            }
            if (!attached) {
                LOG.warn(
                    "Gimlet RR: timed out waiting for CIDR to attach the inferior " +
                        "before process interrupt",
                )
            }

            if (session.isPaused) {
                withTimeoutOrNull(AUTO_RESUME_WAIT) { sessionResumed.await() }
            }
            if (session.isPaused) {
                LOG.info("Gimlet RR: session already paused after attach; no process interrupt needed")
                return
            }

            // postCommand routes the interpreter command through the
            // CIDR driver queue so it runs on a pooled thread (driver
            // I/O on EDT throws). Same pattern the orchestrator uses
            // for its `target modules add` etc. commands.
            val interruptOutput = interruptProcess(debugProcess)
            if (interruptOutput.contains("requires a current process", ignoreCase = true)) {
                delay(RETRY_AFTER_NO_PROCESS)
                val retryOutput = interruptProcess(debugProcess)
                if (retryOutput.contains("requires a current process", ignoreCase = true)) {
                    LOG.warn(
                        "Gimlet RR: process interrupt still has no current process after retry: " +
                            retryOutput.lineSequence().firstOrNull().orEmpty(),
                    )
                }
            }
            // Wait briefly for the sessionPaused that the interrupt
            // should produce. If it doesn't arrive (e.g., sbpf
            // already paused on its own, or the interrupt race lost),
            // proceed anyway - the metadata read will either succeed
            // (target was already in Idle) or the orchestrator will
            // surface the resulting parse failure as before.
            withTimeoutOrNull(PAUSE_AFTER_INTERRUPT) { sessionPaused.await() }
            // Settle: sbpf's state machine just transitioned through
            // CtrlCInterrupt → Idle; let the next interpreter command
            // arrive after that transition completes.
            delay(POST_INTERRUPT_SETTLE)
            LOG.info(
                "Gimlet RR: process interrupt issued; " +
                    "sessionPaused=${sessionPaused.isCompleted}, " +
                    "sessionResumed=${sessionResumed.isCompleted}",
            )
        } catch (t: Throwable) {
            LOG.warn("Gimlet RR: forcePauseAfterAttach threw", t)
        } finally {
            // Listener cleanup is best-effort - XDebugSession doesn't
            // expose a public `removeSessionListener` symmetric to the
            // add API. The session ends shortly anyway; the listener
            // becomes garbage on session disposal.
            @Suppress("UNUSED_EXPRESSION") listener
        }
    }

    private suspend fun interruptProcess(debugProcess: CidrDebugProcess): String =
        withContext(Dispatchers.IO) {
            val future = debugProcess.postCommand(
                CidrDebugProcess.DebuggerCommand { driver ->
                    driver.executeInterpreterCommand("process interrupt")
                },
            )
            future.get(INTERRUPT_WAIT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        }

    override suspend fun submitAttach(
        project: Project,
        lldbPath: Path,
        tcpPort: Int,
        sessionName: String,
    ) {
        val frameworkPath = resolveFrameworkPath(lldbPath)
            ?: error(
                "RustRoverAttachStrategy: could not derive liblldb framework next to $lldbPath. " +
                    "Expected `<lldbPath>/../../lib/liblldb.{dylib,so}`.",
            )

        withContext(Dispatchers.EDT) {
            val type = ConfigurationTypeUtil
                .findConfigurationType(GimletRunConfigurationType::class.java)
            val factory = type.configurationFactories[0]
            val runSettings = RunManager.getInstance(project).createConfiguration(
                sessionName,
                factory,
            )
            runSettings.isTemporary = true
            val cfg = runSettings.configuration as GimletRunConfiguration
            cfg.lldbBinary = lldbPath
            cfg.lldbFramework = frameworkPath
            cfg.tcpPort = tcpPort

            ExecutionEnvironmentBuilder
                .create(DefaultDebugExecutor.getDebugExecutorInstance(), runSettings)
                .target(DefaultExecutionTarget.INSTANCE)
                .buildAndExecute()
        }
        LOG.info(
            "Gimlet RR strategy: submitted GimletRunConfiguration '$sessionName' " +
                "(lldbBinary=$lldbPath, framework=$frameworkPath, port=$tcpPort)",
        )
    }

    /**
     * Derive the framework path next to the lldb binary. Platform-tools
     * lays out `llvm/bin/lldb` and `llvm/lib/liblldb.{dylib,so}` on
     * macOS / Linux; Windows isn't supported by gimlet-rr today.
     * Mirrors the spike's working resolution from `LiveAttachAction`.
     */
    private fun resolveFrameworkPath(lldbBin: Path): Path? {
        val libDir = lldbBin.parent?.parent?.resolve("lib") ?: return null
        return listOf("liblldb.dylib", "liblldb.so")
            .map(libDir::resolve)
            .firstOrNull { Files.exists(it) }
    }

    companion object {
        // Generous timeout: the first iteration used 5 s and missed the
        // command's actual completion by about 10 s. process interrupt
        // blocks LLDB's driver thread until the inferior reports
        // stopped, and sbpf's gdbstub Continue loop polls the TCP
        // buffer once per BPF instruction - fast in principle, but
        // stragglers happen when the BPF program is in a tight loop
        // or doing a long allocation. 30 s matches the orchestrator's
        // LLDB_COMMAND_WAIT for other interpreter commands.
        private val ATTACH_READY_WAIT: Duration = 5.seconds
        private val INTERRUPT_WAIT: Duration = 30.seconds
        private val AUTO_RESUME_WAIT: Duration = 500.milliseconds
        private val PAUSE_AFTER_INTERRUPT: Duration = 5.seconds
        private val RETRY_AFTER_NO_PROCESS: Duration = 500.milliseconds
        // After the interrupt returns and sessionPaused fires, give
        // sbpf's state machine a beat to complete the
        // CtrlCInterrupt → Idle transition before the orchestrator
        // queues the metadata-read packet.
        private val POST_INTERRUPT_SETTLE: Duration = 250.milliseconds
    }
}
