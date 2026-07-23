rootProject.name = "claude-code-cache-fix-gui"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        kotlin("multiplatform") version "2.1.0"
        kotlin("jvm") version "2.1.0"
        kotlin("plugin.serialization") version "2.1.0"
        kotlin("plugin.compose") version "2.1.0"
        id("org.jetbrains.compose") version "1.7.3"
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":shared")
include(":desktop")
