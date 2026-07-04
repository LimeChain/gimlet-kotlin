package com.limechain.gimlet

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val LOG = logger<LldbPathResolver>()

/**
 * Resolves which `lldb` binary the attachment should drive.
 *
 * The plugin owns the LLDB path: the user only has to install
 * platform-tools via the Solana CLI, and we compute the binary path
 * from `platformToolsVersion`. RustRover's bundled LLDB can't parse
 * `e_machine = EM_BPF` (rejects symbol loads with `error: unsupported
 * module`), so we always point CIDR at the platform-tools LLDB fork.
 * The override flows through [GimletLLDBDriverConfiguration]'s
 * `setCustomLLDBPath` call, which fires CIDR's `initCustomMacLldb` /
 * `initCustomLinuxLldb` hooks at command-line construction.
 */
internal object LldbPathResolver {

    sealed interface Result {
        val expectedPath: Path

        data class Ok(override val expectedPath: Path) : Result
        data class Missing(override val expectedPath: Path) : Result
        data class NotExecutable(override val expectedPath: Path) : Result
    }

    fun resolve(project: Project): Result {
        val settings = GimletSettings.getInstance(project).state
        val path = settings.resolvePlatformToolsRoot().resolve(Path.of("llvm", "bin", "lldb"))
        return when {
            !Files.exists(path) -> Result.Missing(path)
            !Files.isExecutable(path) -> Result.NotExecutable(path)
            else -> Result.Ok(path)
        }
    }

    /**
     * Existence plus the executable bit don't guarantee a *usable*
     * LLDB. Two real-world failures, both Linux-verified:
     *
     *  1. The binary can't start: `liblldb` links against an exact
     *     Python runtime the OS must provide (e.g.
     *     `libpython3.10.so.1.0` - not shipped in platform-tools).
     *     The dynamic linker kills the process at exec, and CIDR's
     *     attach degrades into a session that never pauses at entry.
     *  2. The binary starts but its embedded Python can't bootstrap:
     *     Linux tarballs ship `dist-packages` where liblldb expects
     *     `site-packages`, so `import lldb` fails and every
     *     `script`-based command - including the gdbstub metadata
     *     read - is dead.
     *
     * One spawn checks both: run a `script` one-liner that must print
     * [PYTHON_PROBE_TOKEN] on a line of its own, with `PYTHONPATH` set
     * to the discovered package dirs - mirroring what
     * [GimletLLDBDriverConfiguration.createDriverCommandLine] injects
     * for the real LLDBFrontend, so the probe's verdict matches what
     * the session actually experienced. Invoked post-mortem on the
     * failure paths (never-paused session, blank metadata read) so
     * healthy attaches never pay for it. Returns `null` when the token
     * comes back; otherwise a one-line detail (the loader's error or
     * the Python traceback's error line).
     *
     * Echo trap: `lldb --batch -o <cmd>` echoes `(lldb) <cmd>` before
     * executing it, so the raw output contains the command text even
     * when the script dies before printing. Two defenses: the command
     * assembles the token from two halves (the echo can never contain
     * the full sentinel), and detection requires the token standalone
     * on its own line rather than as a substring.
     */
    fun verifyLoads(path: Path): String? {
        return try {
            val builder = ProcessBuilder(
                path.toString(),
                "--batch",
                "-o",
                "script import lldb; print(\"$PROBE_TOKEN_HEAD\" + \"$PROBE_TOKEN_TAIL\")",
            ).redirectErrorStream(true)
            val packageDirs = discoverPythonPackageDirs(path)
            if (packageDirs.isNotEmpty()) {
                val existing = builder.environment()["PYTHONPATH"]
                builder.environment()["PYTHONPATH"] =
                    (packageDirs.map { it.toString() } + listOfNotNull(existing))
                        .joinToString(File.pathSeparator)
            }
            val process = builder.start()
            // Drain before waitFor (avoids pipe-buffer deadlock); the
            // scheduled kill bounds the read if the binary wedges -
            // destroyForcibly closes the pipe, unblocking readText.
            val killSwitch = CompletableFuture.runAsync(
                { process.destroyForcibly() },
                CompletableFuture.delayedExecutor(
                    LOAD_CHECK_TIMEOUT.inWholeMilliseconds,
                    TimeUnit.MILLISECONDS,
                ),
            )
            val output = try {
                process.inputStream.bufferedReader().use { it.readText() }
            } catch (_: Throwable) {
                ""
            }
            val exited = process.waitFor(LOAD_CHECK_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            killSwitch.cancel(false)
            when {
                !exited -> {
                    process.destroyForcibly()
                    "did not respond within ${LOAD_CHECK_TIMEOUT.inWholeSeconds}s"
                }
                output.lineSequence().any { it.trim() == PYTHON_PROBE_TOKEN } -> null
                else -> failureDetail(output) ?: "exited with code ${process.exitValue()}"
            }
        } catch (t: Throwable) {
            t.message ?: t::class.java.simpleName
        }
    }

    /**
     * The most informative line of a failed probe's output: the last
     * error-ish line (a traceback's final `SomethingError: ...` line,
     * or the dynamic linker's `error while loading ...`), falling back
     * to the first non-blank line.
     */
    private fun failureDetail(output: String): String? =
        output.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.contains("error", ignoreCase = true) }
            ?.take(300)
            ?: firstMeaningfulLine(output)

    /**
     * Notification text for a [verifyLoads] failure, with a targeted
     * remediation hint per failure class - missing OS Python runtime
     * and missing lldb Python modules are the two known Linux causes.
     */
    fun loadFailureMessage(path: Path, detail: String): String {
        val hint = when {
            detail.contains("libpython") ->
                "platform-tools LLDB needs the OS to provide that exact Python " +
                    "runtime. On Ubuntu 22.04 run `sudo apt install libpython3.10`; " +
                    "on newer Ubuntu/Debian releases install python3.10 from the " +
                    "deadsnakes PPA so `libpython3.10.so.1.0` is on the loader path."
            detail.contains("No module named") ->
                "platform-tools' LLDB Python modules were not found - expected " +
                    "under `llvm/lib/python*/[site|dist]-packages/lldb` in the " +
                    "platform-tools installation. Reinstall platform-tools; if " +
                    "the problem persists, please file a Gimlet issue."
            else ->
                "A shared-library dependency is likely missing - run `ldd $path` " +
                    "(Linux) or `otool -L $path` (macOS) to inspect."
        }
        return "Platform-tools LLDB at $path failed its startup check:\n" +
            "$detail\n\n$hint"
    }

    internal fun firstMeaningfulLine(output: String): String? =
        output.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(300)

    fun discoverPythonPackageDirs(lldbBinary: Path): List<Path> {
        val libDir = lldbBinary.parent?.parent?.resolve("lib") ?: return emptyList()
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

    private val LOAD_CHECK_TIMEOUT: Duration = 10.seconds

    // Kept as two halves so the assembled sentinel never appears in
    // the probe command itself (see the echo trap in [verifyLoads]).
    private const val PROBE_TOKEN_HEAD = "gimlet-"
    private const val PROBE_TOKEN_TAIL = "python-ok"
    private const val PYTHON_PROBE_TOKEN = PROBE_TOKEN_HEAD + PROBE_TOKEN_TAIL
}
