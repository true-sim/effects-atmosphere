plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "Ephemerides for true-sim: time scales, sun and moon positions, moon phase and illumination, precession and coordinate frames; JDK and common-math only."

dependencies {
    api("com.truesim.common:common-math:0.6.0")
}

coverage {
    minimum = 0.9
}
