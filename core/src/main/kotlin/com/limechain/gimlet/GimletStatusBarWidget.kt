package com.limechain.gimlet

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.event.MouseEvent

/**
 * Status bar item showing Gimlet's current attach state - three labels
 * driven by [GimletStateMonitor]'s MessageBus topic.
 *
 * Click behaviour depends on state:
 * - IDLE  → open Gimlet settings (most likely cause is a port mismatch).
 * - READY → focus the Gimlet tool window so the user can hit Attach.
 * - ATTACHED → focus the Gimlet tool window where Stop lives.
 */
internal class GimletStatusBarWidget(
    private val project: Project,
) : StatusBarWidget, StatusBarWidget.TextPresentation {

    private var statusBar: StatusBar? = null
    private val monitor = GimletStateMonitor.getInstance(project)
    private val connection = project.messageBus.connect()

    init {
        connection.subscribe(GimletStateMonitor.TOPIC, GimletStateListener {
            // Re-render via the platform's status bar API. updateWidget is
            // EDT-only.
            ApplicationManager.getApplication().invokeLater {
                statusBar?.updateWidget(ID())
            }
        })
        monitor.setObserved(true)
    }

    override fun ID(): String = WIDGET_ID

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
    }

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getText(): String = when (monitor.state) {
        GimletState.ATTACHED -> "Gimlet: Attached"
        GimletState.READY    -> "Gimlet: Ready"
        GimletState.IDLE     -> "Gimlet: Idle"
    }

    override fun getTooltipText(): String {
        val port = GimletSettings.getInstance(project).state.tcpPort
        return when (monitor.state) {
            GimletState.ATTACHED -> "Gimlet debug session live on port $port. Click to focus the Gimlet tool window."
            GimletState.READY    -> "Port $port is bound. Click to attach via the Gimlet tool window."
            GimletState.IDLE     -> "No gdbstub on port $port. Run a test to begin. Click to open Gimlet settings."
        }
    }

    override fun getAlignment(): Float = Component.LEFT_ALIGNMENT

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        when (monitor.state) {
            GimletState.IDLE -> ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, GimletConfigurable::class.java)
            GimletState.READY,
            GimletState.ATTACHED -> ToolWindowManager.getInstance(project)
                .getToolWindow(TOOL_WINDOW_ID)
                ?.show()
        }
    }

    override fun dispose() {
        monitor.setObserved(false)
        connection.disconnect()
        statusBar = null
    }

    companion object {
        const val WIDGET_ID: String = "Gimlet.StatusBar"
        // The tool window registers itself under this id in phase 4. The
        // status bar widget references it ahead of time so click-to-focus
        // wires up cleanly once both ship.
        const val TOOL_WINDOW_ID: String = "Gimlet"
    }
}
