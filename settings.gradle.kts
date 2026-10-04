pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
        maven("https://maven.minecraftforge.net/") { name = "MinecraftForge" }
        maven("https://maven.architectury.dev/") { name = "Architectury" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
    }
    includeBuild("build-logic")
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("dev.kikugie.stonecutter") version "0.9.8"
}

rootProject.name = "multichat"

// Pure-Java engine: no Minecraft, no mappings, no preprocessing. Deliberately
// outside the Stonecutter tree so it is compiled and tested exactly once.
include("core")

stonecutter {
    create(rootProject) {
        fun match(mc: String, vararg loaders: String) = loaders.forEach {
            version("$mc-$it", mc).buildscript = "build.$it.gradle.kts"
        }

        // Server-side only, so a node is just that loader's entry point forwarding events
        // to the shared runtime (see src/main/java/de/taczbg/multichat/<loader>).
        match("1.20.1", "forge")
        match("1.21.1", "neoforge")

        vcsVersion = "1.21.1-neoforge"
    }
}
