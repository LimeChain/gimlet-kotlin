package com.limechain.gimlet

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.net.URI
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

private val LOG = logger<GimletToolWindowPanel>()

/**
 * Side-panel UI for Gimlet. Shows the current state, a state-dependent
 * help message, and the action button that's relevant right now
 * (Attach when READY, Stop when ATTACHED).
 *
 * Subscribes to [GimletStateMonitor.TOPIC] and rerenders on state changes.
 * Registers as an observer on the monitor so polling kicks in whenever
 * the user has the panel open.
 */
internal class GimletToolWindowPanel(
    private val project: Project,
) : SimpleToolWindowPanel(/* vertical = */ true, /* borderless = */ true), Disposable {

    private val monitor = GimletStateMonitor.getInstance(project)
    private val connection = project.messageBus.connect(this)

    private val statusLabel = JBLabel()
    private val helpLabel = JBLabel().apply {
        setAllowAutoWrapping(true)
        verticalAlignment = JBLabel.TOP
    }
    private val attachButton = JButton("Attach Debugger").apply {
        addActionListener {
            GimletAttachOrchestrator.getInstance(project).attach()
        }
    }
    private val stopButton = JButton("Stop Session").apply {
        addActionListener {
            GimletAttachOrchestrator.getInstance(project).stopAll()
        }
    }

    init {
        setContent(buildContent())

        connection.subscribe(GimletStateMonitor.TOPIC, GimletStateListener { state ->
            ApplicationManager.getApplication().invokeLater { render(state) }
        })
        // Settings changes (notably the TCP port) don't trigger state
        // transitions, so the state-monitor subscription alone wouldn't
        // refresh the port shown in the help text. Re-render against
        // the current state when settings are applied.
        connection.subscribe(GimletSettings.TOPIC, GimletSettingsListener {
            ApplicationManager.getApplication().invokeLater { render(monitor.state) }
        })
        monitor.setObserved(true)
        render(monitor.state)
    }

    private fun buildContent(): JPanel {
        val root = JPanel(GridBagLayout()).apply {
            border = JBUI.Borders.empty(12)
        }
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            insets = Insets(0, 0, 8, 0)
        }

        root.add(statusLabel, gbc)

        gbc.gridy++
        gbc.insets = Insets(0, 0, 12, 0)
        root.add(helpLabel, gbc)

        gbc.gridy++
        val buttons = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(attachButton)
            add(Box.createHorizontalStrut(8))
            add(stopButton)
            add(Box.createHorizontalGlue())
        }
        root.add(buttons, gbc)

        gbc.gridy++
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        gbc.insets = Insets(16, 0, 0, 0)
        val docsLink = ActionLink("Documentation") {
            try {
                java.awt.Desktop.getDesktop().browse(URI(DOCS_URL))
            } catch (t: Throwable) {
                LOG.warn("Gimlet: failed to open docs URL", t)
            }
        }.apply {
            icon = AllIcons.Actions.Help
        }
        val footer = JPanel(BorderLayout()).apply {
            add(docsLink, BorderLayout.WEST)
        }
        root.add(footer, gbc)

        return root
    }

    private fun render(state: GimletState) {
        val settings = GimletSettings.getInstance(project).state
        val errors = GimletSettings.validate(settings)
        if (errors.isNotEmpty()) {
            renderInvalidConfig(errors)
            return
        }
        statusLabel.text = "<html><b>Status:</b> ${stateLabel(state)}</html>"
        statusLabel.icon = stateIcon(state)
        helpLabel.text = helpText(state, settings.tcpPort)
        // Attach is enabled only when a port is bound and no FSM is
        // running. The orchestrator is single-owner - a second click in
        // ATTACHED is a no-op - so leaving the button enabled there
        // would silently swallow user clicks. CPI re-attach is the
        // FSM's job (auto, on the next gdbstub).
        attachButton.isEnabled = state == GimletState.READY
        stopButton.isEnabled = state == GimletState.ATTACHED
    }

    private fun renderInvalidConfig(errors: List<String>) {
        statusLabel.text = "<html><b>Status:</b> Configuration error</html>"
        statusLabel.icon = AllIcons.General.Warning
        val bullets = errors.joinToString("<br>") { "&bull; $it" }
        helpLabel.text = """
            <html>$bullets<br><br>
            Update under <b>Settings → Tools → Gimlet</b>,<br>
            or edit <code>.idea/gimlet.xml</code> directly.</html>
        """.trimIndent()
        attachButton.isEnabled = false
        stopButton.isEnabled = false
    }

    private fun stateLabel(state: GimletState): String = when (state) {
        GimletState.IDLE     -> "Idle - No test detected"
        GimletState.READY    -> "Ready - port bound, click Attach"
        GimletState.ATTACHED -> "Attached - debug session live"
    }

    private fun stateIcon(state: GimletState): javax.swing.Icon = when (state) {
        GimletState.IDLE     -> AllIcons.RunConfigurations.TestState.Run
        GimletState.READY    -> AllIcons.Actions.Execute
        GimletState.ATTACHED -> AllIcons.Actions.StartDebugger
    }

    private fun helpText(state: GimletState, port: Int): String {
        val settings = GimletSettings.getInstance(project).state
        val version = settings.platformToolsVersionOrDefault
        return when (state) {
            GimletState.IDLE -> {
                val tracePath = settings.sbfTracePath?.takeIf { it.isNotBlank() } ?: "target/sbf/trace"
                """
                    <html>Gimlet attaches once a test process binds to port <b>$port</b> with the sbpf-debugger.<br><br>
                    For Mollusk / LiteSVM, enable the <code>sbpf-debugger</code> feature on the <code>litesvm</code> / <code>mollusk-svm</code> dependency in your <code>Cargo.toml</code>, then build your programs:<br>
                    &nbsp;&nbsp;<code>RUSTFLAGS="-Copt-level=0 -C strip=none -C debuginfo=2" cargo-build-sbf --tools-version v$version --debug --arch v1</code><br><br>
                    And finally, run your test:<br>
                    &nbsp;&nbsp;<code>SBF_DEBUG_PORT=$port SBF_TRACE_DIR=${'$'}PWD/$tracePath cargo test</code><br><br>
                    This view flips to <b>Ready</b> once the port is bound.</html>
                """.trimIndent()
            }
            GimletState.READY -> """
                <html>Click <b>Attach Debugger</b> to start your session.</html>
            """.trimIndent()
            GimletState.ATTACHED -> """
                <html>Gimlet debug session live on port <b>$port</b>.<br>
                Click <b>Stop Session</b> when you're done.</html>
            """.trimIndent()
        }
    }

    override fun dispose() {
        monitor.setObserved(false)
    }

    companion object {
        // TODO: swap to the JetBrains Marketplace listing URL once the
        //  plugin is published - `https://plugins.jetbrains.com/plugin/<id>/gimlet`.
        //  Until then, GitHub README is the canonical user-facing doc.
        const val DOCS_URL: String = "https://github.com/LimeChain/gimlet-kotlin#readme"
    }
}
