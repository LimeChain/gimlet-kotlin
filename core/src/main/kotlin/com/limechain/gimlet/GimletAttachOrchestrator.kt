package com.limechain.gimlet

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import com.jetbrains.cidr.execution.debugger.CidrDebugProcess
import com.jetbrains.cidr.execution.debugger.backend.DebuggerDriver
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private val LOG = logger<GimletAttachOrchestrator>()

/**
 * Drives the Gimlet attach chain - a port-watching loop that auto-
 * attaches a fresh CIDR debug session every time a new gdbstub binds
 * on the configured TCP port. **Sessions are concurrent**.
 *
 * When sbpf invokes a CPI, primary's gdbstub stays alive (port reuse
 * via SO_REUSEADDR) and cpi-target opens a new LISTEN on the same port.
 * Stopping primary's session at that moment severs LLDB's connection,
 * and when sbpf later resumes primary it panics on `UnexpectedEof`
 * trying to read from the dead client. Instead we leave primary's
 * session running and spawn cpi-target's session in parallel - both
 * appear as separate Debug tabs in the IDE and end naturally when their
 * respective programs complete.
 *
 * [attach] starts the chain (no-op while one is already active or any
 * sessions are still alive). [stopAll] tears down every active session
 * and cancels the chain.
 */
@Service(Service.Level.PROJECT)
internal class GimletAttachOrchestrator(
    private val project: Project,
    private val cs: CoroutineScope,
) {

    private val lock = Any()

    /** True while the chain coroutine is running (polling for next LISTEN). */
    private val chainActive = AtomicBoolean(false)

    @Volatile
    private var chainJob: Job? = null

    private val attachSequence = AtomicLong(0)

    /** Sessions that have completed attach and are still alive. */
    private val activeSessions = CopyOnWriteArrayList<XDebugSession>()

    /**
     * Bumped on every [attach] / [stopAll]. Async continuations capture
     * their epoch and re-check before mutating state - late events from
     * a stopped generation can't corrupt a fresh one.
     */
    private val epoch = AtomicLong(0)

    fun attach() {
        synchronized(lock) {
            if (chainActive.get() || activeSessions.isNotEmpty()) {
                LOG.info("Gimlet: attach ignored - chain or sessions still active")
                return
            }
            // Settings UI rejects bad values per-field, but direct
            // edits to `.idea/gimlet.xml` (or VCS-pulled overrides)
            // bypass that. The panel renders a "configuration error"
            // state and disables this button, but we re-check here
            // defensively in case anything reaches us programmatically.
            val errors = GimletSettings.validate(GimletSettings.getInstance(project).state)
            if (errors.isNotEmpty()) {
                notify(
                    "Gimlet configuration has issues:\n" +
                        errors.joinToString("\n") { "• $it" } +
                        "\n\nUpdate Settings → Tools → Gimlet.",
                    NotificationType.ERROR,
                )
                return
            }
            chainActive.set(true)
            val myEpoch = epoch.incrementAndGet()
            chainJob = cs.launch { runChainLoop(myEpoch) }
        }
    }

    fun stopAll() {
        val (snapshot, job) = synchronized(lock) {
            // Bump epoch first - invalidates pending async continuations.
            epoch.incrementAndGet()
            val s = activeSessions.toList()
            val j = chainJob
            activeSessions.clear()
            chainActive.set(false)
            chainJob = null
            s to j
        }
        if (snapshot.isEmpty() && job == null) {
            notify("No active Gimlet debug session to stop.", NotificationType.INFORMATION)
            return
        }
        for (session in snapshot) {
            try {
                session.stop()
            } catch (t: Throwable) {
                LOG.warn("Gimlet: stopping session ${session.sessionName} threw", t)
            }
        }
        job?.cancel()
        GimletStateMonitor.getInstance(project).setAttached(false)
        GimletStateMonitor.getInstance(project).nudge()
    }

    private suspend fun runChainLoop(myEpoch: Long) {
        try {
            val settings = GimletSettings.getInstance(project).state
            val tcpPort = settings.tcpPort

            val lldbPath = preflightLldb() ?: return
            val registry = GimletProgramRegistry.getInstance(project)
            val artifacts = withContext(Dispatchers.IO) { registry.refresh() }
            if (artifacts.isEmpty()) {
                val reason = withContext(Dispatchers.IO) { registry.diagnoseEmpty() }
                notify(emptyRegistryMessage(reason, settings), NotificationType.ERROR)
                return
            }

            if (artifacts.none { it.debugPath != null }) {
                notify(
                    "Registered SBF artifacts have no `.so.debug` companions. Rebuild with --debug.",
                    NotificationType.ERROR,
                )
                return
            }

            // The single main poller drives the whole chain. It never
            // calls session.stop() - previous sessions stay alive
            // through CPI invokes (concurrent design). Each iteration:
            //   1. Wait for next LISTEN on the port. While any session
            //      is alive, wait indefinitely (a future CPI may hit at
            //      any time); when [activeSessions] becomes empty, start
            //      a grace timer and exit if no LISTEN appears within
            //      [NEXT_PROGRAM_TIMEOUT_MS].
            //   2. Attach a new CIDR session.
            //   3. Register it for tracking; loop continues.
            var iteration = 0
            while (epoch.get() == myEpoch) {
                val nextDetected = awaitNextListenWithGrace(tcpPort, myEpoch)
                if (epoch.get() != myEpoch) return
                if (!nextDetected) {
                    LOG.info("Gimlet: no next gdbstub within ${NEXT_PROGRAM_TIMEOUT_MS / 1000}s and no sessions alive; ending chain")
                    return
                }
                if (iteration > 0) {
                    LOG.info("Gimlet: next gdbstub LISTEN detected on $tcpPort (iteration ${iteration + 1})")
                }

                val attached = attachAndConfigureOnce(
                    lldbPath = lldbPath,
                    tcpPort = tcpPort,
                    myEpoch = myEpoch,
                    registry = registry,
                ) ?: return

                // Auto-resume after the strategy's keep-suspended block has closed and
                // all post-attach LLDB commands have flushed - by here CIDR's initial
                // notifyPositionReached pipeline is drained, so XDebugSession.resume()
                // serializes cleanly instead of racing the in-flight first stop.
                if (!settings.stopOnEntry && attached.session.isPaused) {
                    try {
                        attached.session.resume()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        LOG.warn("Gimlet: auto-resume on stopOnEntry=false threw", t)
                    }
                }

                iteration++
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            LOG.warn("Gimlet chain loop threw", t)
            notify("Gimlet attach failed: ${t.message ?: t::class.simpleName}", NotificationType.ERROR)
        } finally {
            if (epoch.get() == myEpoch) {
                chainActive.set(false)
                synchronized(lock) {
                    chainJob = null
                }
                // If no sessions remain, flip the monitor so the panel
                // re-enables Attach. If sessions are still ending, their
                // own removal handler will flip the monitor when the last
                // one drops.
                if (activeSessions.isEmpty()) {
                    GimletStateMonitor.getInstance(project).setAttached(false)
                    GimletStateMonitor.getInstance(project).nudge()
                }
            }
        }
    }

    /**
     * Tracks an attached session: adds it to [activeSessions], flips
     * the StateMonitor to ATTACHED, and launches a child coroutine
     * that removes it from the list when it ends naturally (and flips
     * the monitor back to READY/IDLE if it was the last one and the
     * chain has already exited).
     */
    private fun registerActiveSession(
        session: XDebugSession,
        sessionFinished: CompletableDeferred<Unit>,
        myEpoch: Long,
    ): Boolean {
        // Epoch-guarded add: stopAll may have run between attach
        // completion and this call. If so, the session is an orphan -
        // don't track it; just stop it.
        val proceed = synchronized(lock) {
            if (epoch.get() != myEpoch) {
                false
            } else {
                activeSessions.add(session)
                true
            }
        }
        if (!proceed) {
            try {
                session.stop()
            } catch (t: Throwable) {
                LOG.warn("Gimlet: orphan session stop threw", t)
            }
            return false
        }
        GimletStateMonitor.getInstance(project).setAttached(true)
        cs.launch {
            sessionFinished.await()
            if (epoch.get() != myEpoch) return@launch
            activeSessions.remove(session)
            LOG.info("Gimlet: session ${session.sessionName} removed (active sessions: ${activeSessions.size})")
            if (activeSessions.isEmpty() && !chainActive.get()) {
                GimletStateMonitor.getInstance(project).setAttached(false)
                GimletStateMonitor.getInstance(project).nudge()
            }
        }
        return true
    }

    private data class AttachedSession(
        val session: XDebugSession,
        val sessionFinished: CompletableDeferred<Unit>,
    )

    private suspend fun attachAndConfigureOnce(
        lldbPath: Path,
        tcpPort: Int,
        myEpoch: Long,
        registry: GimletProgramRegistry,
    ): AttachedSession? = attachStrategy.withKeepProcessSuspendedAfterAttach {
        val sessionFinished = CompletableDeferred<Unit>()
        val initialPause = CompletableDeferred<Unit>()
        val debugProcess = attachOnce(
            lldbPath, tcpPort, myEpoch, sessionFinished, initialPause,
        ) ?: return@withKeepProcessSuspendedAfterAttach null
        val session = debugProcess.session

        // Race: stopAll() bumped epoch while attachOnce was awaiting.
        // Stop the orphan and bail; don't add to activeSessions.
        if (epoch.get() != myEpoch) {
            try {
                session.stop()
            } catch (t: Throwable) {
                LOG.warn("Gimlet: orphan session stop threw", t)
            }
            return@withKeepProcessSuspendedAfterAttach null
        }
        if (!registerActiveSession(session, sessionFinished, myEpoch)) {
            return@withKeepProcessSuspendedAfterAttach null
        }

        // Strict strategies keep the inferior suspended after the
        // initial gdb-remote stop, so wait for that pause before
        // issuing post-attach commands. Non-strict strategies treat the
        // first pause as advisory and may re-stop the target through
        // forcePauseAfterAttach.
        val requiresInitialPause = attachStrategy.requiresInitialPauseAfterAttach()
        val pauseObserved = if (requiresInitialPause) {
            withTimeoutOrNull(ATTACH_WAIT_MS) { initialPause.await() } != null
        } else {
            initialPause.isCompleted || session.isPaused
        }
        if (!pauseObserved && requiresInitialPause) {
            notify(
                "LLDB attached, but the session never paused at entry within " +
                    "${ATTACH_WAIT_MS / 1000}s. Stopping.",
                NotificationType.ERROR,
            )
            try {
                session.stop()
            } catch (t: Throwable) {
                LOG.warn("Gimlet: stopping never-paused session threw", t)
            }
            return@withKeepProcessSuspendedAfterAttach null
        }
        if (!requiresInitialPause) {
            LOG.info(
                "Gimlet: initial pause ${if (pauseObserved) "observed" else "not observed"}; " +
                    "asking strategy to force a pause before metadata read.",
            )
            try {
                attachStrategy.forcePauseAfterAttach(debugProcess)
            } catch (t: Throwable) {
                LOG.warn("Gimlet: forcePauseAfterAttach threw; proceeding anyway", t)
            }
        }

        val (metadata, symbolFile) = try {
            loadProgramModulesPostAttach(debugProcess, lldbPath, registry)
        } catch (e: CancellationException) {
            try {
                session.stop()
            } catch (stopError: Throwable) {
                LOG.warn("Gimlet: stopping cancelled post-attach session threw", stopError)
            }
            throw e
        } catch (t: Throwable) {
            try {
                session.stop()
            } catch (stopError: Throwable) {
                LOG.warn("Gimlet: stopping failed post-attach session threw", stopError)
            }
            throw t
        }
        notify(
            "Gimlet attached to ${metadata.programId} (cpi_level=${metadata.cpiLevel}), " +
                "symbols ${symbolFile.fileName}.",
            NotificationType.INFORMATION,
        )
        AttachedSession(session, sessionFinished)
    }

    private fun preflightLldb(): Path? = when (val r = LldbPathResolver.resolve(project)) {
        is LldbPathResolver.Result.Ok -> r.expectedPath
        is LldbPathResolver.Result.Missing -> {
            notify(
                "Gimlet could not find platform-tools LLDB at ${r.expectedPath}. " +
                    "Install Solana platform-tools or update Settings → Tools → Gimlet.",
                NotificationType.ERROR,
            )
            null
        }
        is LldbPathResolver.Result.NotExecutable -> {
            notify(
                "Gimlet found ${r.expectedPath} but it isn't executable. " +
                    "Try `chmod +x ${r.expectedPath}` and retry.",
                NotificationType.ERROR,
            )
            null
        }
    }

    private suspend fun loadProgramModulesPostAttach(
        debugProcess: CidrDebugProcess,
        lldbPath: Path,
        registry: GimletProgramRegistry,
    ): Pair<GdbstubMetadata, Path> {
        LOG.info("Gimlet: loadProgramModulesPostAttach starting")
        // Diagnostic: log the LLDB version so we know which framework
        // got loaded. If `setCustomLLDBPath` triggered CIDR's
        // `initCustomMacLldb` and the DYLD_FRAMEWORK_PATH symlink
        // resolved to platform-tools' liblldb, this should report
        // `lldb version 20.1.7-rust-dev`. If it reports a JetBrains
        // build version, the framework swap didn't take effect and
        // metadata reads will fail.
        try {
            val version = runLldbCommand(debugProcess, "version")
            LOG.info("Gimlet: LLDB version = ${version.lineSequence().firstOrNull { it.isNotBlank() }?.take(200)}")
        } catch (t: Throwable) {
            LOG.warn("Gimlet: version probe threw", t)
        }
        setupLldbHelpers(debugProcess, lldbPath)
        val metadata = readMetadataPostAttach(debugProcess, lldbPath)
        LOG.info("Gimlet: read program_id=${metadata.programId} cpi_level=${metadata.cpiLevel}")
        val artifact = registry.findByProgramId(metadata.programId)
            ?: run {
                val mapFile = GimletSettings.getInstance(project).state
                    .resolveSbfTraceDir(project).resolve("program_ids.map")
                throw IllegalStateException(
                    "Gimlet read program_id ${metadata.programId} from the gdbstub, " +
                        "but it is not present in $mapFile.",
                )
            }
        val symbolFile = artifact.debugPath
            ?: throw IllegalStateException(
                "Resolved artifact ${artifact.programId} has no .so.debug - rebuild with --debug.",
            )

        // `target modules add` / `target modules load -s 0x0` don't change
        // the program counter and don't elicit stop replies, so they're
        // safe to issue while CIDR is still finishing the initial-stop
        // pipeline.
        runLldbCommand(debugProcess, "target modules add ${lldbQuote(symbolFile)}")
        runLldbCommand(debugProcess, "target modules load -f ${lldbQuote(symbolFile)} -s 0x0")
        LOG.info("Gimlet: loadProgramModulesPostAttach complete (symbols=${symbolFile.fileName})")

        // Resume (when stopOnEntry=false) is handled by the caller in runChainLoop,
        // after this returns - raw LLDB `continue` here would race CIDR's in-flight
        // notifyPositionReached from the initial stop.
        return metadata to symbolFile
    }

    /**
     * Imports platform-tools' LLDB Python helpers - Rust + Solana type
     * formatters, the input deserializer, and the output saver - so
     * variable inspection in the IDE renders Vec/String/Pubkey/etc.
     * properly instead of raw byte arrays.
     *
     * Also prepends platform-tools' Python `lib/python<ver>/<*-packages>/`
     * dirs to both `sys.path` (so the helpers' internal `import`s
     * resolve) and `os.environ['PYTHONPATH']` (so any subprocesses
     * LLDB spawns inherit the same path). The exact directory is
     * auto-discovered relative to the `lldb` binary because
     * platform-tools releases pin different Python versions per
     * toolchain version, and the path is OS-agnostic via [Path]
     * resolution. Best-effort: missing helpers are warned and skipped
     * so a partial platform-tools install still attaches; the
     * downstream `solana_save_output` command will fail explicitly if
     * its script wasn't imported, which is the only required helper.
     */
    private suspend fun setupLldbHelpers(
        debugProcess: CidrDebugProcess,
        lldbPath: Path,
    ) {
        val scriptsDir = lldbPath.parent
            ?: throw IllegalStateException(
                "Cannot resolve LLDB scripts directory from $lldbPath."
            )

        val pythonPaths = withContext(Dispatchers.IO) { discoverPythonPaths(scriptsDir) }
        if (pythonPaths.isNotEmpty()) {
            val pyList = pythonPaths.joinToString(", ", "[", "]") {
                lldbQuote(it)
            }
            // Single-line `script` so the whole sys.path / PYTHONPATH
            // setup lands as one driver-queue entry instead of three
            // separate ones.
            runLldbCommand(
                debugProcess,
                "script import sys, os; sys.path[:0] = $pyList; " +
                    "os.environ['PYTHONPATH'] = os.pathsep.join($pyList + " +
                    "([os.environ['PYTHONPATH']] if 'PYTHONPATH' in os.environ else []))",
            )
            LOG.info("Gimlet: LLDB Python paths prepended: ${pythonPaths.joinToString(", ")}")
        }

        for (script in HELPER_SCRIPTS) {
            val path = scriptsDir.resolve(script)
            if (!withContext(Dispatchers.IO) { Files.isRegularFile(path) }) {
                LOG.warn("Gimlet: missing platform-tools script $path; skipping")
                continue
            }
            runLldbCommand(debugProcess, "command script import ${lldbQuote(path)}")
        }
    }

    /**
     * Returns the directory to prepend to LLDB's Python `sys.path` /
     * `PYTHONPATH`: platform-tools' bundled `lib/python<ver>/<*-packages>/`,
     * auto-discovered relative to the `lldb` binary. Platform-tools
     * releases pin different Python versions per toolchain version, so
     * we glob `python*`; the inner directory uses `*-packages` to
     * match both `site-packages` (macOS / standard CPython) and
     * `dist-packages` (Linux distros).
     *
     * Helper scripts in `bin/` next to lldb are not added - we
     * `command script import` them by absolute path, so they don't
     * need to be on the import path.
     */
    private fun discoverPythonPaths(scriptsDir: Path): List<Path> {
        val libDir = scriptsDir.parent?.resolve("lib") ?: return emptyList()
        if (!Files.isDirectory(libDir)) return emptyList()
        return try {
            Files.newDirectoryStream(libDir, "python*").use { pythonDirs ->
                pythonDirs.flatMap { pyDir ->
                    Files.newDirectoryStream(pyDir, "*-packages").use { pkgDirs ->
                        pkgDirs.filter { Files.isDirectory(it) }.toList()
                    }
                }
            }
        } catch (t: Throwable) {
            LOG.warn("Gimlet: failed to enumerate $libDir for *-packages", t)
            emptyList()
        }
    }

    private suspend fun readMetadataPostAttach(
        debugProcess: CidrDebugProcess,
        lldbPath: Path,
    ): GdbstubMetadata {
        val saveOutputScript = lldbPath.parent?.resolve("solana_save_output.py")
            ?: throw IllegalStateException("Could not resolve solana_save_output.py next to $lldbPath.")
        if (!withContext(Dispatchers.IO) { Files.isRegularFile(saveOutputScript) }) {
            throw IllegalStateException("Missing LLDB helper script: $saveOutputScript.")
        }

        val metadataFile = withContext(Dispatchers.IO) {
            Files.createTempFile("gimlet-metadata-", ".txt")
        }
        try {
            // [setupLldbHelpers] already imported solana_save_output.py
            // (and the rest of platform-tools' helpers); the
            // `solana_save_output` command is registered at this point.
            val commandOutput = runLldbCommand(
                debugProcess,
                "solana_save_output ${lldbQuote(metadataFile)} process plugin packet monitor metadata",
            )
            val raw = withContext(Dispatchers.IO) {
                Files.readString(metadataFile).ifBlank { commandOutput }
            }
            return GdbstubMetadata.parse(raw)
                ?: throw IllegalStateException(
                    "Gimlet could not parse gdbstub metadata: " +
                        raw.lineSequence().firstOrNull()?.take(200).orEmpty(),
                )
        } finally {
            withContext(Dispatchers.IO) {
                try {
                    Files.deleteIfExists(metadataFile)
                } catch (t: Throwable) {
                    LOG.warn("Gimlet: failed to delete metadata file $metadataFile", t)
                }
            }
        }
    }

    /**
     * Polls for the next `LISTEN` on [port], with a grace window that
     * activates only when [activeSessions] is empty. Returns:
     *  - `true` if a `LISTEN` was observed (chain proceeds).
     *  - `false` if [activeSessions] has been empty for more than
     *    [NEXT_PROGRAM_TIMEOUT_MS] without any LISTEN - the test process
     *    finished and the chain should exit cleanly. Re-evaluates
     *    [activeSessions] each poll, so sessions ending mid-wait flip
     *    the wait into grace mode.
     */
    private suspend fun awaitNextListenWithGrace(port: Int, myEpoch: Long): Boolean {
        var graceUntilMs: Long = Long.MAX_VALUE
        while (epoch.get() == myEpoch) {
            if (withContext(Dispatchers.IO) { isPortListening(port) }) return true
            if (activeSessions.isEmpty()) {
                if (graceUntilMs == Long.MAX_VALUE) {
                    graceUntilMs = System.currentTimeMillis() + NEXT_PROGRAM_TIMEOUT_MS
                }
                if (System.currentTimeMillis() >= graceUntilMs) return false
            } else {
                graceUntilMs = Long.MAX_VALUE
            }
            delay(NEXT_PROGRAM_POLL_MS)
        }
        return false
    }

    /**
     * Returns true iff something on `port` is in TCP `LISTEN` state.
     *
     * A bind-probe (`ServerSocket(port).bind`) treats `ESTABLISHED` and
     * `LISTEN` identically - both throw `BindException`. That's not
     * sufficient here: while LLDB is connected to the current gdbstub
     * the socket is `ESTABLISHED`, but we want to wait for the *next*
     * gdbstub which appears as a fresh `LISTEN` after a CPI. We shell
     * out to `lsof -sTCP:LISTEN` for an exact answer.
     */
    private fun isPortListening(port: Int): Boolean {
        return try {
            val process = ProcessBuilder("lsof", "-nP", "-iTCP:$port", "-sTCP:LISTEN")
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(LSOF_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                LOG.warn("Gimlet: lsof port probe for $port timed out")
                return false
            }
            process.exitValue() == 0
        } catch (t: Throwable) {
            LOG.warn("Gimlet: lsof port probe for $port failed", t)
            false
        }
    }

    /**
     * Resolved lazily - application services aren't always ready at
     * project-service construction time, but they are by the time
     * [attach] first executes a coroutine.
     */
    private val attachStrategy: AttachStrategy by lazy { service() }

    private suspend fun runLldbCommand(
        debugProcess: CidrDebugProcess,
        command: String,
    ): String = withContext(Dispatchers.IO) {
        val future = debugProcess.postCommand(object : CidrDebugProcess.DebuggerCommand<String> {
            override fun call(driver: DebuggerDriver): String =
                driver.executeInterpreterCommand(command)
        })
        try {
            future.get(LLDB_COMMAND_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw IllegalStateException(
                "LLDB command timed out after ${LLDB_COMMAND_WAIT_MS / 1000}s: ${command.take(120)}",
                e,
            )
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause != null) throw cause
            throw e
        }
    }

    private fun lldbQuote(path: Path): String =
        "\"" + path.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * Build & launch the CIDR remote-debug run config and await the
     * `XDebugProcess` it produces. Installs the session-stop listener
     * that completes [sessionFinished]. Returns null on timeout / failure.
     */
    private suspend fun attachOnce(
        lldbPath: Path,
        tcpPort: Int,
        myEpoch: Long,
        sessionFinished: CompletableDeferred<Unit>,
        initialPause: CompletableDeferred<Unit>,
    ): CidrDebugProcess? {
        val sessionName = "Gimlet attach :$tcpPort #${attachSequence.incrementAndGet()}"
        val processCaptured = CompletableDeferred<CidrDebugProcess>()
        val busConnection = project.messageBus.connect()
        busConnection.subscribe(XDebuggerManager.TOPIC, object : XDebuggerManagerListener {
            override fun processStarted(debugProcess: XDebugProcess) {
                if (
                    debugProcess is CidrDebugProcess &&
                    debugProcess.session.sessionName == sessionName &&
                    !processCaptured.isCompleted
                ) {
                    processCaptured.complete(debugProcess)
                    val session = debugProcess.session
                    session.addSessionListener(object : XDebugSessionListener {
                        override fun sessionPaused() {
                            if (!initialPause.isCompleted) initialPause.complete(Unit)
                        }
                    })
                    // Cover the race where the initial pause already
                    // fired between processStarted and addSessionListener.
                    if (session.isPaused && !initialPause.isCompleted) {
                        initialPause.complete(Unit)
                    }
                }
            }
        })
        try {
            // IDE-specific run-config construction is delegated to the
            // AttachStrategy registered by the leaf module (:rustrover
            // today). The strategy must use the supplied
            // sessionName so the XDebuggerManagerListener subscription
            // above can correlate processStarted to this submission.
            attachStrategy.submitAttach(project, lldbPath, tcpPort, sessionName)
            val debugProcess = withTimeoutOrNull(ATTACH_WAIT_MS) { processCaptured.await() }
            if (debugProcess == null) {
                notify(
                    "LLDB attach didn't complete within ${ATTACH_WAIT_MS / 1000}s. " +
                        "Make sure cargo test is running with the sbpf-debugger feature.",
                    NotificationType.ERROR,
                )
                return null
            }
            installSessionListener(debugProcess, myEpoch, sessionFinished)
            return debugProcess
        } finally {
            busConnection.disconnect()
        }
    }

    private fun installSessionListener(
        debugProcess: CidrDebugProcess,
        myEpoch: Long,
        sessionFinished: CompletableDeferred<Unit>,
    ) {
        val session = debugProcess.session
        val name = session.sessionName

        val managerConnection = project.messageBus.connect()
        val complete: (String) -> Unit = { source ->
            if (epoch.get() == myEpoch && !sessionFinished.isCompleted) {
                LOG.info("Gimlet: session-end signal '$source' for $name")
                sessionFinished.complete(Unit)
            }
            try { managerConnection.disconnect() } catch (_: Throwable) {}
        }

        // Four overlapping signals - empirically, none of the first
        // three fire reliably when sbpf's gdbstub closes the gdb-remote
        // connection mid-session (LLDBFrontend stays alive, CIDR keeps
        // the XDebugSession in a half-attached state). The watchdog
        // catches that case by polling [XDebugSession.isStopped].

        // (1) Manager-level: process-terminated event.
        managerConnection.subscribe(XDebuggerManager.TOPIC, object : XDebuggerManagerListener {
            override fun processStopped(stopped: XDebugProcess) {
                if (stopped === debugProcess) complete("processStopped")
            }
        })

        // (2) Session-level: fires when XDebugSession.stop() is called.
        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionStopped() {
                complete("sessionStopped")
            }
        })

        // (3) ProcessHandler-level: catches LLDBFrontend subprocess exit.
        debugProcess.processHandler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                complete("processTerminated")
            }
        })

        // (4) Polling watchdog: last resort for the "CIDR doesn't fire
        // anything" case. Cancelled by orchestrator scope teardown
        // (project close / stopAll) and by epoch bumps.
        cs.launch {
            while (isActive && !sessionFinished.isCompleted) {
                if (epoch.get() != myEpoch) return@launch
                if (session.isStopped) {
                    complete("watchdog (session.isStopped)")
                    return@launch
                }
                delay(SESSION_END_POLL_MS)
            }
        }
    }

    private fun emptyRegistryMessage(
        reason: EmptyRegistryReason,
        settings: GimletSettings.InnerState,
    ): String {
        val toolsVersion = settings.platformToolsVersionOrDefault
        val buildHint = "cargo-build-sbf --tools-version v$toolsVersion --debug --arch v1"
        val artifactsHint = "If your `.so` / `.so.debug` files live elsewhere, set " +
            "Settings → Tools → Gimlet → `Artifacts path` (absolute, or relative to the project root)."
        val traceHint = "If your trace lives elsewhere, set " +
            "Settings → Tools → Gimlet → `SBF trace path` (absolute, or relative to the project root)."
        return when (reason) {
            is EmptyRegistryReason.NoProjectBase ->
                "Gimlet can't resolve paths: the project has no base directory yet. " +
                    "Wait for the project to finish loading and retry."
            is EmptyRegistryReason.ArtifactsDirMissing ->
                "Artifacts directory not found: ${reason.artifactsDir}. " +
                    "Build the program with `$buildHint` to create it. $artifactsHint"
            is EmptyRegistryReason.NoSoArtifacts ->
                "No `.so` files in ${reason.artifactsDir}. " +
                    "Build with `$buildHint`. $artifactsHint"
            is EmptyRegistryReason.TraceMapMissingOrEmpty ->
                "SBF trace map not found at ${reason.mapFile}. " +
                    "Run a debug-enabled test first (e.g. `cargo test --features sbpf-debugger`) " +
                    "so the SBPF VM writes `program_ids.map`. $traceHint"
            is EmptyRegistryReason.NoMatches ->
                "Found `.so` files in ${reason.artifactsDir} and program ids in ${reason.mapFile}, " +
                    "but no `.so` sha256 matches a recorded program id. " +
                    "Rebuild with `$buildHint` and re-run the debug test so the map and binaries are in sync."
        }
    }

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Gimlet")
            .createNotification(message, type)
            .notify(project)
    }

    companion object {
        private const val ATTACH_WAIT_MS: Long = 15_000
        private const val LLDB_COMMAND_WAIT_MS: Long = 30_000
        private const val NEXT_PROGRAM_POLL_MS: Long = 500
        // Cleanup grace - if no next gdbstub appears within this window
        // after a session ends, treat the test as done (or panicked) and
        // exit the loop cleanly instead of hanging on awaitPortListening
        // forever.
        private const val NEXT_PROGRAM_TIMEOUT_MS: Long = 2_000
        private const val SESSION_END_POLL_MS: Long = 1_000
        private const val LSOF_TIMEOUT_MS: Long = 1_500

        /**
         * platform-tools LLDB Python helpers. Order matters:
         * solana_lookup may register handlers that lldb_lookup
         * defines, and solana_save_output is what we drive
         * `process plugin packet monitor metadata` through.
         */
        private val HELPER_SCRIPTS = listOf(
            "lldb_lookup.py",
            "solana_lookup.py",
            "solana_input_deserialize_abiv1.py",
            "solana_save_output.py",
        )

        fun getInstance(project: Project): GimletAttachOrchestrator = project.service()
    }
}
