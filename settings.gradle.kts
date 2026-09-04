plugins {
    // Auto-provisions the JDK requested by the toolchain block if it is not installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "cellophane"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include("smpp", "server", "ui")
