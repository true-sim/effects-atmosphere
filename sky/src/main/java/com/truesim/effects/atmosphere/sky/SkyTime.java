package com.truesim.effects.atmosphere.sky;

import com.truesim.effects.atmosphere.ephemeris.AstroTime;
import java.time.Instant;

/**
 * The sky's clock: the UT Julian day at the game's time zero and how fast sky time runs (1 = real time, 60 = a game
 * minute per second, 0 = frozen). The sky's date at game time {@code t} seconds is {@link #julianDayAt}.
 *
 * @param julianDayUt the UT Julian day at game time 0
 * @param rate sky seconds per game second
 */
public record SkyTime(double julianDayUt, double rate) {

    public SkyTime {
        if (!Double.isFinite(julianDayUt) || !Double.isFinite(rate)) {
            throw new IllegalArgumentException("Not a sky time: " + julianDayUt + ", rate " + rate);
        }
    }

    /** Starting at {@code start}, running at {@code rate}. */
    public static SkyTime of(Instant start, double rate) {
        return new SkyTime(AstroTime.fromEpochMillis(start.toEpochMilli()), rate);
    }

    /** The UT Julian day at game time {@code gameSeconds}. */
    public double julianDayAt(double gameSeconds) {
        return julianDayUt + gameSeconds * rate / 86400.0;
    }
}
