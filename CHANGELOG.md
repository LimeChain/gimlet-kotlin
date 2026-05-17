# Changelog

## [Unreleased]

## [0.1.2] - 2026-05-17

### Fixed

- "No artifacts found" error message. ([#1](https://github.com/LimeChain/gimlet-kotlin/pull/1))
- `stop on program entry = false` now auto-resumes. ([#1](https://github.com/LimeChain/gimlet-kotlin/pull/1))
- Marketplace compatibility scoped to RustRover only. ([#4](https://github.com/LimeChain/gimlet-kotlin/pull/4))

## [0.1.1] - 2026-05-13

### Added

- Source-level breakpoint debugging for Solana SBPF programs in
  RustRover. Run your debug-enabled test from a terminal; once sbpf's
  gdbstub binds the configured port, the **Gimlet** tool window flips
  to *Ready* - click **Attach Debugger** and the plugin attaches the
  platform-tools LLDB with the matching `.so.debug` symbols, pausing
  at your breakpoint. Threads, stack frames, variables, and the LLDB
  console all populate as expected.
- Cross-program invocation (CPI) debugging. Auto-attaches a new
  concurrent debug session for each program in a CPI chain - outer
  program, every inner level, and post-CPI code in any of them.
  Correct source resolution per program via `program_id` extraction
  from sbpf's stderr.
- Settings UI at **Settings → Tools → Gimlet** for TCP port,
  platform-tools version, stop-on-entry, and optional path overrides
  for platform-tools, the SBPF trace directory, and the artifacts
  directory.

### Requires

- RustRover 2026.1.1 or later (build number 261.23567+).
- Solana platform-tools v1.54 (installed via the Solana CLI).
- macOS or Linux.
