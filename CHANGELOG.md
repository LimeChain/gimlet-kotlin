# Changelog

## [Unreleased]

## [0.1.5] - 2026-07-02

- fix: replace lsof port probe with OSHI socket-table reads by @failfmi in https://github.com/LimeChain/gimlet-kotlin/pull/15
- chore(deps): bump gradle-wrapper from 9.6.0 to 9.6.1 by @dependabot[bot] in https://github.com/LimeChain/gimlet-kotlin/pull/14

## [0.1.4] - 2026-06-21

- docs: add debug-session screenshot in README by @failfmi
- docs: bump referenced mollusk-svm version to v0.13.0 by @failfmi
- ci(release): prefix release tags with v and CHANGELOG compare links by @failfmi
- chore(deps): bump JetBrains/qodana-action from 2026.1.0 to 2026.1.3 by @dependabot[bot] in https://github.com/LimeChain/gimlet-kotlin/pull/7
- chore(deps): bump org.jetbrains.qodana from 2026.1.0 to 2026.1.3 by @dependabot[bot] in https://github.com/LimeChain/gimlet-kotlin/pull/8
- chore(deps): bump actions/checkout from 6 to 7 by @dependabot[bot] in https://github.com/LimeChain/gimlet-kotlin/pull/9
- chore(deps): bump gradle-wrapper from 9.5.1 to 9.6.0 by @dependabot[bot] in https://github.com/LimeChain/gimlet-kotlin/pull/10
- docs: LiteSVM sbpf-debugger support by @failfmi in https://github.com/LimeChain/gimlet-kotlin/pull/11
- ci: re-enable strict plugin verification by @failfmi in https://github.com/LimeChain/gimlet-kotlin/pull/12
- @github-actions[bot] made their first contribution in https://github.com/LimeChain/gimlet-kotlin/pull/6
- @dependabot[bot] made their first contribution in https://github.com/LimeChain/gimlet-kotlin/pull/7

## [0.1.3] - 2026-05-19

### Changed

- chore: address qodana inspections by @failfmi in https://github.com/LimeChain/gimlet-kotlin/pull/5
- mark verifyPlugin job as advisory by @ERoydev in https://github.com/LimeChain/gimlet-kotlin/pull/2

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

[Unreleased]: https://github.com/LimeChain/gimlet-kotlin/compare/v0.1.5...HEAD
[0.1.5]: https://github.com/LimeChain/gimlet-kotlin/compare/v0.1.4...v0.1.5
[0.1.4]: https://github.com/LimeChain/gimlet-kotlin/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/LimeChain/gimlet-kotlin/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/LimeChain/gimlet-kotlin/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/LimeChain/gimlet-kotlin/commits/v0.1.1
