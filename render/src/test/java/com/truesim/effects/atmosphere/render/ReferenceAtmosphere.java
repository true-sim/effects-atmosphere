package com.truesim.effects.atmosphere.render;

import java.util.SplittableRandom;

/**
 * An unbiased Monte Carlo reference of the same atmosphere (the design's validation, §10.1): volumetric path tracing of
 * the spherical shell of {@link AtmosphereModel}'s media, one colour channel at a time. Free flights by delta tracking
 * against a majorant (Woodcock tracking; Novák et al. 2018's survey), real collisions scattering with Rayleigh's or the
 * Cornette-Shanks phase function (sampled through Henyey-Greenstein and weighted by the ratio) or absorbing by the
 * single-scattering albedo, a Lambertian ground of the config's albedo, next-event estimation of the sun (a
 * directional light) at every vertex with the transmittance to it ray-marched in 256 steps and the planet's shadow,
 * every order of scattering (Russian roulette after the third). No look-up tables and no multiple-scattering
 * approximation: what Hillaire 2020's tables are measured against.
 */
final class ReferenceAtmosphere {

    private final AtmosphereModel model;
    private final double majorant;
    private int maxOrder = Integer.MAX_VALUE;
    private double limit = Double.POSITIVE_INFINITY;

    /** Only the first {@code order} orders of scattering (1: single scattering, the ground not bouncing). */
    ReferenceAtmosphere order(int order) {
        maxOrder = order;
        return this;
    }

    /** The camera's ray ends at {@code km} (a black surface there): the aerial perspective's in-scattering. */
    ReferenceAtmosphere limit(double km) {
        limit = km;
        return this;
    }

    ReferenceAtmosphere(AtmosphereModel model) {
        this.model = model;
        double most = 0;
        for (double h = 0; h <= model.topRadius() - model.bottomRadius(); h += 0.05) {
            for (int c = 0; c < 3; c++) {
                most = Math.max(most, model.extinction(h, c));
            }
        }
        majorant = most * 1.01;
    }

    /**
     * The radiance per unit sun illuminance (1/sr) reaching radius {@code r} km (on the y axis) from unit direction
     * ({@code dx}, {@code dy}, {@code dz}), channel {@code c}, the sun towards ({@code sx}, {@code sy}, {@code sz}):
     * the mean of {@code paths} paths.
     */
    double radiance(double r, double dx, double dy, double dz, double sx, double sy, double sz, int c, int paths,
        long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        double sum = 0;
        double[] p = new double[3];
        double[] d = new double[3];
        for (int i = 0; i < paths; i++) {
            p[0] = 0;
            p[1] = r;
            p[2] = 0;
            d[0] = dx;
            d[1] = dy;
            d[2] = dz;
            sum += path(p, d, sx, sy, sz, c, random);
        }
        return sum / paths;
    }

    /** One path's contribution from {@code p} along {@code d} (both changed). */
    private double path(double[] p, double[] d, double sx, double sy, double sz, int c, SplittableRandom random) {
        double throughput = 1;
        double result = 0;
        double bottom = model.bottomRadius();
        double top = model.topRadius();
        for (int bounce = 0; bounce < 256; bounce++) {
            double toGround = AtmosphereModel.raySphere(p[0], p[1], p[2], d[0], d[1], d[2], bottom);
            double toTop = AtmosphereModel.raySphere(p[0], p[1], p[2], d[0], d[1], d[2], top);
            double end = toGround > 1e-9 ? toGround : toTop;
            if (end <= 0) {
                return result; // left the atmosphere
            }
            boolean black = false;
            if (bounce == 0 && end > limit) {
                end = limit;
                black = true;
            }
            // Delta tracking to a real collision or the end
            double t = 0;
            boolean collided = false;
            while (true) {
                t -= Math.log(1 - random.nextDouble()) / majorant;
                if (t >= end) {
                    break;
                }
                double x = p[0] + d[0] * t;
                double y = p[1] + d[1] * t;
                double z = p[2] + d[2] * t;
                double h = Math.sqrt(x * x + y * y + z * z) - bottom;
                if (random.nextDouble() < model.extinction(h, c) / majorant) {
                    collided = true;
                    break;
                }
            }
            if (!collided) {
                if (black) {
                    return result;
                }
                if (!(toGround > 1e-9) || bounce + 1 >= maxOrder) {
                    return result; // into space
                }
                // The ground: next-event estimation of the sun, then a cosine-weighted bounce times the albedo
                p[0] += d[0] * end;
                p[1] += d[1] * end;
                p[2] += d[2] * end;
                double l = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
                double nx = p[0] / l;
                double ny = p[1] / l;
                double nz = p[2] / l;
                p[0] = nx * (bottom + 1e-7);
                p[1] = ny * (bottom + 1e-7);
                p[2] = nz * (bottom + 1e-7);
                double albedo = model.groundAlbedo();
                double cos = nx * sx + ny * sy + nz * sz;
                if (cos > 0) {
                    result += throughput * albedo / Math.PI * cos * sunTransmittance(p, sx, sy, sz, c);
                }
                throughput *= albedo;
                cosineDirection(nx, ny, nz, d, random);
            } else {
                p[0] += d[0] * t;
                p[1] += d[1] * t;
                p[2] += d[2] * t;
                double h = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]) - bottom;
                double rayleigh = model.rayleighScattering(h, c);
                double mie = model.mieScattering(h, c);
                double extinction = model.extinction(h, c);
                throughput *= (rayleigh + mie) / extinction; // the single-scattering albedo
                // Next-event estimation of the sun through both phase functions
                double cosSun = d[0] * sx + d[1] * sy + d[2] * sz;
                double phase = (rayleigh * AtmosphereModel.rayleighPhase(cosSun) + mie
                    * AtmosphereModel.cornetteShanksPhase(model.mieAnisotropy(), cosSun)) / (rayleigh + mie);
                result += throughput * phase * sunTransmittance(p, sx, sy, sz, c);
                if (bounce + 1 >= maxOrder) {
                    return result;
                }
                // A new direction: Rayleigh or Mie by their shares
                if (random.nextDouble() < rayleigh / (rayleigh + mie)) {
                    rayleighDirection(d, random);
                } else {
                    double g = model.mieAnisotropy();
                    double before0 = d[0];
                    double before1 = d[1];
                    double before2 = d[2];
                    henyeyGreensteinDirection(g, d, random);
                    double cos = before0 * d[0] + before1 * d[1] + before2 * d[2];
                    throughput *= AtmosphereModel.cornetteShanksPhase(g, cos) / henyeyGreenstein(g, cos);
                }
            }
            if (bounce >= 3) {
                double keep = Math.min(0.95, throughput);
                if (random.nextDouble() >= keep) {
                    return result;
                }
                throughput /= keep;
            }
        }
        return result;
    }

    /** The transmittance from {@code p} to the sun: 0 behind the planet, else ray-marched to the top in 256 steps. */
    private double sunTransmittance(double[] p, double sx, double sy, double sz, int c) {
        double bottom = model.bottomRadius();
        if (AtmosphereModel.raySphere(p[0], p[1], p[2], sx, sy, sz, bottom) > 1e-9) {
            return 0;
        }
        double end = AtmosphereModel.raySphere(p[0], p[1], p[2], sx, sy, sz, model.topRadius());
        if (end <= 0) {
            return 1;
        }
        int steps = 256;
        double dt = end / steps;
        double depth = 0;
        for (int i = 0; i < steps; i++) {
            double t = (i + 0.5) * dt;
            double x = p[0] + sx * t;
            double y = p[1] + sy * t;
            double z = p[2] + sz * t;
            depth += model.extinction(Math.sqrt(x * x + y * y + z * z) - bottom, c) * dt;
        }
        return Math.exp(-depth);
    }

    static double henyeyGreenstein(double g, double cos) {
        double d = 1 + g * g - 2 * g * cos;
        return (1 - g * g) / (4 * Math.PI * d * Math.sqrt(d));
    }

    /** {@code d} turned by a Henyey-Greenstein sample about itself. */
    private static void henyeyGreensteinDirection(double g, double[] d, SplittableRandom random) {
        double u = random.nextDouble();
        double cos;
        if (Math.abs(g) < 1e-4) {
            cos = 1 - 2 * u;
        } else {
            double s = (1 - g * g) / (1 - g + 2 * g * u);
            cos = (1 + g * g - s * s) / (2 * g);
        }
        turn(d, cos, random);
    }

    /** {@code d} turned by a sample of Rayleigh's phase function (inverted in closed form). */
    private static void rayleighDirection(double[] d, SplittableRandom random) {
        double u = 4 * random.nextDouble() - 2;
        double a = Math.cbrt(u + Math.sqrt(u * u + 1));
        double cos = a - 1 / a;
        turn(d, Math.max(-1, Math.min(1, cos)), random);
    }

    /** {@code d} turned to angle {@code cos} from itself, at a uniform azimuth. */
    private static void turn(double[] d, double cos, SplittableRandom random) {
        double sin = Math.sqrt(Math.max(0, 1 - cos * cos));
        double phi = 2 * Math.PI * random.nextDouble();
        double[] t = new double[3];
        double[] b = new double[3];
        basis(d[0], d[1], d[2], t, b);
        double cp = Math.cos(phi) * sin;
        double sp = Math.sin(phi) * sin;
        double x = t[0] * cp + b[0] * sp + d[0] * cos;
        double y = t[1] * cp + b[1] * sp + d[1] * cos;
        double z = t[2] * cp + b[2] * sp + d[2] * cos;
        double l = Math.sqrt(x * x + y * y + z * z);
        d[0] = x / l;
        d[1] = y / l;
        d[2] = z / l;
    }

    private static void cosineDirection(double nx, double ny, double nz, double[] d, SplittableRandom random) {
        double u = random.nextDouble();
        double phi = 2 * Math.PI * random.nextDouble();
        double sin = Math.sqrt(u);
        double cos = Math.sqrt(1 - u);
        double[] t = new double[3];
        double[] b = new double[3];
        basis(nx, ny, nz, t, b);
        double x = t[0] * Math.cos(phi) * sin + b[0] * Math.sin(phi) * sin + nx * cos;
        double y = t[1] * Math.cos(phi) * sin + b[1] * Math.sin(phi) * sin + ny * cos;
        double z = t[2] * Math.cos(phi) * sin + b[2] * Math.sin(phi) * sin + nz * cos;
        d[0] = x;
        d[1] = y;
        d[2] = z;
    }

    private static void basis(double x, double y, double z, double[] t, double[] b) {
        double ax = Math.abs(x) < 0.9 ? 1 : 0;
        double ay = Math.abs(x) < 0.9 ? 0 : 1;
        // t = normalize(a x n)
        double tx = ay * z;
        double ty = -ax * z;
        double tz = ax * y - ay * x;
        double l = Math.sqrt(tx * tx + ty * ty + tz * tz);
        t[0] = tx / l;
        t[1] = ty / l;
        t[2] = tz / l;
        b[0] = y * t[2] - z * t[1];
        b[1] = z * t[0] - x * t[2];
        b[2] = x * t[1] - y * t[0];
    }
}
