package com.limechain.gimlet

import com.intellij.openapi.diagnostic.logger
import oshi.SystemInfo
import oshi.software.os.InternetProtocolStats.TcpState
import java.util.concurrent.atomic.AtomicBoolean

private val LOG = logger<TcpListenProbe>()

/**
 * Passive answer to "is anything in TCP `LISTEN` state on this local
 * port?". Two constraints rule out the obvious JDK probes:
 *
 *  - **Must not connect.** sbpf's gdbstub accepts a single client and
 *    treats an accepted-then-closed connection as the debugger
 *    detaching - a connect-probe would consume LLDB's slot and resume
 *    the VM.
 *  - **Must not bind.** `BindException` fires for `ESTABLISHED` and
 *    `LISTEN` alike, so it can't spot the *next* gdbstub's fresh
 *    `LISTEN` while the current one's socket is still `ESTABLISHED`;
 *    and a momentarily successful bind races sbpf binding the next
 *    gdbstub on the same port.
 *
 * Neither the JDK nor the IntelliJ platform can enumerate the socket
 * table (`com.intellij.util.net.NetUtils` offers active probes only),
 * so we read it through OSHI - `/proc/net/tcp` on Linux, `sysctl` on
 * macOS, `GetExtendedTcpTable` on Windows.
 *
 * oshi-core is bundled with the plugin, minus its JNA and slf4j
 * transitives: the IDE provides both on the core classpath
 * (`lib/util-8.jar`), and a second JNA copy would clash with the
 * IDE-managed native dispatch library (`jna.boot.library.path`). The
 * IDE ships the same oshi-core version inside its bundled
 * `performanceTesting` plugin against that same JNA, so the pairing
 * is JetBrains-validated - we bundle our own copy only because
 * another plugin's libraries aren't on our classpath.
 */
internal object TcpListenProbe {

    /**
     * oshi memoizes the OS handle internally; the stats object is a
     * stateless view whose `connections` takes a fresh socket-table
     * snapshot on every call.
     */
    private val ipStats by lazy { SystemInfo().operatingSystem.internetProtocolStats }

    fun isListening(port: Int): Boolean {
        return try {
            ipStats.connections.any { connection ->
                connection.state == TcpState.LISTEN && connection.localPort == port
            }
        } catch (t: Throwable) {
            warnOnce(t)
            false
        }
    }

    /**
     * A broken probe fails on every poll (every ~500ms while the
     * chain waits) - warn on the first occurrence only, debug-log
     * the rest.
     */
    private val warnedProbeFailure = AtomicBoolean(false)

    private fun warnOnce(t: Throwable) {
        val message = "Gimlet: OSHI socket-table probe failed"
        if (warnedProbeFailure.compareAndSet(false, true)) {
            LOG.warn(message, t)
        } else {
            LOG.debug(message, t)
        }
    }
}
