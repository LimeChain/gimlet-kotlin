package com.limechain.gimlet

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * Covers the three branches of [LldbPathResolver.Result] by driving
 * `user.home` at a controlled temp directory and populating / omitting the
 * expected lldb binary on disk.
 */
class LldbPathResolverTest : BasePlatformTestCase() {

    private lateinit var fakeHome: Path
    private var originalUserHome: String? = null

    override fun setUp() {
        super.setUp()
        fakeHome = Files.createTempDirectory("gimlet-lldb-home-")
        originalUserHome = System.getProperty("user.home")
        System.setProperty("user.home", fakeHome.toString())
    }

    override fun tearDown() {
        try {
            originalUserHome?.let { System.setProperty("user.home", it) }
            fakeHome.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    /** Compute where LldbPathResolver will look for a given version. */
    private fun expectedPathFor(version: String): Path =
        fakeHome.resolve(".cache/solana/v$version/platform-tools/llvm/bin/lldb")

    private fun setPlatformToolsVersion(version: String) {
        GimletSettings.getInstance(project).state.platformToolsVersion = version
    }

    fun testMissingWhenBinaryDoesNotExist() {
        setPlatformToolsVersion("1.54")
        val result = LldbPathResolver.resolve(project)
        assertTrue("expected Missing, got $result", result is LldbPathResolver.Result.Missing)
        assertEquals(expectedPathFor("1.54"), result.expectedPath)
    }

    fun testNotExecutableWhenFileExistsButLacksExecBit() {
        setPlatformToolsVersion("1.54")
        val path = expectedPathFor("1.54")
        Files.createDirectories(path.parent)
        Files.writeString(path, "#!/bin/sh\necho fake\n")
        Files.setPosixFilePermissions(path, setOf(PosixFilePermission.OWNER_READ))
        val result = LldbPathResolver.resolve(project)
        assertTrue("expected NotExecutable, got $result", result is LldbPathResolver.Result.NotExecutable)
        assertEquals(path, result.expectedPath)
    }

    fun testOkWhenBinaryIsExecutable() {
        setPlatformToolsVersion("1.54")
        val path = expectedPathFor("1.54")
        Files.createDirectories(path.parent)
        Files.writeString(path, "#!/bin/sh\necho fake\n")
        Files.setPosixFilePermissions(
            path,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
        val result = LldbPathResolver.resolve(project)
        assertTrue("expected Ok, got $result", result is LldbPathResolver.Result.Ok)
        assertEquals(path, result.expectedPath)
    }

    fun testUsesCurrentPlatformToolsVersionFromSettings() {
        setPlatformToolsVersion("1.99")
        val result = LldbPathResolver.resolve(project)
        assertEquals(expectedPathFor("1.99"), result.expectedPath)
    }

    fun testFallsBackToDefaultWhenVersionIsNull() {
        // SimplePersistentStateComponent can store null for the string
        // property; platformToolsVersionOrDefault should then substitute
        // the hardcoded default (1.54 at the time of writing).
        GimletSettings.getInstance(project).state.platformToolsVersion = null
        val result = LldbPathResolver.resolve(project)
        assertEquals(
            expectedPathFor(GimletSettings.DEFAULT_PLATFORM_TOOLS_VERSION),
            result.expectedPath,
        )
    }

    // ---- verifyLoads ------------------------------------------------

    /** Writes an executable fake lldb script at [path]. */
    private fun writeScript(path: Path, body: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, "#!/bin/sh\n$body\n")
        Files.setPosixFilePermissions(
            path,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
    }

    fun testVerifyLoadsPassesWhenProbeTokenComesBack() {
        val path = expectedPathFor("1.54")
        // A healthy lldb echoes the full probe command (token in two
        // halves), then the executed print emits the assembled token
        // on its own line.
        writeScript(
            path,
            "echo '(lldb) script import lldb; print(\"gimlet-\" + \"python-ok\")'\n" +
                "echo 'gimlet-python-ok'\n" +
                "exit 0",
        )
        assertNull(LldbPathResolver.verifyLoads(path))
    }

    fun testVerifyLoadsRejectsCommandEchoWithoutExecution() {
        val path = expectedPathFor("1.54")
        // Regression for the echo trap: `lldb --batch -o <cmd>` echoes
        // the command line before executing it, so output can contain
        // the assembled token as a substring even when the script dies
        // before printing. Only a standalone token line counts.
        writeScript(
            path,
            "echo '(lldb) script import lldb; print(\"gimlet-python-ok\")'\n" +
                "echo 'Traceback (most recent call last):'\n" +
                "echo \"ModuleNotFoundError: No module named 'lldb'\"\n" +
                "exit 0",
        )
        val detail = LldbPathResolver.verifyLoads(path)
        assertNotNull("echoed-but-not-executed probe must fail", detail)
        assertTrue(
            "detail should carry the traceback's error line, got: $detail",
            detail!!.contains("No module named"),
        )
    }

    fun testVerifyLoadsSurfacesLoaderError() {
        // The exact failure shape of a missing libpython on Linux: the
        // dynamic linker prints to stderr and the process dies with 127
        // before main() ever runs.
        val path = expectedPathFor("1.54")
        writeScript(
            path,
            "echo 'lldb: error while loading shared libraries: " +
                "libpython3.10.so.1.0: cannot open shared object file' 1>&2\nexit 127",
        )
        val detail = LldbPathResolver.verifyLoads(path)
        assertNotNull("loader failure must be reported", detail)
        assertTrue("detail should carry the loader line, got: $detail", detail!!.contains("libpython3.10.so.1.0"))
    }

    fun testVerifyLoadsSurfacesDeadPythonInterpreter() {
        // The dist-packages/site-packages mismatch shape: lldb starts
        // fine (exit 0) but the script command tracebacks and the
        // token never appears.
        val path = expectedPathFor("1.54")
        writeScript(
            path,
            "echo 'Traceback (most recent call last):'\n" +
                "echo '  File \"<string>\", line 1, in <module>'\n" +
                "echo \"ModuleNotFoundError: No module named 'lldb'\"\n" +
                "exit 0",
        )
        val detail = LldbPathResolver.verifyLoads(path)
        assertNotNull("dead interpreter must be reported", detail)
        assertTrue(
            "detail should carry the traceback's error line, got: $detail",
            detail!!.contains("No module named"),
        )
    }

    fun testVerifyLoadsReportsUnrunnableBinary() {
        val path = expectedPathFor("1.54")
        // Exists but is not a program the OS can exec.
        Files.createDirectories(path.parent)
        Files.writeString(path, "not a binary")
        assertNotNull(LldbPathResolver.verifyLoads(path))
    }

    fun testLoadFailureMessageHintsAtLibpython() {
        val message = LldbPathResolver.loadFailureMessage(
            Path.of("/opt/lldb"),
            "error while loading shared libraries: libpython3.10.so.1.0: cannot open shared object file",
        )
        assertTrue("libpython failures should carry the install hint", message.contains("libpython3.10"))
        assertTrue(message.contains("apt install"))
    }

    fun testLoadFailureMessageHintsAtMissingLldbModules() {
        val message = LldbPathResolver.loadFailureMessage(
            Path.of("/opt/lldb"),
            "ModuleNotFoundError: No module named 'lldb'",
        )
        assertTrue(
            "missing-module failures should point at the packages dir",
            message.contains("[site|dist]-packages"),
        )
        assertTrue(message.contains("Reinstall platform-tools"))
    }

    fun testLoadFailureMessageFallsBackToGenericHint() {
        val message = LldbPathResolver.loadFailureMessage(Path.of("/opt/lldb"), "Segmentation fault")
        assertTrue("non-python failures should suggest dependency inspection", message.contains("ldd"))
    }

    fun testFirstMeaningfulLineSkipsBlanksAndTrims() {
        assertEquals("boom", LldbPathResolver.firstMeaningfulLine("\n\n   boom   \nrest"))
        assertNull(LldbPathResolver.firstMeaningfulLine("\n \n"))
    }
}
