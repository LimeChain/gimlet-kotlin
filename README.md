# Gimlet

Gimlet is a RustRover plugin that makes Solana program debugging seamless, automated, and fully integrated into the JetBrains experience.

<!-- Plugin description -->
**Gimlet** lets you step-debug Solana SBPF programs from RustRover.
Run your debug-enabled test from a terminal; once sbpf's gdbstub binds
the port, the **Gimlet** tool window flips to *Ready* - click **Attach
Debugger** and the plugin attaches the platform-tools LLDB with the
matching `.so.debug` symbols, pausing at your breakpoint. Cross-program
invocations (CPIs) auto-attach new concurrent debug sessions for the
inner programs - breakpoints in any program in the chain hit with
correct source resolution.

![Gimlet](https://raw.githubusercontent.com/LimeChain/gimlet-kotlin/main/images/gimlet.png)
<!-- Plugin description end -->

---

## Table of Contents

- [Gimlet](#gimlet)
  - [Table of Contents](#table-of-contents)
  - [Prerequisites](#prerequisites)
  - [Introduction](#introduction)
  - [Getting Started with Gimlet](#getting-started-with-gimlet)
    - [1. Configuration](#1-configuration)
    - [2. Setup Steps](#2-setup-steps)
  - [Troubleshooting](#troubleshooting)
    - [No artifacts found](#no-artifacts-found)
    - [Platform-tools](#platform-tools)
    - [Permission Denied When Trying to Debug a Program](#permission-denied-when-trying-to-debug-a-program)
    - [Python Issues](#python-issues)

---

## Prerequisites

Before using Gimlet, ensure you have the following tools installed:

| Tool             | Installation Command                                      | Notes                |
|------------------|-----------------------------------------------------------|----------------------|
| RustRover        | [JetBrains site](https://www.jetbrains.com/rust/)         | Version 2026.1+      |
| `solana-cli`     | [Solana Docs](https://solana.com/docs/intro/installation) | Use latest version   |
| `platform-tools` | [Solana Docs](https://solana.com/docs/intro/installation) | Use versions >= 1.54 |

The plugin **doesn't** require any IDE-side toolchain setup - it computes the LLDB path from the platform-tools version in Settings.

---

## Introduction

When the `sbpf-debugger` feature is enabled in a Solana testing framework - currently [Mollusk](https://github.com/anza-xyz/mollusk/pull/229) (available in [mollusk-svm v0.13.0](https://crates.io/crates/mollusk-svm/0.13.0) or higher) and [LiteSVM](https://github.com/LiteSVM/litesvm/pull/354) (available in [litesvm v0.13.0](https://crates.io/crates/litesvm/0.13.0) or higher), with surfpool on the way (pending alignment with Agave 4.0) - each instruction typically spins up a VM that, if both `SBF_DEBUG_PORT` and `SBF_TRACE_DIR` are set, listens on that TCP port via a gdbstub. Gimlet connects to this port using the **TCP port** setting (which must match `SBF_DEBUG_PORT`) and launches `lldb` with a special library provided by the Solana platform-tools, setting up the symbols needed to load and debug ELF files (your compiled Solana programs). CPI (Cross-Program Invocation) debugging is supported as well.

> **Note:** Gimlet currently works with the Solana custom toolchain, which supports dynamic stack frames - a requirement when building without optimizations and with full debug information. In the future, the stack frame size will be configurable for debug builds, dropping the need for dynamic stack frames. We're open to adding upstream eBPF support as well, provided the upstream tooling gains the same stack frame configurability needed for debugging.

---

## Getting Started with Gimlet

Gimlet makes debugging Solana programs inside RustRover effortless. Follow these steps to get started:

### 1. Configuration

Gimlet stores per-project settings in `.idea/gimlet.xml` and exposes them at **Settings → Tools → Gimlet**. You can customize:
- The **TCP port** Gimlet watches and attaches to
- A different **platform-tools version**
- A **custom platform-tools install** (Nix, renamed dirs, custom toolchains, CI containers)
- Where Gimlet looks for the **SBF trace directory** and the **compiled artifacts**
- Whether the debugger **stops on entry** or runs straight to your first breakpoint

| Option                     | Default                                                     | Description                                                                                                |
|----------------------------|-------------------------------------------------------------|------------------------------------------------------------------------------------------------------------|
| **TCP port**               | `1212`                                                      | Port the gdbstub listens on. Must be in the unprivileged range (1024-65535) and match `SBF_DEBUG_PORT`.    |
| **Platform-tools version** | `1.54`                                                      | Solana platform-tools version. Major.minor only (e.g. `1.54`); minimum supported is `1.54`.                |
| **Stop on entry**          | `true`                                                      | Stop at program entry point; set to `false` to skip straight to the first breakpoint.                      |
| **SBF trace path**         | `target/sbf/trace`                                          | Project-relative path to the SBF trace directory containing `program_ids.map`.                             |
| **Artifacts path**         | `target/deploy/debug`                                       | Project-relative path to the directory holding your compiled `.so` programs (plus `.so.debug` companions). |
| **Platform-tools path**    | `~/.cache/solana/v{platformToolsVersion}/platform-tools/`   | Absolute path to your platform-tools root. Override when your toolchain lives outside the default cache.   |

> **Which key do I need?** Leave **Platform-tools path** unset for the default `cargo build-sbf` layout at `~/.cache/solana/v{platformToolsVersion}/platform-tools/`. Override it when your toolchain is in a non-standard location.

### 2. Setup Steps

1. **Open RustRover** in your Solana project folder.
2. **Install the Gimlet plugin** from the JetBrains Marketplace (**Settings → Plugins → Marketplace**, search for *Gimlet*).
3. **Build your program** with debug symbols (at the time of writing, this uses dynamic stack frames):
   ```sh
   RUSTFLAGS="-Copt-level=0 -C strip=none -C debuginfo=2" cargo build-sbf --tools-version v1.54 --debug --arch v1
   ```
4. **Run your test** with the debugger enabled (ensure your workspace's `Cargo.toml` enables the `sbpf-debugger` feature on `mollusk-svm` / `litesvm`):
   ```sh
   SBF_DEBUG_PORT=1212 SBF_TRACE_DIR=$PWD/target/sbf/trace cargo test
   ```
   `SBF_TRACE_DIR` is required: it tells the framework where to emit `program_ids.map`, which maps each program ID to the SHA-256 of its ELF. Gimlet uses this mapping to locate the matching debug symbols.
5. **Watch the Gimlet status-bar widget** (bottom-right of RustRover) for the gdbstub state:
   - `Gimlet: Idle` → no listener on the configured TCP port
   - `Gimlet: Ready` → port is bound; ready to attach
   - `Gimlet: Attached` → debug session is live
6. Once it shows **Ready**, **attach** by opening the **Gimlet tool window** (right sidebar) and clicking **Attach Debugger**.
7. Set breakpoints and step through your code using the standard RustRover debug controls. To disconnect, click **Stop Session** in the Gimlet tool window.

When your test triggers a CPI to another SBPF program, Gimlet auto-attaches a new concurrent debug session for each inner program; breakpoints in any program in the chain hit with correct source resolution.

---

## Troubleshooting

### No artifacts found

If Gimlet reports **"No Solana artifacts found under target/deploy/debug"**, rebuild your programs with debug symbols:

```sh
cargo build-sbf --tools-version v1.54 --debug --arch v1
```

The `.so` file alone isn't enough; Gimlet also needs the `.so.debug` companion that `--debug` produces.

### Platform-tools

We recommend using platform-tools version **v1.54**. To force-install the correct version inside your Rust project, run:

```sh
cargo build-sbf --tools-version v1.54 --debug --arch v1 --force-tools-install
```

If Gimlet reports **"platform-tools LLDB not found at …"**, verify the **Platform-tools version** setting in **Settings → Tools → Gimlet** matches the version you installed (the directory under `~/.cache/solana/v<version>/` should exist).

### Permission Denied When Trying to Debug a Program

Refer to the [Apple Developer Forum thread](https://forums.developer.apple.com/forums/thread/17452) for instructions on disabling debugging protection for macOS systems.

### Python Issues

If for some reason you're willing to debug by hand and `lldb` fails to start due to missing or mismatched Python, follow the upstream guide: [README_SOLANA_LLDB_PYTHON.md](https://github.com/anza-xyz/llvm-project/blob/solana-rustc/20.1-2025-02-13/lldb/docs/solana/README_SOLANA_LLDB_PYTHON.md).
