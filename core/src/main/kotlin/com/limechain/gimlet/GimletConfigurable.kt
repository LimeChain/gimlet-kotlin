package com.limechain.gimlet

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.layout.ValidationInfoBuilder
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Settings page for Gimlet, mounted at Settings → Tools → Gimlet.
 * Kotlin UI DSL v2. Bound directly to [GimletSettings]'s state so the
 * platform handles isModified / apply / reset for us.
 */
internal class GimletConfigurable(
    private val project: Project,
) : BoundConfigurable(GimletBundle.message("settings.title")) {

    /**
     * Notify subscribers (tool window panel, etc.) after the user
     * applies a change. `super.apply()` is a no-op unless the panel
     * reports `isModified`, so we won't false-publish on
     * open-and-close.
     */
    override fun apply() {
        super.apply()
        project.messageBus.syncPublisher(GimletSettings.TOPIC).settingsChanged()
    }

    override fun createPanel(): DialogPanel {
        val state = GimletSettings.getInstance(project).state

        return panel {
            row(GimletBundle.message("settings.tcpPort")) {
                intTextField(range = GimletSettings.VALID_TCP_PORT_RANGE)
                    .bindIntText(state::tcpPort)
                    .comment(GimletBundle.message("settings.tcpPort.comment"))
            }
            row(GimletBundle.message("settings.platformToolsVersion")) {
                textField()
                    .bindText(
                        { state.platformToolsVersionOrDefault },
                        { state.platformToolsVersion = it.ifBlank { null } },
                    )
                    .comment(GimletBundle.message("settings.platformToolsVersion.comment"))
                    .validationOnApply { field ->
                        val raw = field.text.trim()
                        when {
                            raw.isEmpty() -> ValidationInfo(
                                GimletBundle.message("settings.error.platformToolsVersion.required"),
                                field,
                            )
                            !GimletSettings.VERSION_PATTERN.matches(raw) -> ValidationInfo(
                                GimletBundle.message("settings.error.platformToolsVersion.format"),
                                field,
                            )
                            GimletSettings.compareVersions(
                                raw,
                                GimletSettings.MIN_PLATFORM_TOOLS_VERSION,
                            ) < 0 -> ValidationInfo(
                                GimletBundle.message(
                                    "settings.error.platformToolsVersion.tooOld",
                                    raw,
                                    GimletSettings.MIN_PLATFORM_TOOLS_VERSION,
                                ),
                                field,
                            )
                            else -> null
                        }
                    }
            }
            row {
                checkBox(GimletBundle.message("settings.stopOnEntry"))
                    .bindSelected(state::stopOnEntry)
                    .comment(GimletBundle.message("settings.stopOnEntry.comment"))
            }
            row(GimletBundle.message("settings.platformToolsPath")) {
                textField()
                    .bindText(
                        { state.platformToolsPath.orEmpty() },
                        { state.platformToolsPath = it.ifBlank { null } },
                    )
                    .comment(GimletBundle.message("settings.platformToolsPath.comment"))
                    .validationOnApply { field ->
                        validateOptionalAbsolutePath(field.text)
                    }
            }
            row(GimletBundle.message("settings.sbfTracePath")) {
                textField()
                    .bindText(
                        { state.sbfTracePath.orEmpty() },
                        { state.sbfTracePath = it.ifBlank { null } },
                    )
                    .comment(GimletBundle.message("settings.sbfTracePath.comment"))
                    .validationOnApply { field ->
                        validateOptionalPath(field.text)
                    }
            }
            row(GimletBundle.message("settings.artifactsPath")) {
                textField()
                    .bindText(
                        { state.artifactsPath.orEmpty() },
                        { state.artifactsPath = it.ifBlank { null } },
                    )
                    .comment(GimletBundle.message("settings.artifactsPath.comment"))
                    .validationOnApply { field ->
                        validateOptionalPath(field.text)
                    }
            }
        }
    }

    /**
     * Common validator for path-override fields that require an
     * absolute path when set. Empty input means "use default" - fine.
     */
    private fun ValidationInfoBuilder.validateOptionalAbsolutePath(raw: String): ValidationInfo? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val path = try {
            Path.of(trimmed)
        } catch (_: InvalidPathException) {
            return error(GimletBundle.message("settings.error.platformToolsPath.invalid"))
        }
        return if (path.isAbsolute) null
        else error(GimletBundle.message("settings.error.platformToolsPath.notAbsolute"))
    }

    /**
     * Validator for path-override fields where both absolute and
     * project-relative paths are allowed. Only checks that the input
     * parses as a [Path]; existence isn't checked because the build
     * may not have created the directory yet.
     */
    private fun ValidationInfoBuilder.validateOptionalPath(raw: String): ValidationInfo? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return try {
            Path.of(trimmed)
            null
        } catch (_: InvalidPathException) {
            error(GimletBundle.message("settings.error.path.invalid"))
        }
    }
}
