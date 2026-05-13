package com.limechain.gimlet

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.atomic.AtomicInteger

/**
 * Persistence round-trip + default fallbacks for [GimletSettings].
 */
class GimletSettingsTest : BasePlatformTestCase() {

    private val settings get() = GimletSettings.getInstance(project)

    fun testDefaultsMatchCompanionConstants() {
        val state = settings.state
        assertEquals(GimletSettings.DEFAULT_TCP_PORT, state.tcpPort)
        assertEquals(GimletSettings.DEFAULT_PLATFORM_TOOLS_VERSION, state.platformToolsVersion)
        assertTrue("stopOnEntry should default to true", state.stopOnEntry)
        assertEquals(GimletSettings.ImportDecision.NotAsked, state.importDecision)
    }

    fun testMutationsSurviveRoundTrip() {
        // Mutate each field, serialise via getState, rehydrate via loadState
        // on a fresh state object - mirrors how the platform persists.
        val outer = settings.state
        outer.tcpPort = 1313
        outer.platformToolsVersion = "1.55"
        outer.stopOnEntry = false
        outer.importDecision = GimletSettings.ImportDecision.Dismissed

        val element = settings.state
        val fresh = GimletSettings(project)
        fresh.loadState(element)

        assertEquals(1313, fresh.state.tcpPort)
        assertEquals("1.55", fresh.state.platformToolsVersion)
        assertFalse(fresh.state.stopOnEntry)
        assertEquals(GimletSettings.ImportDecision.Dismissed, fresh.state.importDecision)
    }

    fun testPlatformToolsVersionOrDefaultFallsBackWhenNull() {
        settings.state.platformToolsVersion = null
        assertEquals(
            GimletSettings.DEFAULT_PLATFORM_TOOLS_VERSION,
            settings.state.platformToolsVersionOrDefault,
        )
    }

    fun testPlatformToolsVersionOrDefaultReturnsSetValue() {
        settings.state.platformToolsVersion = "2.0"
        assertEquals("2.0", settings.state.platformToolsVersionOrDefault)
    }

    fun testImportDecisionEnumPersistsAllVariants() {
        // Guards against accidentally dropping an enum variant from the
        // persisted representation - BaseState.enum serialises by name.
        for (decision in GimletSettings.ImportDecision.entries) {
            settings.state.importDecision = decision
            assertEquals(decision, settings.state.importDecision)
        }
    }

    fun testLoadStatePublishesSettingsChanged() {
        // External edits to .idea/gimlet.xml come back through loadState;
        // the tool window panel relies on this fan-out to refresh the
        // port it shows. Regression test for the publish wiring.
        val notifications = AtomicInteger(0)
        project.messageBus.connect(testRootDisposable)
            .subscribe(GimletSettings.TOPIC, GimletSettingsListener {
                notifications.incrementAndGet()
            })

        GimletSettings.getInstance(project).loadState(GimletSettings.InnerState())

        assertEquals(1, notifications.get())
    }

    fun testNoStateLoadedPublishesSettingsChanged() {
        // Fresh-project path. Symmetric with the loadState publish so
        // an early subscriber sees a "settings resolved" signal whether
        // gimlet.xml exists or not.
        val notifications = AtomicInteger(0)
        project.messageBus.connect(testRootDisposable)
            .subscribe(GimletSettings.TOPIC, GimletSettingsListener {
                notifications.incrementAndGet()
            })

        GimletSettings.getInstance(project).noStateLoaded()

        assertEquals(1, notifications.get())
    }

    fun testValidateAcceptsDefaults() {
        // Out-of-the-box state must be considered valid - otherwise
        // every fresh project would render the configuration-error
        // panel and refuse to attach.
        assertTrue(GimletSettings.validate(GimletSettings.InnerState()).isEmpty())
    }

    fun testValidateRejectsOutOfRangePort() {
        val state = GimletSettings.InnerState().apply { tcpPort = 12 }
        val errors = GimletSettings.validate(state)
        assertEquals(1, errors.size)
        assertTrue(errors.first().contains("TCP port 12"))
    }

    fun testValidateRejectsBlankVersion() {
        // BaseState's `string(default)` normalises "" back to default
        // behind our back, so test with whitespace - which is preserved
        // verbatim and still trips `isBlank()`.
        val state = GimletSettings.InnerState().apply { platformToolsVersion = "   " }
        val errors = GimletSettings.validate(state)
        assertEquals(1, errors.size)
        assertTrue(errors.first().contains("required"))
    }

    fun testValidateRejectsMalformedVersion() {
        val state = GimletSettings.InnerState().apply { platformToolsVersion = "abc" }
        val errors = GimletSettings.validate(state)
        assertEquals(1, errors.size)
        assertTrue(errors.first().contains("malformed"))
    }

    fun testValidateRejectsTooOldVersion() {
        val state = GimletSettings.InnerState().apply { platformToolsVersion = "1.50" }
        val errors = GimletSettings.validate(state)
        assertEquals(1, errors.size)
        assertTrue(errors.first().contains("below the minimum"))
    }

    fun testValidateRejectsRelativePlatformToolsPath() {
        val state = GimletSettings.InnerState().apply { platformToolsPath = "relative/path" }
        val errors = GimletSettings.validate(state)
        assertEquals(1, errors.size)
        assertTrue(errors.first().contains("must be absolute"))
    }

    fun testValidateRejectsMalformedPath() {
        // Path.of throws InvalidPathException on embedded NUL bytes.
        // Build the input via Char(0) so the source file stays text-only
        // (grep / IDE search choke on a literal NUL in the .kt).
        val raw = "with" + 0.toChar() + "null"
        val state = GimletSettings.InnerState().apply { sbfTracePath = raw }
        val errors = GimletSettings.validate(state)
        assertEquals(1, errors.size)
        assertTrue(errors.first().contains("not a valid filesystem path"))
    }

    fun testValidateAccumulatesMultipleErrors() {
        val state = GimletSettings.InnerState().apply {
            tcpPort = 12
            platformToolsVersion = "abc"
        }
        assertEquals(2, GimletSettings.validate(state).size)
    }

    fun testValidateIgnoresBlankOptionalPaths() {
        // Blank/null overrides mean "use default", not "user typed nothing
        // meaningful". The validator must skip those, not flag them.
        val state = GimletSettings.InnerState().apply {
            platformToolsPath = ""
            sbfTracePath = ""
            artifactsPath = ""
        }
        assertTrue(GimletSettings.validate(state).isEmpty())
    }
}
