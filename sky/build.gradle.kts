plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "The night sky for true-sim: a real star catalogue as a scene asset, star colours from B-V, apparent places for a date, airglow and Milky Way parameters, sky components."

dependencies {
    api(project(":atmosphere-ephemeris"))
    api("com.truesim.scene:scene-core:0.65.0")
    api("com.truesim.scene:scene-assets:0.65.0")
}

coverage {
    minimum = 0.9
}
