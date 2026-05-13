package com.limechain.gimlet.rustrover

import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.RunConfigurationWithSuppressedDefaultRunAction
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.options.SettingsEditorGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.rustrover.debugger.runners.core.RsCidrRunProfile
import com.jetbrains.cidr.execution.CidrCommandLineState
import org.jdom.Element
import java.nio.file.Path

/**
 * Parallel of `com.intellij.rustrover.debugger.runners.remote.RsRemoteRunConfiguration`
 * (the Rust plugin's remote-attach config in RustRover) but with the
 * driver-config selection lifted out of `RsDebuggerToolchainService`
 * and pushed into our [GimletCidrLauncher].
 *
 * **Why a parallel implementation, not a subclass.**
 * `RsRemoteRunConfiguration` is `public final` and its
 * `RemoteLauncher` is also final - neither can be subclassed, so we
 * reconstruct the equivalent flow in our own class. Spike memory at
 * `project_gimlet_spike_rr.md` documents the path-decision sequence
 * (Path C / A / B; only B was feasible).
 *
 * **Why we still go through CidrCommandLineState.** Spike Probe D
 * confirmed that the bypass route (XDebuggerManager.startSession with
 * a hand-rolled XDebugProcessStarter) hit a bogus
 * `target create "<source-port>"` issue inside LLDBFrontend that
 * disappeared once we routed the same construction through the
 * proper run-config flow. CidrCommandLineState + CidrLauncher's
 * `startDebugProcess` wrapper does the setup that suppresses the bug.
 *
 * Implementing `RsCidrRunProfile` keeps us on the Rust plugin's
 * blessed remote-debug code path. The interface is empty - a marker -
 * but the platform's program-runner registry filters by it.
 * `RunConfigurationWithSuppressedDefaultRunAction` hides the
 * non-debug Run action from the gutter; only Debug makes sense.
 */
class GimletRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String,
) : RunConfigurationBase<Element>(project, factory, name),
    RsCidrRunProfile,
    RunConfigurationWithSuppressedDefaultRunAction {

    /** Path to platform-tools' `lldb` binary. Set by the strategy before submission. */
    var lldbBinary: Path? = null

    /** Path to platform-tools' `liblldb.{dylib,so}`. Set by the strategy before submission. */
    var lldbFramework: Path? = null

    /** Local TCP port where sbpf's gdbstub is listening. Set by the strategy before submission. */
    var tcpPort: Int = DEFAULT_PORT

    // Return type is CommandLineState (not the broader RunProfileState
    // we'd inherit from RunConfiguration alone) because the
    // CidrRunProfile interface - pulled in via RsCidrRunProfile -
    // narrows the contract. CidrCommandLineState extends
    // CommandLineState, so the narrower return type is satisfied.
    override fun getState(executor: Executor, environment: ExecutionEnvironment): CommandLineState {
        val bin = lldbBinary
            ?: error("GimletRunConfiguration: lldbBinary not set before getState()")
        val framework = lldbFramework
            ?: error("GimletRunConfiguration: lldbFramework not set before getState()")
        // CidrCommandLineState is the platform-blessed wrapper that
        // knows how to drive a CidrLauncher. Public ctor (env, launcher);
        // no reflection needed.
        return CidrCommandLineState(environment, GimletCidrLauncher(project, bin, framework, tcpPort))
    }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> {
        // We never expose an editor - instances are constructed
        // programmatically by RustRoverAttachStrategy and submitted as
        // transient (isTemporary = true). Empty group satisfies the
        // API surface without any user-visible chrome.
        return SettingsEditorGroup()
    }

    companion object {
        const val DEFAULT_PORT: Int = 1212
    }
}

internal object GimletRunConfigurationType : ConfigurationType {
    private val factory = GimletRunConfigurationFactory(this)

    override fun getDisplayName(): String = "Gimlet Attach (RustRover)"
    override fun getConfigurationTypeDescription(): String =
        "Transient remote-debug configuration submitted by the Gimlet plugin to attach " +
            "platform-tools LLDB to a running sbpf gdbstub. Not user-editable."

    override fun getIcon(): javax.swing.Icon =
        IconLoader.getIcon("/icons/gimlet.svg", GimletRunConfigurationType::class.java)

    override fun getId(): String = "GimletAttach.RustRover"
    override fun getConfigurationFactories(): Array<ConfigurationFactory> = arrayOf(factory)

    fun factory(): ConfigurationFactory = factory
}

internal class GimletRunConfigurationFactory(
    type: ConfigurationType,
) : ConfigurationFactory(type) {
    override fun getId(): String = "GimletAttach.RustRover.Factory"

    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        GimletRunConfiguration(project, this, "Gimlet attach (template)")

    override fun isEditableInDumbMode(): Boolean = true
}
