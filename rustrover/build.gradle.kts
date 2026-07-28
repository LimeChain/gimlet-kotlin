import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

// :rustrover is the only plugin artifact today. It owns the plugin
// descriptor (META-INF/plugin.xml) and the signing / publishing /
// verifier configuration. Reads its IDE properties from the root
// gradle.properties shared with :core.
//
// The module is kept under `:rustrover/` (rather than collapsing into
// `:core` or being renamed to `:plugin`) so re-introducing CLion (or
// any other CIDR-hosting IDE) is a clean parallel addition - a new
// subproject + a new AttachStrategy - rather than a refactor.

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.intelliJPlatform)
    alias(libs.plugins.changelog)
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":core"))

    intellijPlatform {
        rustRover(providers.gradleProperty("platformVersion"))

        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })
        plugins(providers.gradleProperty("platformPlugins").map { it.split(',') })

        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        // Extract the <!-- Plugin description --> section from the root
        // README.md. Path is relative to this subproject, so we walk
        // up to the rootProject directory.
        description = providers.fileContents(rootProject.layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        val changelog = project.changelog
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = providers.gradleProperty("pluginVersion").map {
            listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }

    pluginVerification {
        ides {
            // Oldest supported build (same as the compile/sandbox target)...
            create(IntelliJPlatformType.RustRover, providers.gradleProperty("platformVersion"))
            recommended()
        }
    }
}

changelog {
    groups.empty()
    // Resolve relative to the rootProject so the changelog plugin keeps
    // pointing at the single CHANGELOG.md at the repository root rather
    // than a non-existent one under :rustrover/.
    path = rootProject.layout.projectDirectory.file("CHANGELOG.md").asFile.absolutePath
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
    versionPrefix = "v"
}

tasks {
    publishPlugin {
        dependsOn(patchChangelog)
    }

    // Pin the buildPlugin zip's base name to "gimlet" so the artifact
    // is `gimlet-<version>.zip`. Without this gradle would default to
    // the subproject name (`rustrover`) and emit `rustrover-<version>.zip`,
    // which would break consumer scripts and the marketplace listing.
    buildPlugin {
        archiveBaseName.set("gimlet")
    }

    // IPGP 2.x registers these unconditionally, but we have nothing
    // settings-indexable yet and the headless traverseUI starter has a
    // history of crashes across IDE majors. Re-enable once we have
    // searchable settings worth indexing AND the starter is stable.
    buildSearchableOptions {
        enabled = false
    }
    prepareJarSearchableOptions {
        enabled = false
    }
    jarSearchableOptions {
        enabled = false
    }
}
