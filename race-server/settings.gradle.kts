pluginManagement {
    plugins {
        kotlin("jvm") version "2.3.0"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.3.0"
        id("io.ktor.plugin") version "3.4.0"
    }

    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven {
            name = "Fabric"
            url = uri("https://maven.fabricmc.net/")
        }
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven {
            name = "Fabric"
            url = uri("https://maven.fabricmc.net/")
        }
    }
}

rootProject.name = "race-server"

include("shared")
project(":shared").projectDir = file("../shared")

