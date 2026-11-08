package com.truesim.effects.atmosphere.fog;

import com.truesim.engine.settings.Setting;
import java.util.List;

/**
 * The fog's settings, read from the frame graph's settings when frames record: how many regions a view takes, how many
 * a ray takes, how finely pieces of rays inside regions are integrated, and how far regions count.
 */
public final class FogSettings {

    /** Fog volumes a view takes at most: the nearest to its camera. */
    public static final Setting<Integer> MAX_VOLUMES = Setting.integer("atmosphere.fog.maxVolumes", 64, 0, 4096,
        "fog volumes a view takes at most, the nearest to its camera");
    /** Indoor zones a view takes at most: the nearest to its camera. */
    public static final Setting<Integer> MAX_ZONES = Setting.integer("atmosphere.fog.maxZones", 32, 0, 4096,
        "indoor zones a view takes at most, the nearest to its camera");
    /**
     * Regions one ray takes at most, the nearest to the camera first: every pixel tests the view's regions in that
     * order until it has this many it crosses, then integrates through them (at most 16, the shader's arrays).
     */
    public static final Setting<Integer> REGIONS_PER_RAY = Setting.integer("atmosphere.fog.regionsPerRay", 8, 1, 16,
        "fog regions one view ray takes at most, the nearest first");
    /**
     * Steps per piece of a ray inside regions (between two of their boundaries): exact for hard-edged uniform regions
     * at 1; soft edges, falloffs and height fog inside zones want more.
     */
    public static final Setting<Integer> STEPS = Setting.integer("atmosphere.fog.steps", 8, 1, 64,
        "integration steps per piece of a view ray inside fog regions");
    /** Regions farther from the camera than this (from their bounds) are left out, metres. */
    public static final Setting<Float> CULL_DISTANCE = Setting.decimal("atmosphere.fog.cullDistance", 5000, 0,
        1_000_000, "fog regions farther from the camera than this are left out, metres");

    /** Every fog setting. */
    public static final List<Setting<?>> ALL = List.of(MAX_VOLUMES, MAX_ZONES, REGIONS_PER_RAY, STEPS, CULL_DISTANCE);

    private FogSettings() {
    }
}
