pluginManagement {
    // Use ../build-logic when present; override with -Ptruesim.build-logic=<path> (or false).
    providers.gradleProperty("truesim.build-logic").orElse("../build-logic").get()
        .takeIf { file(it).isDirectory }?.let { includeBuild(it) }
    repositories {
        gradlePluginPortal()
        maven("https://maven.pkg.github.com/true-sim/build-logic") {
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
            }
        }
    }
}

plugins {
    id("truesim.settings") version "0.19.0"
}

rootProject.name = "effects-atmosphere"

// Each module is published on its own: atmosphere-ephemeris needs only the JDK and common-math (a server computing
// the sun takes just that); atmosphere-sky adds the star catalogue and the night sky as scene assets and components;
// atmosphere-fog is the first render plugin: fog, fog volumes and indoor zones as a media shading hook of the engine;
// atmosphere-render draws the sky: the Hillaire 2020 atmosphere, sky capture, aerial perspective, sun, moon and stars,
// as techniques of the engine's frame graph and a media shading hook, over the fog.
include("atmosphere-ephemeris", "atmosphere-sky", "atmosphere-fog", "atmosphere-render")
project(":atmosphere-ephemeris").projectDir = file("ephemeris")
project(":atmosphere-sky").projectDir = file("sky")
project(":atmosphere-fog").projectDir = file("fog")
project(":atmosphere-render").projectDir = file("render")

// Sibling checkouts are used as included builds when present (override with -Ptruesim.<repo>=<path>, or false).
val truesimRepos = listOf("build-logic", "common-math", "common-state", "common-jobs", "common-graph", "scene", "ui", "engine")
for (repo in truesimRepos) {
    providers.gradleProperty("truesim.$repo").orElse("../$repo").get()
        .takeIf { file(it).isDirectory }?.let { includeBuild(it) }
}

dependencyResolutionManagement {
    repositories {
        for (repo in truesimRepos) {
            maven("https://maven.pkg.github.com/true-sim/$repo") {
                credentials {
                    username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                    password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
                }
            }
        }
    }
}
