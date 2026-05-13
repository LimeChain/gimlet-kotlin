import org.jetbrains.intellij.platform.gradle.TestFrameworkType

// :core is the IDE-agnostic library subproject. It carries every
// production source file and every test in the gimlet plugin today.
// :rustrover depends on it via `implementation(project(":core"))`.
//
// IPGP 2.x is applied here purely for the platform compile classpath
// (CidrDebugProcess, LLDBDriver, Kotlin UI DSL v2, etc. - anything in
// the platform / cidr.debugger / Rust plugin surface). The lack of an
// `intellijPlatform { pluginConfiguration { ... } }` block means this
// module does NOT produce a plugin zip; it produces a plain library
// jar that the leaf module consumes.
//
// The IDE target is RustRover: it bundles `com.intellij.modules.cidr.debugger`
// (via the bundled `com.intellij.nativeDebug` plugin) which is the
// only platform surface :core touches. :core code MUST stay strictly
// within that CIDR-shared API surface - the IDE-specific run-config
// construction lives behind the `AttachStrategy` interface, with the
// concrete implementation in :rustrover. The module structure is
// preserved so re-introducing a second leaf (e.g. CLion) is a matter
// of adding a new subproject + AttachStrategy, not a refactor of the
// shared code.

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.intelliJPlatform)
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.opentest4j)

    intellijPlatform {
        rustRover(providers.gradleProperty("platformVersion"))

        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })
        plugins(providers.gradleProperty("platformPlugins").map { it.split(',') })
        bundledModules(providers.gradleProperty("platformBundledModules").map { it.split(',') })

        testFramework(TestFrameworkType.Platform)
    }
}

tasks {
    // IPGP 2.x registers these tasks on every subproject that applies
    // the plugin, even when there's no `pluginConfiguration { ... }`
    // block (i.e. when the subproject is a library, not a plugin).
    // Without disabling, an aggregating root invocation like
    // `./gradlew buildPlugin` walks into :core's buildSearchableOptions
    // which spawns the IDE in headless traverseUI mode. We also have
    // nothing settings-indexable yet, so the task is pure overhead.
    buildSearchableOptions {
        enabled = false
    }
    prepareJarSearchableOptions {
        enabled = false
    }
    jarSearchableOptions {
        enabled = false
    }

    // Disable plugin-output tasks. IPGP 2.x registers `buildPlugin`,
    // `prepareSandbox`, `runIde`, `signPlugin`, `publishPlugin`, etc. on
    // every subproject. :core is a library - no plugin descriptor, no
    // plugin zip - so these tasks have nothing to do but they still run
    // when invoked at the root via `./gradlew buildPlugin`, producing a
    // useless `core-<version>.zip` and pulling in unnecessary work.
    // `composedJar` is left enabled because :rustrover's `prepareSandbox`
    // consumes it as an input.
    buildPlugin { enabled = false }
    prepareSandbox { enabled = false }
    runIde { enabled = false }
    signPlugin { enabled = false }
    publishPlugin { enabled = false }
}
