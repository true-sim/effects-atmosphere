plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
    id("truesim.slang")
}

description = "Fog for true-sim as a media shading hook of the engine: height fog, fog volumes and indoor zones from " +
    "scene components, integrated along every view ray through the regions it crosses."

dependencies {
    api("com.truesim.engine:engine-scene:0.192.0") // the renderer's shading hooks, the scene bridge
    testImplementation(testFixtures("com.truesim.engine:engine-render:0.192.0")) // the headless renderer
    testImplementation("com.truesim.scene:scene-meshbuild:0.70.0")
}

// The fog's record (AtmosphereFogParameters) and constants (FogConstants) of src/main/slang, as Java; the listing
// (AtmosphereFogShadingHooks) rebuilds the engine's shading with the fog hook.
slang {
    javaPackage = "com.truesim.effects.atmosphere.fog.hooks"
    pushBase = "com.truesim.engine.vulkan.PushConstants"
    constantModules = listOf("atmosphere/fog")
}

// The screenshots (TRUESIM_SCREENSHOTS=true) render at full size.
tasks.test {
    if (providers.environmentVariable("TRUESIM_SCREENSHOTS").orNull == "true") {
        maxHeapSize = "4g"
        extensions.configure<JacocoTaskExtension> { isEnabled = false }
    }
    providers.gradleProperty("screenshots").orNull?.let { systemProperty("truesim.screenshots", it) }
}

// LWJGL and mapped GPU memory use native access.
tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
