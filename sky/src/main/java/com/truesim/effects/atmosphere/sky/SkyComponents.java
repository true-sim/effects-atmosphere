package com.truesim.effects.atmosphere.sky;

import com.truesim.scene.core.ComponentType;

/**
 * The sky's components on scene nodes (application-api.md D46): a world says what its sky is by putting
 * {@link #SKY} on the node that holds its environment, and its time of day by {@link #SKY_TIME}; a renderer or a
 * server reads them from the scene, never from a global.
 *
 * <pre>{@code
 * scene.components().set(environment, SkyComponents.SKY, SkySettings.forQuality(Quality.MEDIUM, observer));
 * scene.components().set(environment, SkyComponents.SKY_TIME, SkyTime.of(Instant.parse("2026-06-21T21:00:00Z"), 60));
 * }</pre>
 */
public final class SkyComponents {

    /** The sky's settings; saved. */
    public static final ComponentType<SkySettings> SKY =
        ComponentType.of("truesim.atmosphere.sky", SkySettings.class);
    /** The sky's clock; saved, so a loaded game has its time of day. */
    public static final ComponentType<SkyTime> SKY_TIME =
        ComponentType.of("truesim.atmosphere.sky-time", SkyTime.class);

    private SkyComponents() {
    }
}
