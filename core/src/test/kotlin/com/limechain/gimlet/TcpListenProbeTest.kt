package com.limechain.gimlet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Exercises [TcpListenProbe] against real loopback sockets - OSHI
 * reads the live OS socket table, so there is no parser seam to
 * unit-test; instead each test stages an actual TCP state. Ephemeral
 * ports (bind to 0) keep the tests collision-free.
 *
 * The ESTABLISHED cases mirror the production scenario the probe
 * exists for: during a CPI, primary's gdbstub connection stays
 * `ESTABLISHED` on the port while the chain loop waits for the next
 * gdbstub's fresh `LISTEN` on that same port.
 */
class TcpListenProbeTest {

    private val loopback: InetAddress = InetAddress.getLoopbackAddress()

    @Test
    fun `fresh listener is detected`() {
        ServerSocket(0, 1, loopback).use { server ->
            assertTrue(
                "LISTEN on ${server.localPort} must be detected",
                TcpListenProbe.isListening(server.localPort),
            )
        }
    }

    @Test
    fun `listener beside established connection is detected`() {
        ServerSocket(0, 1, loopback).use { server ->
            Socket(loopback, server.localPort).use {
                server.accept().use {
                    assertTrue(
                        "LISTEN must still be seen next to an ESTABLISHED on the same port",
                        TcpListenProbe.isListening(server.localPort),
                    )
                }
            }
        }
    }

    @Test
    fun `established-only port reads false`() {
        val server = ServerSocket(0, 1, loopback)
        val port = server.localPort
        Socket(loopback, port).use {
            server.accept().use {
                server.close()
                assertFalse(
                    "ESTABLISHED without LISTEN must not read as listening",
                    TcpListenProbe.isListening(port),
                )
            }
        }
    }

    @Test
    fun `closed listener reads false`() {
        val port = ServerSocket(0, 1, loopback).use { it.localPort }
        assertFalse(
            "no socket left on $port - must not read as listening",
            TcpListenProbe.isListening(port),
        )
    }
}
