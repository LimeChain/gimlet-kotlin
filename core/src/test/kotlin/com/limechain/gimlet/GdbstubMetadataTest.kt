package com.limechain.gimlet

import com.limechain.gimlet.GdbstubMetadata.ReadOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [GdbstubMetadata.classify]. The three outcomes
 * drive different failure handling in the orchestrator: [ReadOutcome.Empty]
 * triggers a post-mortem toolchain probe, [ReadOutcome.Malformed]
 * surfaces the content itself.
 *
 * Pubkey fixtures are real values from an sbpf gdbstub session.
 */
class GdbstubMetadataTest {

    private val primaryId = "1113eKEmP3gmGaNeoKSVoYwPpyfTmrmizMbi1TqGj2"
    private val cpiTargetId = "1116HdUXm6NXY9kJbdszc6snexKvYiYSyiCR1vfYT3"

    private fun parsed(raw: String): GdbstubMetadata {
        val outcome = GdbstubMetadata.classify(raw)
        assertTrue("expected Parsed, got $outcome", outcome is ReadOutcome.Parsed)
        return (outcome as ReadOutcome.Parsed).metadata
    }

    // ---- Parsed -------------------------------------------------------

    @Test
    fun `full metadata line parses`() {
        val metadata = parsed("program_id=$cpiTargetId;cpi_level=1;caller=$primaryId")
        assertEquals(cpiTargetId, metadata.programId)
        assertEquals(1, metadata.cpiLevel)
        assertEquals(primaryId, metadata.caller)
    }

    @Test
    fun `cpi_level defaults to zero and caller to null`() {
        val metadata = parsed("program_id=$primaryId")
        assertEquals(0, metadata.cpiLevel)
        assertNull(metadata.caller)
    }

    @Test
    fun `malformed caller is dropped, not fatal`() {
        val metadata = parsed("program_id=$primaryId;cpi_level=0;caller=not-a-pubkey")
        assertNull(metadata.caller)
    }

    @Test
    fun `trailing decoration lines are ignored`() {
        // solana_save_output appends its own "[Saved to: ...]" line.
        val metadata = parsed("program_id=$primaryId;cpi_level=0\n\n[Saved to: /tmp/x.txt]")
        assertEquals(primaryId, metadata.programId)
    }

    @Test
    fun `leading blank lines are skipped`() {
        val metadata = parsed("\n   \nprogram_id=$primaryId")
        assertEquals(primaryId, metadata.programId)
    }

    // ---- Empty --------------------------------------------------------

    @Test
    fun `empty string classifies as Empty`() {
        assertEquals(ReadOutcome.Empty, GdbstubMetadata.classify(""))
    }

    @Test
    fun `whitespace-only classifies as Empty not Malformed`() {
        assertEquals(ReadOutcome.Empty, GdbstubMetadata.classify("\n  \n\t\n"))
    }

    // ---- Malformed ----------------------------------------------------

    @Test
    fun `garbage output classifies as Malformed with the line as preview`() {
        val outcome = GdbstubMetadata.classify("error: no metadata present")
        assertTrue("expected Malformed, got $outcome", outcome is ReadOutcome.Malformed)
        assertEquals("error: no metadata present", (outcome as ReadOutcome.Malformed).preview)
    }

    @Test
    fun `program_id failing the base58 shape check is Malformed`() {
        // 0, O, I, l are not base58 - a truncated/corrupted read must
        // not produce a Parsed result.
        val outcome = GdbstubMetadata.classify("program_id=0OIl0OIl0OIl0OIl0OIl0OIl0OIl0OIl;cpi_level=0")
        assertTrue("expected Malformed, got $outcome", outcome is ReadOutcome.Malformed)
    }

    @Test
    fun `preview is capped at 200 chars`() {
        val outcome = GdbstubMetadata.classify("x".repeat(500))
        assertEquals(200, (outcome as ReadOutcome.Malformed).preview.length)
    }
}
