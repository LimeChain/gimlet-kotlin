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
}
