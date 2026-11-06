package com.truesim.effects.atmosphere.sky;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;
import java.util.Objects;

/**
 * Stars as packed arrays, brightest first: J2000.0 positions, proper motions, visual magnitudes and B−V colour
 * indices, with catalogue numbers and names. Loaded through the asset system ({@link SkyAssets#STAR_CATALOGUE}: the
 * bundled HYG subset or any file in its columns) or generated ({@link ProceduralStars}). Immutable once made; because
 * it is sorted by magnitude, the stars down to a limiting magnitude are a prefix ({@link #countBrighterThan}).
 */
public final class StarCatalogue {

    private final String name;
    private final int size;
    private final double[] rightAscension;
    private final double[] declination;
    private final float[] pmRaCosDec;
    private final float[] pmDec;
    private final float[] magnitude;
    private final float[] colorIndex;
    private final int[] hip;
    private final String[] properName;
    private final String[] designation;

    /**
     * A catalogue of {@code size} stars from parallel arrays (taken, not copied), sorted here by magnitude.
     *
     * @param rightAscension J2000.0, degrees
     * @param declination J2000.0, degrees
     * @param pmRaCosDec proper motion μα·cos δ, milliarcseconds per year
     * @param pmDec proper motion μδ, milliarcseconds per year
     * @param magnitude visual (V) magnitudes
     * @param colorIndex B−V; NaN where unknown
     * @param hip Hipparcos numbers, 0 where none
     * @param properName common names or null
     * @param designation Bayer/Flamsteed designations and constellation, or null
     */
    @AllowAllocation("made once, when loaded or generated")
    public StarCatalogue(String name, int size, double[] rightAscension, double[] declination, float[] pmRaCosDec,
        float[] pmDec, float[] magnitude, float[] colorIndex, int[] hip, String[] properName, String[] designation) {
        this.name = Objects.requireNonNull(name, "name");
        int shortest = Math.min(Math.min(Math.min(rightAscension.length, declination.length),
            Math.min(pmRaCosDec.length, pmDec.length)), Math.min(Math.min(magnitude.length, colorIndex.length),
            Math.min(hip.length, Math.min(properName.length, designation.length))));
        if (size < 0 || shortest < size) {
            throw new IllegalArgumentException("Arrays shorter than " + size + " stars");
        }
        this.size = size;
        Integer[] order = new Integer[size];
        for (int i = 0; i < size; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Float.compare(magnitude[a], magnitude[b]));
        this.rightAscension = new double[size];
        this.declination = new double[size];
        this.pmRaCosDec = new float[size];
        this.pmDec = new float[size];
        this.magnitude = new float[size];
        this.colorIndex = new float[size];
        this.hip = new int[size];
        this.properName = new String[size];
        this.designation = new String[size];
        for (int i = 0; i < size; i++) {
            int s = order[i];
            this.rightAscension[i] = rightAscension[s];
            this.declination[i] = declination[s];
            this.pmRaCosDec[i] = pmRaCosDec[s];
            this.pmDec[i] = pmDec[s];
            this.magnitude[i] = magnitude[s];
            this.colorIndex[i] = colorIndex[s];
            this.hip[i] = hip[s];
            this.properName[i] = properName[s];
            this.designation[i] = designation[s];
        }
    }

    /** Where it came from, e.g. its asset path. */
    public String name() {
        return name;
    }

    public int size() {
        return size;
    }

    /** J2000.0 right ascension of star {@code i}, degrees. */
    public double rightAscension(int i) {
        return rightAscension[Objects.checkIndex(i, size)];
    }

    /** J2000.0 declination, degrees. */
    public double declination(int i) {
        return declination[Objects.checkIndex(i, size)];
    }

    /** Proper motion μα·cos δ, mas/yr. */
    public float pmRaCosDec(int i) {
        return pmRaCosDec[Objects.checkIndex(i, size)];
    }

    /** Proper motion μδ, mas/yr. */
    public float pmDec(int i) {
        return pmDec[Objects.checkIndex(i, size)];
    }

    /** Visual magnitude. */
    public float magnitude(int i) {
        return magnitude[Objects.checkIndex(i, size)];
    }

    /** B−V colour index, NaN where unknown. */
    public float colorIndex(int i) {
        return colorIndex[Objects.checkIndex(i, size)];
    }

    /** Hipparcos number, 0 where none. */
    public int hip(int i) {
        return hip[Objects.checkIndex(i, size)];
    }

    /** Common name, or null. */
    public String properName(int i) {
        return properName[Objects.checkIndex(i, size)];
    }

    /** Bayer/Flamsteed designation, or null. */
    public String designation(int i) {
        return designation[Objects.checkIndex(i, size)];
    }

    /** How many stars are at most {@code limitingMagnitude}: they are the first ones. */
    public int countBrighterThan(double limitingMagnitude) {
        int lo = 0;
        int hi = size;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (magnitude[mid] <= limitingMagnitude) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** The index of the star with this proper name (case-insensitive), or −1. */
    public int find(String proper) {
        for (int i = 0; i < size; i++) {
            if (properName[i] != null && properName[i].equalsIgnoreCase(proper)) {
                return i;
            }
        }
        return -1;
    }

    /** The memory it takes, roughly, for the asset cache's budget. */
    public long byteSize() {
        return 64L + size * (8L + 8 + 4 + 4 + 4 + 4 + 4 + 8 + 8);
    }

    @Override
    public String toString() {
        return name + " (" + size + " stars)";
    }
}
