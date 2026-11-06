package com.truesim.effects.atmosphere.sky;

import com.truesim.annotations.AllowAllocation;
import com.truesim.effects.atmosphere.ephemeris.ApparentPlace;
import com.truesim.effects.atmosphere.ephemeris.Photometry;

/**
 * The stars as a renderer draws them, for one date: unit directions in equatorial coordinates of date (rotate them to
 * the horizon with {@code Ephemeris.equatorialToHorizon()}), illuminance outside the atmosphere in lux, and linear
 * sRGB colour of luminance 1. Packed float arrays, brightest first, ready to upload. Colours are computed once per
 * catalogue; {@link #update} (positions for a new date) allocates nothing.
 */
public final class StarField {

    private final StarCatalogue catalogue;
    private final float[] directions;
    private final float[] illuminance;
    private final float[] colors;
    private final double[] scratch = new double[3];
    private int count;
    private double updatedJde = Double.NaN;

    /** A field over {@code catalogue}'s stars, colours computed now. */
    @AllowAllocation("made once per catalogue")
    public StarField(StarCatalogue catalogue) {
        this.catalogue = catalogue;
        int n = catalogue.size();
        directions = new float[3 * n];
        illuminance = new float[n];
        colors = new float[3 * n];
        for (int i = 0; i < n; i++) {
            StarColor.linearSrgb(catalogue.colorIndex(i), colors, 3 * i);
        }
    }

    public StarCatalogue catalogue() {
        return catalogue;
    }

    /**
     * Computes the places of the stars down to {@code limitingMagnitude} for the date {@code place} was prepared for
     * ({@link ApparentPlace#prepare}), and their illuminance with the zero point {@code zeroMagnitudeLux} times
     * {@code brightness}.
     */
    public void update(ApparentPlace place, double jde, double limitingMagnitude, double zeroMagnitudeLux,
        double brightness) {
        count = catalogue.countBrighterThan(limitingMagnitude);
        for (int i = 0; i < count; i++) {
            place.apply(catalogue.rightAscension(i), catalogue.declination(i), catalogue.pmRaCosDec(i),
                catalogue.pmDec(i), scratch, 0);
            directions[3 * i] = (float) scratch[0];
            directions[3 * i + 1] = (float) scratch[1];
            directions[3 * i + 2] = (float) scratch[2];
            illuminance[i] = (float) (brightness * Photometry.illuminance(catalogue.magnitude(i), zeroMagnitudeLux));
        }
        updatedJde = jde;
    }

    /** Forgets the places, so the next {@link NightSky#update} recomputes them. */
    public void invalidate() {
        updatedJde = Double.NaN;
    }

    /** How many stars the last update placed (those down to the limiting magnitude): the first ones of the arrays. */
    public int count() {
        return count;
    }

    /** The TT Julian day of the last update, NaN before the first. */
    public double updatedJde() {
        return updatedJde;
    }

    /** x, y, z per star: equatorial unit directions of date. Read only. */
    public float[] directions() {
        return directions;
    }

    /** Lux per star outside the atmosphere. Read only. */
    public float[] illuminance() {
        return illuminance;
    }

    /** r, g, b per star: linear sRGB, luminance 1. Read only. */
    public float[] colors() {
        return colors;
    }
}
