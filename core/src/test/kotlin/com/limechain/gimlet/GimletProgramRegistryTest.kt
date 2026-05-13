package com.limechain.gimlet

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Covers [GimletProgramRegistry.refresh] against a synthetic
 * `target/deploy/debug` + `target/sbf/trace/program_ids.map` layout.
 *
 * `project.basePath` is injected via the light fixture but isn't guaranteed
 * to exist on disk - we pass an override-style approach: compute the
 * expected on-disk layout rooted at `project.basePath` (creating missing
 * dirs) and verify the registry reads it back correctly.
 */
class GimletProgramRegistryTest : BasePlatformTestCase() {

    private lateinit var projectBase: Path
    private lateinit var deployDir: Path
    private lateinit var traceDir: Path

    override fun setUp() {
        super.setUp()
        val basePath = project.basePath
            ?: fail("project.basePath is null; cannot run registry tests against this fixture")
        projectBase = Path.of(basePath as String)
        Files.createDirectories(projectBase)
        deployDir = projectBase.resolve("target/deploy/debug").also { Files.createDirectories(it) }
        traceDir = projectBase.resolve("target/sbf/trace").also { Files.createDirectories(it) }
    }

    override fun tearDown() {
        try {
            // Leave the fixture's project dir in place (the fixture owns it)
            // but scrub our test files so consecutive tests don't see stale
            // artifacts.
            deployDir.toFile().deleteRecursively()
            traceDir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Write a fake `.so` with the given contents and optionally its `.debug` sibling. */
    private fun writeSoWithDebug(
        name: String,
        soBytes: ByteArray,
        includeDebug: Boolean = true,
    ): String {
        val so = deployDir.resolve("$name.so")
        Files.write(so, soBytes)
        if (includeDebug) {
            Files.write(deployDir.resolve("$name.so.debug"), soBytes + "DWARF-TRAILER".toByteArray())
        }
        return sha256Hex(soBytes)
    }

    private fun writeProgramIdMap(vararg entries: Pair<String, String>) {
        Files.writeString(
            traceDir.resolve("program_ids.map"),
            entries.joinToString("\n") { (id, hash) -> "$id=$hash" },
        )
    }

    private val registry get() = GimletProgramRegistry.getInstance(project)

    fun testEmptyWhenDeployDirMissing() {
        // Blow away the whole `target/` tree so resolveDeployDir returns null.
        projectBase.resolve("target").toFile().deleteRecursively()
        val artifacts = registry.refresh()
        assertTrue("expected no artifacts when deploy dir missing", artifacts.isEmpty())
    }

    fun testEmptyWhenProgramIdMapMissing() {
        // .so files exist but program_ids.map doesn't - registry has no way
        // to map sha → program_id, returns empty.
        writeSoWithDebug("primary", "bpf_program_primary_bytes".toByteArray())
        val artifacts = registry.refresh()
        assertTrue("expected no artifacts when program_ids.map missing", artifacts.isEmpty())
    }

    fun testHappyPathSingleArtifact() {
        val sha = writeSoWithDebug("primary", "bpf_program_primary_bytes".toByteArray())
        writeProgramIdMap("ProgramIdAlpha" to sha)
        val artifacts = registry.refresh()
        assertEquals(1, artifacts.size)
        val artifact = artifacts[0]
        assertEquals("ProgramIdAlpha", artifact.programId)
        assertEquals(sha, artifact.sha256)
        assertEquals(deployDir.resolve("primary.so"), artifact.soPath)
        assertEquals(deployDir.resolve("primary.so.debug"), artifact.debugPath)
    }

    fun testMultipleArtifactsMatched() {
        val shaA = writeSoWithDebug("primary", "AAA".toByteArray())
        val shaB = writeSoWithDebug("cpi_target", "BBB".toByteArray())
        writeProgramIdMap(
            "ProgA" to shaA,
            "ProgB" to shaB,
        )
        val artifacts = registry.refresh()
        assertEquals(2, artifacts.size)
        val byId = artifacts.associateBy { it.programId }
        assertEquals(shaA, byId.getValue("ProgA").sha256)
        assertEquals(shaB, byId.getValue("ProgB").sha256)
    }

    fun testMissingDebugCompanionProducesNullDebugPath() {
        val sha = writeSoWithDebug("primary", "content".toByteArray(), includeDebug = false)
        writeProgramIdMap("ProgA" to sha)
        val artifacts = registry.refresh()
        assertEquals(1, artifacts.size)
        assertNull(
            "debugPath should be null when .so.debug companion is absent",
            artifacts[0].debugPath,
        )
    }

    fun testOrphanProgramIdWithoutMatchingSoIsSkipped() {
        // map entry references a sha that no .so file hashes to → dropped
        // silently (no artifact emitted, no exception).
        writeSoWithDebug("primary", "present".toByteArray())
        writeProgramIdMap("OrphanProgramId" to "hash-with-no-matching-file")
        val artifacts = registry.refresh()
        assertTrue("orphan mapping should be dropped", artifacts.isEmpty())
    }

    fun testShaCollisionDropsEntry() {
        // Two .so files with identical bytes → same sha256. Registry's
        // sha-collision guard drops the entry (returns null for that
        // programId) rather than guess which file belongs to the id.
        val sharedBytes = "IDENTICAL".toByteArray()
        val sha = writeSoWithDebug("primary", sharedBytes)
        writeSoWithDebug("cpi_target", sharedBytes)
        writeProgramIdMap("ProgA" to sha)
        val artifacts = registry.refresh()
        assertTrue("colliding sha should drop the entry", artifacts.isEmpty())
    }

    fun testFindByProgramIdReturnsTheRightArtifact() {
        val sha = writeSoWithDebug("primary", "bytes".toByteArray())
        writeProgramIdMap("ProgA" to sha)
        registry.refresh()
        val found = registry.findByProgramId("ProgA")
        assertNotNull(found)
        assertEquals("ProgA", found!!.programId)
        assertNull(registry.findByProgramId("ProgMissing"))
    }

    fun testGetArtifactsReturnsSnapshotOfLastRefresh() {
        val shaFirst = writeSoWithDebug("primary", "first".toByteArray())
        writeProgramIdMap("ProgA" to shaFirst)
        val firstSnapshot = registry.refresh()
        assertEquals(1, firstSnapshot.size)
        assertEquals(1, registry.getArtifacts().size)

        // Overwrite with a second build and refresh.
        val shaSecond = writeSoWithDebug("primary", "second".toByteArray())
        writeProgramIdMap("ProgA" to shaSecond)
        val secondSnapshot = registry.refresh()
        assertEquals(1, secondSnapshot.size)
        assertEquals(shaSecond, secondSnapshot[0].sha256)
        assertEquals(shaSecond, registry.getArtifacts()[0].sha256)
    }
}
