package com.truesim.effects.atmosphere;

/**
 * The quality presets of the atmosphere and weather effects. Each configuration record has a {@code forQuality}
 * factory mapping a preset to its settings; every value stays individually overridable (records' {@code with...}
 * copies or the engine's settings), so a preset is only a starting point. {@link #LOW} is meant for games that do not
 * aim at realism: it must never need the data the expensive tiers use.
 */
public enum Quality {
    /** The cheapest tier: analytic or static data, nothing simulated that a cheap renderer cannot show. */
    LOW,
    /** Mid-range hardware. */
    MEDIUM,
    /** The default on desktop GPUs. */
    HIGH,
    /** Screenshots, cinematics, references. */
    ULTRA
}
