plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
    id("truesim.slang")
}

description = "The sky of true-sim drawn: the Hillaire 2020 atmosphere (transmittance, multiple-scattering, sky-view " +
    "and aerial-perspective look-up tables), sky capture into the engine's environment, aerial perspective as a " +
    "media shading hook, sun and moon discs and the night sky, as techniques of the engine's frame graph."

dependencies {
    api(project(":atmosphere-sky"))
    api("com.truesim.engine:engine-scene:0.192.0") // the renderer, its shading hooks, the scene bridge
    testImplementation(testFixtures("com.truesim.engine:engine-render:0.192.0")) // the headless renderer
    testImplementation("com.truesim.scene:scene-meshbuild:0.70.0")
}

// The atmosphere's records (AerialPerspectiveParameters, AtmosphereFrameLayout) and constants (AtmosphereConstants)
// of src/main/slang, as Java; the listing (AtmosphereRenderShadingHooks) rebuilds the engine's shading with the
// aerial perspective hook.
slang {
    javaPackage = "com.truesim.effects.atmosphere.render.hooks"
    pushPackage = "com.truesim.effects.atmosphere.render.layout"
    pushBase = "com.truesim.engine.vulkan.PushConstants" // push layouts are writers too
    constantModules = listOf("atmosphere/atmosphere")
}

coverage {
    minimum = 0.8
}

// The benchmarks (TRUESIM_BENCHMARK=true) and screenshots (TRUESIM_SCREENSHOTS=true) measure the code, not its
// coverage probes.
tasks.test {
    if (providers.environmentVariable("TRUESIM_BENCHMARK").orNull == "true"
        || providers.environmentVariable("TRUESIM_SCREENSHOTS").orNull == "true") {
        maxHeapSize = "4g"
        extensions.configure<JacocoTaskExtension> { isEnabled = false }
    }
}

// LWJGL and mapped GPU memory use native access.
tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
