// Root build script. The actual plugin & module configuration lives in
// the per-subproject scripts (:core, :rustrover). This root only owns
// concerns that are project-global by definition: the gradle wrapper
// task and one-time plugin loading.
//
// The `apply false` here is required when a Gradle plugin is applied in
// multiple subprojects via the `plugins { ... }` DSL with explicit
// versions. Without this, gradle warns "loaded multiple times in
// different subprojects, which is not supported and may break the build"
// and refuses to share the plugin classloader. Declaring the plugins
// here (without applying) gives every subproject access via
// `alias(libs.plugins.X)` while the actual classloading happens once.

plugins {
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.intelliJPlatform) apply false
    alias(libs.plugins.changelog) apply false
}

tasks.wrapper {
    gradleVersion = providers.gradleProperty("gradleVersion").get()
}
