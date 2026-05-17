package com.limechain.gimlet

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest

internal sealed class EmptyRegistryReason {
    object NoProjectBase : EmptyRegistryReason()
    data class ArtifactsDirMissing(val artifactsDir: Path) : EmptyRegistryReason()
    data class NoSoArtifacts(val artifactsDir: Path) : EmptyRegistryReason()
    data class TraceMapMissing(val mapFile: Path) : EmptyRegistryReason()
    // Covers empty file AND read errors (parseProgramIdMap returns empty on IOException).
    data class TraceMapEmpty(val mapFile: Path) : EmptyRegistryReason()
    data class NoMatches(val artifactsDir: Path, val mapFile: Path) : EmptyRegistryReason()
}

/**
 * Maps runtime Solana program ids to the compiled artifacts on disk.
 *
 * Walks `<project>/target/deploy/debug/` on demand (no VFS listener because
 * `target/` is in IntelliJ's default excluded-folders list - see design doc
 * §5.5 "VFS exclude trap"). Per-file sha256 is memoised across refreshes
 * keyed on `(path, mtime, size)` so the common case where nothing in the
 * deploy dir changed between debug sessions avoids rehashing.
 *
 * Public API is blocking file I/O. Call [refresh] from a background
 * thread / coroutine - never from the EDT.
 */
@Service(Service.Level.PROJECT)
internal class GimletProgramRegistry(private val project: Project) {

    private data class CachedHash(
        val sha: String,
        val mtime: FileTime,
        val size: Long,
    )

    private val hashCache = mutableMapOf<Path, CachedHash>()

    @Volatile
    private var artifacts: List<SbpfProgramArtifact> = emptyList()

    fun getArtifacts(): List<SbpfProgramArtifact> = artifacts

    fun findByProgramId(programId: String): SbpfProgramArtifact? =
        artifacts.firstOrNull { it.programId == programId }

    // Order matters: artifacts-side checks first - if the build is missing,
    // the trace map is irrelevant.
    // basePath guard mirrors resolveDeployDir() - resolve* would throw on null.
    fun diagnoseEmpty(): EmptyRegistryReason {
        if (project.basePath == null) return EmptyRegistryReason.NoProjectBase
        val settings = GimletSettings.getInstance(project).state
        val artifactsDir = settings.resolveArtifactsDir(project)
        val mapFile = settings.resolveSbfTraceDir(project).resolve("program_ids.map")
        return when {
            !Files.isDirectory(artifactsDir) -> EmptyRegistryReason.ArtifactsDirMissing(artifactsDir)
            listSoFiles(artifactsDir).isEmpty() -> EmptyRegistryReason.NoSoArtifacts(artifactsDir)
            !Files.isRegularFile(mapFile) -> EmptyRegistryReason.TraceMapMissing(mapFile)
            parseProgramIdMap(mapFile).isEmpty() -> EmptyRegistryReason.TraceMapEmpty(mapFile)
            else -> EmptyRegistryReason.NoMatches(artifactsDir, mapFile)
        }
    }

    /**
     * Re-scan the deploy dir. Returns the current [artifacts] snapshot.
     * Returns an empty list if the deploy dir doesn't exist yet (user
     * hasn't built the program), in which case callers should surface a
     * build-command hint.
     */
    @Synchronized
    fun refresh(): List<SbpfProgramArtifact> {
        val deployDir = resolveDeployDir()
        if (deployDir == null) {
            log.info("deploy dir does not exist; registry empty")
            artifacts = emptyList()
            return artifacts
        }

        // program_ids.map lives at <sbfTracePath>/program_ids.map (default
        // <project>/target/sbf/trace/), NOT under the artifacts dir. The
        // SBPF VM writes it there at debug-test time (not at build time);
        // an empty map just means the user hasn't run a debug-enabled
        // test yet.
        val settings = GimletSettings.getInstance(project).state
        val idToHash = parseProgramIdMap(
            settings.resolveSbfTraceDir(project).resolve("program_ids.map"),
        )
        val soFiles = listSoFiles(deployDir)
        val hashes = soFiles.mapNotNull { so ->
            val sha = cachedSha256(so) ?: return@mapNotNull null
            so to sha
        }.toMap()

        val newArtifacts = idToHash.mapNotNull { (programId, hash) ->
            val matching = hashes.entries.filter { it.value == hash }
            when (matching.size) {
                0 -> null
                1 -> {
                    val soPath = matching.single().key
                    val debugPath = soPath.resolveSibling(soPath.fileName.toString() + ".debug")
                        .takeIf { Files.isRegularFile(it) }
                    SbpfProgramArtifact(programId, soPath, debugPath, hash)
                }
                else -> {
                    log.warn(
                        "sha256 collision for program $programId: " +
                            matching.map { it.key.fileName }
                    )
                    null
                }
            }
        }

        artifacts = newArtifacts
        log.info("refreshed registry: ${newArtifacts.size} artifact(s) in $deployDir")
        return newArtifacts
    }

    private fun resolveDeployDir(): Path? {
        if (project.basePath == null) return null
        val settings = GimletSettings.getInstance(project).state
        return settings.resolveArtifactsDir(project).takeIf { Files.isDirectory(it) }
    }

    private fun listSoFiles(deployDir: Path): List<Path> =
        try {
            Files.list(deployDir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".so") }
                    .toList()
            }
        } catch (e: IOException) {
            log.warn("failed to list deploy dir $deployDir: ${e.message}")
            emptyList()
        }

    private fun cachedSha256(path: Path): String? =
        try {
            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
            val mtime = attrs.lastModifiedTime()
            val size = attrs.size()
            val hit = hashCache[path]
            if (hit != null && hit.mtime == mtime && hit.size == size) {
                hit.sha
            } else {
                val sha = computeSha256(path)
                hashCache[path] = CachedHash(sha, mtime, size)
                sha
            }
        } catch (e: IOException) {
            log.warn("failed to hash $path: ${e.message}")
            null
        }

    private fun computeSha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { stream ->
            val buffer = ByteArray(SHA_BUFFER_BYTES)
            while (true) {
                val n = stream.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val SHA_BUFFER_BYTES = 8 * 1024
        private val log = logger<GimletProgramRegistry>()

        fun getInstance(project: Project): GimletProgramRegistry = project.service()

        /**
         * Parses `program_ids.map` lines of the form `<programId>=<sha>`.
         * Split out as a package-level function so unit tests can exercise
         * it without a fake project.
         */
        internal fun parseProgramIdMap(mapFile: Path): Map<String, String> {
            if (!Files.isRegularFile(mapFile)) return emptyMap()
            val lines = try {
                Files.readAllLines(mapFile)
            } catch (e: IOException) {
                log.warn("failed to read $mapFile: ${e.message}")
                return emptyMap()
            }
            return buildMap {
                for (raw in lines) {
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val eq = line.indexOf('=')
                    if (eq <= 0 || eq == line.length - 1) continue
                    put(line.substring(0, eq).trim(), line.substring(eq + 1).trim())
                }
            }
        }
    }
}
