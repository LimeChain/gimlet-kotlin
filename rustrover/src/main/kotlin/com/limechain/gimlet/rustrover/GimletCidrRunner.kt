package com.limechain.gimlet.rustrover

import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentDescriptor
import com.jetbrains.cidr.execution.CidrCommandLineState
import com.jetbrains.cidr.execution.CidrRunner

/**
 * Public-API runner for [GimletRunConfiguration].
 *
 * RustRover's runner narrows [CidrRunner] to its private
 * `RsCidrRunProfile` marker. Gimlet cannot implement that marker
 * without acquiring a forbidden private-module dependency, so it
 * registers its own runner for the single transient configuration.
 */
internal class GimletCidrRunner : CidrRunner() {
    override fun getRunnerId(): String = RUNNER_ID

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == DefaultDebugExecutor.EXECUTOR_ID &&
            profile is GimletRunConfiguration &&
            super.canRun(executorId, profile)

    override fun doExecute(
        state: RunProfileState,
        environment: ExecutionEnvironment,
    ): RunContentDescriptor? =
        startDebugDescriptor(state as CidrCommandLineState, environment, false)

    companion object {
        private const val RUNNER_ID = "GimletCidrRunner"
    }
}
