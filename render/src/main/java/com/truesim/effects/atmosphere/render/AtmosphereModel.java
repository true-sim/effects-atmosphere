package com.truesim.effects.atmosphere.render;

import com.truesim.common.math.Maths;

/**
 * The atmosphere's media on the CPU, in double precision: the same model the shaders evaluate (atmosphere.slang's
 * {@code medium}), for what the host needs each frame (the transmittance from the camera to the sun and the moon: their
 * lights' colours) and for tests. Lengths in km from the planet's centre, coefficients per km. Allocates nothing.
 */
public final class AtmosphereModel {

    /** Steps of an optical depth integral: the transmittance table's 40. */
    public static final int STEPS = 40;

    private final double bottom;
    private final double top;
    private final double[] rayleigh = new double[3];
    private final double[] mieExtinction = new double[3];
    private final double[] mieScattering = new double[3];
    private final double[] ozone = new double[3];
    private final double rayleighHeight;
    private final double mieHeight;
    private final double ozoneCenter;
    private final double ozoneHalfWidth;
    private final double mieG;
    private final double albedo;

    /** The model of {@code config}'s media. */
    public AtmosphereModel(AtmosphereConfig config) {
        bottom = config.planetRadius();
        top = config.topRadius();
        rayleigh[0] = config.rayleighRed();
        rayleigh[1] = config.rayleighGreen();
        rayleigh[2] = config.rayleighBlue();
        for (int c = 0; c < 3; c++) {
            mieScattering[c] = config.mieScattering();
            mieExtinction[c] = config.mieScattering() + config.mieAbsorption();
        }
        ozone[0] = config.ozoneRed();
        ozone[1] = config.ozoneGreen();
        ozone[2] = config.ozoneBlue();
        rayleighHeight = config.rayleighScaleHeight();
        mieHeight = config.mieScaleHeight();
        ozoneCenter = config.ozoneCenter();
        ozoneHalfWidth = config.ozoneHalfWidth();
        mieG = config.mieAnisotropy();
        albedo = config.groundAlbedo();
    }

    public double bottomRadius() {
        return bottom;
    }

    public double topRadius() {
        return top;
    }

    public double groundAlbedo() {
        return albedo;
    }

    public double mieAnisotropy() {
        return mieG;
    }

    /** Rayleigh's density at height {@code h} km (1 at the ground). */
    public double rayleighDensity(double h) {
        return Maths.exp(-Math.max(h, 0) / rayleighHeight);
    }

    /** Mie's density at height {@code h} km. */
    public double mieDensity(double h) {
        return Maths.exp(-Math.max(h, 0) / mieHeight);
    }

    /** Ozone's density at height {@code h} km (1 at its peak): a tent. */
    public double ozoneDensity(double h) {
        return Math.max(0, 1 - Math.abs(h - ozoneCenter) / ozoneHalfWidth);
    }

    /** The extinction at height {@code h} km of channel {@code c}, per km. */
    public double extinction(double h, int c) {
        return rayleigh[c] * rayleighDensity(h) + mieExtinction[c] * mieDensity(h) + ozone[c] * ozoneDensity(h);
    }

    /** Rayleigh scattering at height {@code h} km, channel {@code c}. */
    public double rayleighScattering(double h, int c) {
        return rayleigh[c] * rayleighDensity(h);
    }

    /** Mie scattering at height {@code h} km, channel {@code c}. */
    public double mieScattering(double h, int c) {
        return mieScattering[c] * mieDensity(h);
    }

    /** Rayleigh's phase function. */
    public static double rayleighPhase(double cosTheta) {
        return 3.0 / (16.0 * Maths.PI) * (1 + cosTheta * cosTheta);
    }

    /** The Cornette-Shanks phase function of asymmetry {@code g}. */
    public static double cornetteShanksPhase(double g, double cosTheta) {
        double k = 3.0 / (8.0 * Maths.PI) * (1 - g * g) / (2 + g * g);
        double d = Math.max(1 + g * g - 2 * g * cosTheta, 1e-12);
        return k * (1 + cosTheta * cosTheta) / (d * Math.sqrt(d));
    }

    /**
     * The nearest intersection at t >= 0 of the ray from ({@code ox}, {@code oy}, {@code oz}) along the unit direction
     * ({@code dx}, {@code dy}, {@code dz}) with the sphere of {@code radius} about the centre, or -1.
     */
    public static double raySphere(double ox, double oy, double oz, double dx, double dy, double dz, double radius) {
        double b = ox * dx + oy * dy + oz * dz;
        double c = ox * ox + oy * oy + oz * oz - radius * radius;
        double d = b * b - c;
        if (d < 0) {
            return -1;
        }
        double s = Math.sqrt(d);
        double near = -b - s;
        double far = -b + s;
        return near >= 0 ? near : far >= 0 ? far : -1;
    }

    /**
     * The transmittance from radius {@code r} km along a direction of zenith cosine {@code mu} to the top of the
     * atmosphere, ignoring the ground, into {@code out} (rgb), its optical depth integrated in {@code steps} midpoint
     * steps.
     */
    public void transmittanceToTop(double r, double mu, int steps, double[] out) {
        double sin = Math.sqrt(Math.max(0, 1 - mu * mu));
        double length = Math.max(raySphere(0, r, 0, sin, mu, 0, top), 0);
        double dt = length / steps;
        double d0 = 0;
        double d1 = 0;
        double d2 = 0;
        for (int i = 0; i < steps; i++) {
            double t = (i + 0.5) * dt;
            double x = sin * t;
            double y = r + mu * t;
            double h = Math.sqrt(x * x + y * y) - bottom;
            d0 += extinction(h, 0) * dt;
            d1 += extinction(h, 1) * dt;
            d2 += extinction(h, 2) * dt;
        }
        out[0] = Maths.exp(-d0);
        out[1] = Maths.exp(-d1);
        out[2] = Maths.exp(-d2);
    }

    /**
     * The transmittance of a light of angular radius {@code radius} (radians) seen at zenith cosine {@code mu} from
     * radius {@code r} km, with the planet's shadow: the share of its disc above the horizon (as the shaders take it),
     * into {@code out}.
     */
    public void transmittanceToLight(double r, double mu, double radius, double[] out) {
        double ratio = bottom / r;
        double horizon = -Math.sqrt(Math.max(1 - ratio * ratio, 0));
        double sinHorizon = Math.sqrt(Math.max(1 - horizon * horizon, 0));
        double above = Maths.asin(Maths.clamp(mu * sinHorizon - Math.sqrt(Math.max(1 - mu * mu, 0)) * horizon, -1,
            1));
        double visible = radius > 0 ? Maths.smoothstep(-radius, radius, above) : above >= 0 ? 1 : 0;
        if (visible <= 0) {
            out[0] = 0;
            out[1] = 0;
            out[2] = 0;
            return;
        }
        transmittanceToTop(r, Math.max(mu, horizon), STEPS, out);
        out[0] *= visible;
        out[1] *= visible;
        out[2] *= visible;
    }
}
