package com.limechain.gimlet

import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves which `lldb` binary the attach should drive.
 *
 * The plugin owns the LLDB path: the user only has to install
 * platform-tools via the Solana CLI, and we compute the binary path
 * from `platformToolsVersion`. RustRover's bundled LLDB can't parse
 * `e_machine = EM_BPF` (rejects symbol loads with `error: unsupported
 * module`), so we always point CIDR at the platform-tools LLDB fork.
 * The override flows through [GimletLLDBDriverConfiguration]'s
 * `setCustomLLDBPath` call, which fires CIDR's `initCustomMacLldb` /
 * `initCustomLinuxLldb` hooks at command-line construction.
 */
internal object LldbPathResolver {

    sealed interface Result {
        val expectedPath: Path

        data class Ok(override val expectedPath: Path) : Result
        data class Missing(override val expectedPath: Path) : Result
        data class NotExecutable(override val expectedPath: Path) : Result
    }

    fun resolve(project: Project): Result {
        val settings = GimletSettings.getInstance(project).state
        val path = settings.resolvePlatformToolsRoot().resolve(Path.of("llvm", "bin", "lldb"))
        return when {
            !Files.exists(path) -> Result.Missing(path)
            !Files.isExecutable(path) -> Result.NotExecutable(path)
            else -> Result.Ok(path)
        }
    }
}
