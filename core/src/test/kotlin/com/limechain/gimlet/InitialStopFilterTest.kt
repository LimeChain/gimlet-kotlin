package com.limechain.gimlet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure-logic tests for [InitialStopFilter]. No IntelliJ fixture needed
 * since the class has no platform deps.
 */
class InitialStopFilterTest {

    @Test
    fun `first stop forwards when armed`() {
        val filter = InitialStopFilter(initialPaused = false)
        assertFalse("first stop must be forwarded", filter.shouldDrop())
    }

    @Test
    fun `second stop drops before the IDE has paused`() {
        val filter = InitialStopFilter(initialPaused = false)
        filter.shouldDrop()
        assertTrue("second stop must be dropped", filter.shouldDrop())
    }

    @Test
    fun `every stop after onSessionPaused forwards`() {
        val filter = InitialStopFilter(initialPaused = false)
        filter.shouldDrop()
        filter.onSessionPaused()
        // Real signals after the first IDE pause - breakpoints,
        // exceptions, manual interrupts - must never be dropped.
        repeat(5) {
            assertFalse("stop #$it after pause must be forwarded", filter.shouldDrop())
        }
    }

    @Test
    fun `construction with initialPaused=true disables the filter`() {
        // If the session is already paused at construction time there's
        // no notifyPositionReached pipeline to protect; every stop
        // should propagate immediately.
        val filter = InitialStopFilter(initialPaused = true)
        assertFalse(filter.shouldDrop())
        assertFalse(filter.shouldDrop())
        assertFalse(filter.shouldDrop())
    }

    @Test
    fun `concurrent first stops yield exactly one forward`() {
        // Two threads call shouldDrop() simultaneously. The CAS must
        // pick exactly one winner so we never both forward twice
        // (re-trip CIDR's assertion) nor drop twice (silently swallow
        // the user's first stop).
        val filter = InitialStopFilter(initialPaused = false)
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val forwarded = AtomicInteger(0)
        val dropped = AtomicInteger(0)

        try {
            repeat(2) {
                executor.submit {
                    start.await()
                    if (filter.shouldDrop()) dropped.incrementAndGet()
                    else forwarded.incrementAndGet()
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue("workers did not finish in time", done.await(5, TimeUnit.SECONDS))

            assertEquals("exactly one stop forwarded", 1, forwarded.get())
            assertEquals("exactly one stop dropped", 1, dropped.get())
        } finally {
            executor.shutdownNow()
        }
    }
}
