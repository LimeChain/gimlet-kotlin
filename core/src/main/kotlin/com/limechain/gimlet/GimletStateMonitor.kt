package com.limechain.gimlet

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val LOG = logger<GimletStateMonitor>()

/**
 * Watches the configured TCP port for an SBPF gdbstub and maintains the
 * current [GimletState] visible to UI consumers (status bar widget, tool
 * window).
 *
 * **Lazy**: only ticks while at least one observer is active. Status bar
 * widgets and tool windows call [setObserved] when they become visible
 * and again when hidden. With no observers, the monitor stops polling
 * entirely to avoid burning a wakeup every 1–2 seconds for a UI nobody
 * is looking at.
 *
 * **Probe**: tries to open a `ServerSocket` on 127.0.0.1 at the configured
 * port; if `BindException`, port is in use → `READY`. This is purely
 * passive - it never makes a TCP connection to the gdbstub, so the SBPF
 * VM's `accept()` queue is undisturbed. The downside is we can't tell
 * an SBPF gdbstub apart from any other process bound to that port; v0
 * accepts that ambiguity (default port 1212 is reasonably specific).
 */
@Service(Service.Level.PROJECT)
internal class GimletStateMonitor(
    private val project: Project,
    private val cs: CoroutineScope,
) : Disposable {

    @Volatile
    var state: GimletState = GimletState.IDLE
        private set

    private val observerCount = AtomicInteger(0)

    @Volatile
    private var tickJob: Job? = null

    init {
        // Re-probe immediately when settings change (notably the TCP
        // port). Without this, after a settings flip the state would
        // sit on its last value until the next regular tick - up to
        // [IDLE_POLL] - which the user perceives as a transient
        // wrong state in the tool window.
        project.messageBus.connect(this)
            .subscribe(GimletSettings.TOPIC, GimletSettingsListener { nudge() })
    }

    /**
     * UI observers (status bar widget, tool window) call this with `true`
     * when they become visible and `false` when hidden / disposed. The
     * monitor starts polling on the first observer; stops on the last.
     */
    @Synchronized
    fun setObserved(observed: Boolean) {
        val n = if (observed) observerCount.incrementAndGet() else observerCount.decrementAndGet()
        if (n < 0) {
            // Defensive - observers should be balanced. If someone
            // double-decrements, snap back to 0 rather than letting it go
            // negative and confuse subsequent increments.
            observerCount.set(0)
            return
        }
        if (observed && n == 1 && tickJob == null) startTicking()
        if (!observed && n == 0) stopTicking()
    }

    /** Forces an immediate tick - useful right after an attach succeeds. */
    fun nudge() {
        cs.launch { tick() }
    }

    /** Called by the attach orchestrator when an LLDB session lands / ends. */
    fun setAttached(attached: Boolean) {
        val next = if (attached) GimletState.ATTACHED else GimletState.IDLE
        publish(next)
    }

    private fun startTicking() {
        tickJob = cs.launch {
            while (isActive) {
                tick()
                val sleep = if (state == GimletState.IDLE) IDLE_POLL else ACTIVE_POLL
                delay(sleep)
            }
        }
    }

    private fun stopTicking() {
        tickJob?.cancel()
        tickJob = null
    }

    private suspend fun tick() {
        if (state == GimletState.ATTACHED) {
            // Don't re-probe while a session is live - `setAttached(false)`
            // is what flips us back to IDLE. Probing while attached would
            // always see the port as in use and add noise.
            return
        }
        val settings = GimletSettings.getInstance(project).state
        if (GimletSettings.validate(settings).isNotEmpty()) {
            // Any config error: stay IDLE rather than probe. The tool
            // window panel renders an explicit "configuration error"
            // state when it sees this and disables the Attach button,
            // so READY would only mislead the user (and the status bar
            // widget).
            publish(GimletState.IDLE)
            return
        }
        val ready = withContext(Dispatchers.IO) { isPortBound(settings.tcpPort) }
        publish(if (ready) GimletState.READY else GimletState.IDLE)
    }

    private fun publish(next: GimletState) {
        if (next == state) return
        state = next
        LOG.info("Gimlet state → $next")
        project.messageBus.syncPublisher(TOPIC).stateChanged(next)
    }

    override fun dispose() {
        stopTicking()
    }

    companion object {
        // Active poll is 1s so the UI flips to READY within a second of
        // the gdbstub binding; idle poll backs off to 2s since "nothing
        // happening" is the steady state.
        private val ACTIVE_POLL: Duration = 1.seconds
        private val IDLE_POLL: Duration = 2.seconds

        @JvmField
        val TOPIC: Topic<GimletStateListener> =
            Topic.create("Gimlet state changes", GimletStateListener::class.java)

        fun getInstance(project: Project): GimletStateMonitor = project.service()

        /**
         * Tries to bind a fresh `ServerSocket` on the loopback at [port].
         * - Bind succeeds → port is FREE → close + return false (IDLE).
         * - Bind throws `BindException` → port is IN USE by something
         *   (presumably the gdbstub) → return true (READY).
         */
        private fun isPortBound(port: Int): Boolean =
            try {
                ServerSocket(port, /* backlog = */ 0, InetAddress.getLoopbackAddress()).use { false }
            } catch (_: BindException) {
                true
            } catch (t: Throwable) {
                LOG.warn("Gimlet: port probe on $port threw ${t.javaClass.simpleName}: ${t.message}")
                false
            }
    }
}

internal fun interface GimletStateListener {
    fun stateChanged(newState: GimletState)
}
