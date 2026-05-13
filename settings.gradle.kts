rootProject.name = "gimlet"

include(":core", ":rustrover")

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
