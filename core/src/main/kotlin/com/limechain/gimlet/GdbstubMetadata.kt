package com.limechain.gimlet

/**
 * Parsed `program_id=…;cpi_level=…;caller=…` line that
 * `solana_save_output process plugin packet monitor metadata` writes
 * to its output file.
 */
internal data class GdbstubMetadata(
    val programId: String,
    val cpiLevel: Int,
    val caller: String?,
) {
    companion object {
        // Conservative base58 shape check - 32-44 chars, no 0/O/I/l. Not
        // a full decode, just rejects obvious garbage from a partial /
        // truncated read.
        private val PUBKEY_SHAPE = Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$")

        /**
         * Parse the file content. `solana_save_output` writes the LLDB
         * command's full output, often with a trailing decoration line
         * - we take the first non-empty line. Returns null if
         * `program_id` is missing or malformed.
         */
        fun parse(raw: String): GdbstubMetadata? {
            val firstLine = raw.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                ?: return null
            val fields = buildMap {
                for (token in firstLine.split(';')) {
                    val trimmed = token.trim()
                    if (trimmed.isEmpty()) continue
                    val eq = trimmed.indexOf('=')
                    if (eq <= 0) continue
                    put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim())
                }
            }
            val programId = fields["program_id"]?.takeIf { PUBKEY_SHAPE.matches(it) }
                ?: return null
            val cpiLevel = fields["cpi_level"]?.toIntOrNull() ?: 0
            val caller = fields["caller"]?.takeIf { PUBKEY_SHAPE.matches(it) }
            return GdbstubMetadata(programId, cpiLevel, caller)
        }
    }
}
