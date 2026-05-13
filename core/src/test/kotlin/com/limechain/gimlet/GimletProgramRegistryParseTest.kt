package com.limechain.gimlet

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pure-logic tests for `GimletProgramRegistry.Companion.parseProgramIdMap`.
 * Plain JUnit 4 - no platform fixture needed since the parser only
 * touches the filesystem and returns a Map.
 */
class GimletProgramRegistryParseTest {

    private lateinit var tempDir: Path

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("gimlet-registry-parse-")
    }

    @After
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    private fun writeMap(contents: String): Path {
        val file = tempDir.resolve("program_ids.map")
        Files.writeString(file, contents)
        return file
    }

    @Test
    fun `happy path parses two entries`() {
        val file = writeMap(
            """
            11157t3sqMV725NVRLrVQbAu98Jjfk1uCKehJnXXQs=abc123
            1117mWrzzrZr312ebPDHu8tbfMwFNvCvMbr6WepCNG=def456
            """.trimIndent()
        )
        val parsed = GimletProgramRegistry.parseProgramIdMap(file)
        assertEquals(2, parsed.size)
        assertEquals("abc123", parsed["11157t3sqMV725NVRLrVQbAu98Jjfk1uCKehJnXXQs"])
        assertEquals("def456", parsed["1117mWrzzrZr312ebPDHu8tbfMwFNvCvMbr6WepCNG"])
    }

    @Test
    fun `missing file returns empty map`() {
        val parsed = GimletProgramRegistry.parseProgramIdMap(tempDir.resolve("does-not-exist.map"))
        assertTrue(parsed.isEmpty())
    }

    @Test
    fun `blank lines and whitespace are tolerated`() {
        val file = writeMap(
            """
            |
            |  abc=xyz
            |
            |  def=uvw
            |
            """.trimMargin()
        )
        val parsed = GimletProgramRegistry.parseProgramIdMap(file)
        assertEquals(mapOf("abc" to "xyz", "def" to "uvw"), parsed)
    }

    @Test
    fun `comment lines starting with hash are skipped`() {
        val file = writeMap(
            """
            # this is a comment
            # another=comment
            valid=12345
            """.trimIndent()
        )
        val parsed = GimletProgramRegistry.parseProgramIdMap(file)
        assertEquals(mapOf("valid" to "12345"), parsed)
    }

    @Test
    fun `lines without equals are skipped`() {
        val file = writeMap(
            """
            no-equals-sign
            valid=ok
            """.trimIndent()
        )
        val parsed = GimletProgramRegistry.parseProgramIdMap(file)
        assertEquals(mapOf("valid" to "ok"), parsed)
    }

    @Test
    fun `lines with equals at the very start are skipped`() {
        // Leading `=` means there's no key - parser rejects it via the
        // `eq <= 0` guard. Guards against corrupted / malformed maps.
        val file = writeMap(
            """
            =orphan-value
            valid=ok
            """.trimIndent()
        )
        val parsed = GimletProgramRegistry.parseProgramIdMap(file)
        assertEquals(mapOf("valid" to "ok"), parsed)
    }

    @Test
    fun `lines with equals at the very end are skipped`() {
        // Trailing `=` means there's no value - parser rejects it via the
        // `eq == line.length - 1` guard.
        val file = writeMap(
            """
            key-without-value=
            valid=ok
            """.trimIndent()
        )
        val parsed = GimletProgramRegistry.parseProgramIdMap(file)
        assertEquals(mapOf("valid" to "ok"), parsed)
    }

    @Test
    fun `empty file returns empty map`() {
        val file = writeMap("")
        val parsed = GimletProgramRegistry.parseProgramIdMap(file)
        assertTrue(parsed.isEmpty())
    }

    @Test
    fun `later entry for same key overwrites earlier`() {
        // Not a realistic scenario (the SBPF VM doesn't emit dupes) but
        // verifies we don't accidentally preserve stale entries.
        val file = writeMap(
            """
            key=first
            key=second
            """.trimIndent()
        )
        val parsed = GimletProgramRegistry.parseProgramIdMap(file)
        assertEquals(mapOf("key" to "second"), parsed)
    }
}
