package com.limechain.gimlet

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pure-filesystem tests for [LldbPathResolver.discoverPythonPackageDirs].
 * Layouts mirror real platform-tools tarballs: v1.54 ships
 * `python3.14/site-packages` on macOS and `python3.10/dist-packages`
 * on Linux, and the discovery must match both without knowing the
 * pinned Python version.
 */
class LldbPythonPackageDirsTest {

    private val root: Path = Files.createTempDirectory("gimlet-platform-tools-")

    @After
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    /** Creates `llvm/bin` + the given `llvm/lib/<dir>` entries; returns the lldb binary path. */
    private fun lldbWithLibDirs(vararg libSubDirs: String): Path {
        val bin = root.resolve("llvm").resolve("bin")
        Files.createDirectories(bin)
        for (sub in libSubDirs) {
            Files.createDirectories(root.resolve("llvm").resolve("lib").resolve(sub))
        }
        return bin.resolve("lldb")
    }

    @Test
    fun `finds linux dist-packages`() {
        val lldb = lldbWithLibDirs("python3.10/dist-packages")
        assertEquals(
            listOf(root.resolve("llvm/lib/python3.10/dist-packages")),
            LldbPathResolver.discoverPythonPackageDirs(lldb),
        )
    }

    @Test
    fun `finds macos site-packages`() {
        val lldb = lldbWithLibDirs("python3.14/site-packages")
        assertEquals(
            listOf(root.resolve("llvm/lib/python3.14/site-packages")),
            LldbPathResolver.discoverPythonPackageDirs(lldb),
        )
    }

    @Test
    fun `non-packages python subdirs are ignored`() {
        val lldb = lldbWithLibDirs("python3.10/dist-packages", "python3.10/lib-dynload")
        assertEquals(
            listOf(root.resolve("llvm/lib/python3.10/dist-packages")),
            LldbPathResolver.discoverPythonPackageDirs(lldb),
        )
    }

    @Test
    fun `empty when lib has no python dir`() {
        val lldb = lldbWithLibDirs("sbpf")
        assertTrue(LldbPathResolver.discoverPythonPackageDirs(lldb).isEmpty())
    }

    @Test
    fun `empty when lib dir is missing entirely`() {
        val bin = root.resolve("bare").resolve("bin")
        Files.createDirectories(bin)
        assertTrue(LldbPathResolver.discoverPythonPackageDirs(bin.resolve("lldb")).isEmpty())
    }
}
