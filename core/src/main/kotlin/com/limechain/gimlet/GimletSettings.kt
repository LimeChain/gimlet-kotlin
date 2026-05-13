package com.limechain.gimlet

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Broadcast to UI consumers (tool window panel, status bar widget) when
 * the user applies a settings change. Carries no payload - subscribers
 * re-read [GimletSettings] state themselves. Project-scoped because
 * settings are project-scoped.
 */
internal fun interface GimletSettingsListener {
    fun settingsChanged()
}

/**
 * Project-level persistent configuration for Gimlet. Serialises to
 * `.idea/gimlet.xml` via the platform's `@State` mechanism.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "GimletSettings",
    storages = [Storage("gimlet.xml")],
)
internal class GimletSettings(private val project: Project) :
    SimplePersistentStateComponent<GimletSettings.InnerState>(InnerState()) {

    enum class ImportDecision { NotAsked, Imported, Dismissed }

    /**
     * Fired when the platform deserialises the XML back into our state -
     * covers direct edits to `.idea/gimlet.xml`, VCS pulls that change
     * it, and the "Reload from Disk" action. The initial load during
     * service construction also fires this; subscribers don't exist yet
     * at that point, so the publish is a no-op.
     *
     * The Settings-UI path (Apply / OK) does NOT go through here - it
     * mutates state in-place via Kotlin UI DSL bindings - so
     * [GimletConfigurable.apply] publishes separately.
     */
    override fun loadState(state: InnerState) {
        super.loadState(state)
        project.messageBus.syncPublisher(TOPIC).settingsChanged()
    }

    /**
     * Fired when the platform finds no `.idea/gimlet.xml` (fresh
     * project, never configured Gimlet). Symmetric with [loadState]
     * so that a future UI subscriber that wires up before the service
     * is first touched still gets a "settings are now resolved" signal.
     * On the panel + status bar widget paths this is effectively a no-op
     * - both subscribe lazily, after the first state read.
     */
    override fun noStateLoaded() {
        super.noStateLoaded()
        project.messageBus.syncPublisher(TOPIC).settingsChanged()
    }

    class InnerState : BaseState() {
        var tcpPort by property(DEFAULT_TCP_PORT)
        var platformToolsVersion by string(DEFAULT_PLATFORM_TOOLS_VERSION)
        var stopOnEntry by property(true)
        var importDecision by enum(ImportDecision.NotAsked)

        /**
         * Override for the platform-tools install root. When unset (null
         * or blank), defaults to `~/.cache/solana/v<version>/platform-tools/`.
         */
        var platformToolsPath by string()

        /**
         * Override for the SBPF trace directory containing `program_ids.map`.
         * Resolved against the project base when relative; absolute paths
         * pass through. Defaults to `<project>/target/sbf/trace/`.
         */
        var sbfTracePath by string()

        /**
         * Override for the artifacts directory containing the built `.so`
         * + `.so.debug` companions. Resolved against the project base when
         * relative; absolute paths pass through. Defaults to
         * `<project>/target/deploy/debug/`.
         */
        var artifactsPath by string()
    }

    companion object {
        @JvmField
        val TOPIC: Topic<GimletSettingsListener> =
            Topic.create("Gimlet settings changes", GimletSettingsListener::class.java)

        const val DEFAULT_TCP_PORT: Int = 1212
        const val DEFAULT_PLATFORM_TOOLS_VERSION: String = "1.54"

        /**
         * Acceptable range for the TCP port the SBPF gdbstub binds on.
         * Lower bound excludes the privileged port range (0-1023);
         * upper bound is the TCP cap. Shared by the Settings UI
         * validator and the orchestrator's defensive read-site check.
         */
        val VALID_TCP_PORT_RANGE: IntRange = 1024..65535

        /**
         * Minimum platform-tools version we support. sbpf gdbstub
         * support landed in 1.54; older releases lack the gdbstub
         * crate entirely.
         */
        const val MIN_PLATFORM_TOOLS_VERSION: String = "1.54"

        val DEFAULT_SBF_TRACE_RELATIVE: Path = Path.of("target", "sbf", "trace")
        val DEFAULT_ARTIFACTS_RELATIVE: Path = Path.of("target", "deploy", "debug")

        /** Matches `1.54`, `2.0`, etc. - major.minor only. */
        val VERSION_PATTERN: Regex = Regex("""^\d+\.\d+$""")

        fun getInstance(project: Project): GimletSettings = project.service()

        /**
         * Compares two `major.minor` version strings. Returns negative
         * if [a] < [b], zero if equal, positive if [a] > [b]. Throws
         * if either string isn't `\d+\.\d+`.
         */
        fun compareVersions(a: String, b: String): Int {
            val (aMajor, aMinor) = parseVersion(a)
            val (bMajor, bMinor) = parseVersion(b)
            return (aMajor - bMajor).takeIf { it != 0 } ?: (aMinor - bMinor)
        }

        private fun parseVersion(v: String): Pair<Int, Int> {
            require(VERSION_PATTERN.matches(v)) { "Not a major.minor version: $v" }
            val (major, minor) = v.split('.')
            return major.toInt() to minor.toInt()
        }

        /**
         * Walk every settings field and collect human-readable error
         * strings for anything that would make attach unusable. An
         * empty list means the configuration is valid. Mirrors the
         * per-field validators in [GimletConfigurable] so XML-direct
         * edits (which bypass the UI) can't sneak a bad value past us.
         *
         * Consumed by the tool window panel (renders a "configuration
         * error" state), [GimletStateMonitor] (forces IDLE while
         * invalid), and [GimletAttachOrchestrator] (defensive balloon
         * before starting a chain).
         */
        fun validate(state: InnerState): List<String> {
            val errors = mutableListOf<String>()

            val port = state.tcpPort
            if (port !in VALID_TCP_PORT_RANGE) {
                errors += "TCP port $port is out of range (must be " +
                    "${VALID_TCP_PORT_RANGE.first}-${VALID_TCP_PORT_RANGE.last})."
            }

            val version = state.platformToolsVersionOrDefault
            when {
                version.isBlank() -> errors += "Platform-tools version is required."
                !VERSION_PATTERN.matches(version) -> errors +=
                    "Platform-tools version '$version' is malformed " +
                        "(expected major.minor, e.g. 1.54)."
                compareVersions(version, MIN_PLATFORM_TOOLS_VERSION) < 0 -> errors +=
                    "Platform-tools version $version is below the minimum " +
                        "$MIN_PLATFORM_TOOLS_VERSION."
            }

            state.platformToolsPath?.takeIf { it.isNotBlank() }?.let { raw ->
                val path = tryPath(raw)
                when {
                    path == null -> errors += "Platform-tools path '$raw' is not a valid filesystem path."
                    !path.isAbsolute -> errors += "Platform-tools path must be absolute (got '$raw')."
                }
            }

            state.sbfTracePath?.takeIf { it.isNotBlank() }?.let { raw ->
                if (tryPath(raw) == null) {
                    errors += "SBF trace path '$raw' is not a valid filesystem path."
                }
            }

            state.artifactsPath?.takeIf { it.isNotBlank() }?.let { raw ->
                if (tryPath(raw) == null) {
                    errors += "Artifacts path '$raw' is not a valid filesystem path."
                }
            }

            return errors
        }

        private fun tryPath(raw: String): Path? = try {
            Path.of(raw)
        } catch (_: InvalidPathException) {
            null
        }
    }
}

/** Non-null read helper; falls back to the default if XML serialisation left the field null. */
internal val GimletSettings.InnerState.platformToolsVersionOrDefault: String
    get() = platformToolsVersion ?: GimletSettings.DEFAULT_PLATFORM_TOOLS_VERSION

/**
 * Resolves the platform-tools install root from the user override
 * (`platformToolsPath`) or falls back to the default location under
 * `~/.cache/solana/v<version>/platform-tools/`. Override paths must be
 * absolute (validated when written through the settings UI).
 */
internal fun GimletSettings.InnerState.resolvePlatformToolsRoot(): Path {
    platformToolsPath?.takeIf { it.isNotBlank() }?.let { return Path.of(it) }
    val home = System.getProperty("user.home")
    return Path.of(home, ".cache", "solana", "v$platformToolsVersionOrDefault", "platform-tools")
}

/**
 * Resolves the SBPF trace directory: the user override
 * (`sbfTracePath`) when set, else `<project>/target/sbf/trace/`.
 * Relative overrides are resolved against the project base; absolute
 * overrides pass through.
 */
internal fun GimletSettings.InnerState.resolveSbfTraceDir(project: Project): Path =
    resolveProjectRelative(sbfTracePath, project, GimletSettings.DEFAULT_SBF_TRACE_RELATIVE)

/**
 * Resolves the artifacts directory: the user override
 * (`artifactsPath`) when set, else `<project>/target/deploy/debug/`.
 * Same relative/absolute semantics as [resolveSbfTraceDir].
 */
internal fun GimletSettings.InnerState.resolveArtifactsDir(project: Project): Path =
    resolveProjectRelative(artifactsPath, project, GimletSettings.DEFAULT_ARTIFACTS_RELATIVE)

private fun resolveProjectRelative(override: String?, project: Project, defaultRelative: Path): Path {
    val basePath = project.basePath?.let(Path::of)
        ?: error("Gimlet: project has no basePath; cannot resolve relative path")
    override?.takeIf { it.isNotBlank() }?.let {
        val p = Path.of(it)
        return if (p.isAbsolute) p else basePath.resolve(p)
    }
    return basePath.resolve(defaultRelative)
}
