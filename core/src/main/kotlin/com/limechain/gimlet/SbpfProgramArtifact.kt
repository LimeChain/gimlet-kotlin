package com.limechain.gimlet

import java.nio.file.Path

/**
 * One Solana program found in the project's `target/deploy/debug/` output.
 *
 * @property programId  Base58 program id, as declared in `program_ids.map`.
 *                      Matches the pubkey the SBPF VM reports via
 *                      `solana_save_output process plugin packet monitor metadata`.
 * @property soPath     Absolute path to the compiled `.so` file.
 * @property debugPath  Absolute path to the `.so.debug` companion (with DWARF
 *                      symbols). `null` if the companion is missing; in that
 *                      case the orchestrator surfaces a build-command hint.
 * @property sha256     Lowercase hex sha256 of the `.so` bytes. Used to
 *                      cross-reference against the hash in `program_ids.map`.
 */
internal data class SbpfProgramArtifact(
    val programId: String,
    val soPath: Path,
    val debugPath: Path?,
    val sha256: String,
)
