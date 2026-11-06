package com.truesim.effects.atmosphere.sky;

import com.truesim.effects.atmosphere.Quality;
import com.truesim.effects.atmosphere.ephemeris.AstroTime;
import com.truesim.effects.atmosphere.ephemeris.CelestialBody;
import com.truesim.effects.atmosphere.ephemeris.Ephemeris;
import com.truesim.effects.atmosphere.ephemeris.EphemerisConfig;
import com.truesim.effects.atmosphere.ephemeris.Galactic;
import com.truesim.effects.atmosphere.ephemeris.Observer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * CPU visualisations of the sky data (TRUESIM_SCREENSHOTS=true; PNGs into TRUESIM_SCREENSHOTS_DIR or build/screenshots):
 * not the renderer, which does not exist yet, but pictures of what it will be fed.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_SCREENSHOTS", matches = "true")
class SkyScreenshots {

    static Path dir() throws Exception {
        String d = System.getenv("TRUESIM_SCREENSHOTS_DIR");
        Path p = Path.of(d == null ? "build/screenshots" : d);
        Files.createDirectories(p);
        return p;
    }

    static Graphics2D graphics(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g;
    }

    private static StarCatalogue catalogue() throws Exception {
        try (InputStream in = SkyAssets.class.getResourceAsStream(
            "/com/truesim/effects/atmosphere/sky/stars/hyg-v41-mag7.csv")) {
            return StarCatalogueImporter.read(in, SkyAssets.BUNDLED_CATALOGUE);
        }
    }

    /** Chart position of an (east, north, up) direction: azimuthal equidistant, north up, east left. */
    private static double[] chart(double e, double n, double u, double cx, double cy, double radius) {
        double alt = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, u))));
        double az = Math.atan2(e, n);
        double r = (90 - alt) / 90 * radius;
        return new double[] {cx - r * Math.sin(az), cy - r * Math.cos(az), alt};
    }

    /** Draws a lit moon disc: bright limb towards {@code limbAngle} (radians, chart direction), phase angle i. */
    static void moon(BufferedImage image, double cx, double cy, double radius, double limbX, double limbY,
        double phaseAngleDeg) {
        double cosI = Math.cos(Math.toRadians(phaseAngleDeg));
        double len = Math.hypot(limbX, limbY);
        double lx = limbX / len;
        double ly = limbY / len;
        int r = (int) Math.ceil(radius);
        for (int y = -r; y <= r; y++) {
            for (int x = -r; x <= r; x++) {
                double dx = x / radius;
                double dy = y / radius;
                double rr = dx * dx + dy * dy;
                if (rr > 1) {
                    continue;
                }
                double along = dx * lx + dy * ly;
                double across = -dx * ly + dy * lx;
                double half = Math.sqrt(Math.max(0, 1 - across * across));
                boolean lit = along >= -cosI * half;
                int px = (int) Math.round(cx) + x;
                int py = (int) Math.round(cy) + y;
                if (px < 0 || py < 0 || px >= image.getWidth() || py >= image.getHeight()) {
                    continue;
                }
                int v = lit ? 235 : 38;
                image.setRGB(px, py, new Color(v, v, (int) (v * 0.93)).getRGB());
            }
        }
    }

    @Test
    void nightSkyOverVienna() throws Exception {
        // Vienna, 2026-11-06 22:00 CET = 21:00 UTC, as asked; then before dawn next morning, when the moon is up.
        nightSky(AstroTime.julianDay(2026, 11, 6, 21, 0, 0), "2026-11-06 22:00 CET (21:00 UTC)",
            "1-night-sky-vienna.png");
        nightSky(AstroTime.julianDay(2026, 11, 7, 4, 30, 0), "2026-11-07 05:30 CET (04:30 UTC), moon up",
            "1b-night-sky-vienna-morning.png");
    }

    private void nightSky(double jd, String when, String file) throws Exception {
        int size = 1000;
        int top = 70;
        BufferedImage image = new BufferedImage(size, size + top + 90, BufferedImage.TYPE_INT_RGB);
        double cx = size / 2.0;
        double cy = top + size / 2.0;
        double radius = size / 2.0 - 30;
        // Vienna, 2026-11-06 22:00 CET = 21:00 UTC.
        SkySettings settings = SkySettings.forQuality(Quality.ULTRA, new Observer(48.2082, 16.3738, 190));
        NightSky sky = new NightSky(settings);
        sky.setCatalogue(catalogue());
        sky.update(jd);
        Ephemeris eph = sky.ephemeris();
        double[] m = eph.equatorialToHorizon();
        Airglow airglow = settings.airglow();
        MilkyWay milkyWay = settings.milkyWay();

        // Background: airglow (van Rhijn) + the analytic Milky Way, log tone mapped.
        double lo = Math.log10(1.0e-4);
        double hi = Math.log10(1.5e-3);
        double[] eq = new double[3];
        double[] gal = new double[3];
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < size; x++) {
                double dx = x - cx;
                double dy = y - cy;
                double r = Math.hypot(dx, dy) / radius;
                if (r > 1) {
                    continue;
                }
                double alt = 90 * (1 - r);
                double az = Math.atan2(-dx, -dy);
                double ca = Math.cos(Math.toRadians(alt));
                double e = ca * Math.sin(az);
                double n = ca * Math.cos(az);
                double u = Math.sin(Math.toRadians(alt));
                // ENU → equatorial of date: the transpose of the horizon matrix.
                eq[0] = m[0] * e + m[3] * n + m[6] * u;
                eq[1] = m[1] * e + m[4] * n + m[7] * u;
                eq[2] = m[2] * e + m[5] * n + m[8] * u;
                Galactic.fromEquatorial(eq, 0, gal, 0);
                double mw = milkyWay.luminance(Galactic.longitude(gal, 0), Galactic.latitude(gal, 0));
                double ag = airglow.luminance(90 - alt, 6371);
                double total = mw + ag;
                double t = Math.max(0, Math.min(1, (Math.log10(total) - lo) / (hi - lo)));
                double wa = ag / total;
                // Airglow keeps a faint green tint, the Milky Way a neutral, slightly blue one.
                int red = (int) (255 * t * (0.62 * (1 - wa) + (0.3 + 0.2 * airglow.red()) * wa));
                int green = (int) (255 * t * (0.66 * (1 - wa) + (0.3 + 0.2 * airglow.green()) * wa));
                int blue = (int) (255 * t * (0.78 * (1 - wa) + (0.3 + 0.2 * airglow.blue()) * wa));
                image.setRGB(x, y, new Color(Math.min(255, red), Math.min(255, green), Math.min(255, blue)).getRGB());
            }
        }

        Graphics2D g = graphics(image);
        g.setColor(new Color(90, 90, 110));
        g.setStroke(new BasicStroke(1f));
        for (int alt = 0; alt < 90; alt += 30) {
            double r = (90 - alt) / 90.0 * radius;
            g.drawOval((int) (cx - r), (int) (cy - r), (int) (2 * r), (int) (2 * r));
        }
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        g.setColor(Color.LIGHT_GRAY);
        g.drawString("N", (int) cx - 6, (int) (cy - radius - 8));
        g.drawString("S", (int) cx - 6, (int) (cy + radius + 22));
        g.drawString("E", (int) (cx - radius - 24), (int) cy + 6);
        g.drawString("W", (int) (cx + radius + 8), (int) cy + 6);

        // Stars: size and brightness from magnitude, colour from B−V.
        StarField field = sky.stars();
        float[] d = field.directions();
        float[] c = field.colors();
        StarCatalogue cat = field.catalogue();
        int drawn = 0;
        for (int i = field.count() - 1; i >= 0; i--) {
            double e = m[0] * d[3 * i] + m[1] * d[3 * i + 1] + m[2] * d[3 * i + 2];
            double n = m[3] * d[3 * i] + m[4] * d[3 * i + 1] + m[5] * d[3 * i + 2];
            double u = m[6] * d[3 * i] + m[7] * d[3 * i + 1] + m[8] * d[3 * i + 2];
            if (u < 0) {
                continue;
            }
            double[] p = chart(e, n, u, cx, cy, radius);
            double mag = cat.magnitude(i);
            // Size and brightness follow the illuminance (10^(−0.4 m)); colour half way to white, as the eye sees.
            double flux = Math.pow(10, -0.4 * (mag - 6.5));
            double r = Math.min(5.5, 0.45 + 0.5 * Math.pow(flux, 0.25));
            float max = Math.max(c[3 * i], Math.max(c[3 * i + 1], c[3 * i + 2]));
            float bright = (float) Math.min(1, 0.18 + 0.12 * Math.log10(flux) * 2.2);
            float sat = 0.5f;
            splat(image, p[0], p[1], r, (sat * c[3 * i] / max + 1 - sat) * bright,
                (sat * c[3 * i + 1] / max + 1 - sat) * bright, (sat * c[3 * i + 2] / max + 1 - sat) * bright);
            drawn++;
        }
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        String[] names = {"Betelgeuse", "Rigel", "Bellatrix", "Alnilam", "Sirius", "Aldebaran", "Capella", "Polaris",
            "Dubhe", "Alkaid", "Vega", "Deneb", "Altair", "Procyon", "Castor", "Pollux", "Regulus", "Arcturus"};
        for (String name : names) {
            int i = cat.find(name);
            if (i < 0 || i >= field.count()) {
                continue;
            }
            double e = m[0] * d[3 * i] + m[1] * d[3 * i + 1] + m[2] * d[3 * i + 2];
            double n = m[3] * d[3 * i] + m[4] * d[3 * i + 1] + m[5] * d[3 * i + 2];
            double u = m[6] * d[3 * i] + m[7] * d[3 * i + 1] + m[8] * d[3 * i + 2];
            if (u < 0) {
                continue;
            }
            double[] p = chart(e, n, u, cx, cy, radius);
            g.setColor(new Color(170, 190, 220));
            g.drawString(name, (int) p[0] + 6, (int) p[1] - 4);
        }

        // The moon, ten times its size, lit side towards the sun.
        CelestialBody moonBody = eph.moon();
        String moonText;
        if (moonBody.altitude() > 0) {
            double[] p = chart(moonBody.east(), moonBody.north(), moonBody.up(), cx, cy, radius);
            // Celestial north and east at the moon, in chart coordinates, for the bright limb's position angle.
            double[] pn = towards(eph, moonBody, 0.5, 0, cx, cy, radius);
            double[] pe = towards(eph, moonBody, 0, 0.5, cx, cy, radius);
            double nx = pn[0] - p[0];
            double ny = pn[1] - p[1];
            double ex = pe[0] - p[0];
            double ey = pe[1] - p[1];
            double nl = Math.hypot(nx, ny);
            double el = Math.hypot(ex, ey);
            double chi = Math.toRadians(eph.moonPhase().brightLimb());
            double lx = Math.cos(chi) * nx / nl + Math.sin(chi) * ex / el;
            double ly = Math.cos(chi) * ny / nl + Math.sin(chi) * ey / el;
            moon(image, p[0], p[1], moonBody.angularRadius(1737.4) / 90 * radius * 10, lx, ly,
                eph.moonPhase().phaseAngle());
            g.setColor(Color.WHITE);
            g.drawString("Moon (drawn 10× size)", (int) p[0] + 16, (int) p[1] + 22);
            moonText = String.format("Moon: alt %.1f°, az %.1f°, %.0f%% lit, %s", moonBody.altitude(),
                moonBody.azimuth(), 100 * eph.moonPhase().illuminatedFraction(),
                eph.moonPhase().waxing() ? "waxing" : "waning");
        } else {
            moonText = String.format("Moon below the horizon (alt %.1f°), %.0f%% lit, %s — inset: its phase, north up",
                moonBody.altitude(), 100 * eph.moonPhase().illuminatedFraction(),
                eph.moonPhase().waxing() ? "waxing" : "waning");
            double chi = Math.toRadians(eph.moonPhase().brightLimb());
            moon(image, size - 70, top + size + 20, 40, -Math.sin(chi), -Math.cos(chi), eph.moonPhase().phaseAngle());
        }

        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        g.drawString("Night sky over Vienna, " + when + " — CPU preview of the sky data", 20, 30);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        g.drawString(String.format("HYG v4.1: %d of %d stars (mag ≤ 7) above the horizon, B−V colours; analytic Milky "
            + "Way + airglow; fisheye, zenith centre, N up, E left", drawn, field.count()), 20, 54);
        int y0 = top + size + 20;
        g.drawString(moonText, 20, y0);
        g.drawString(String.format("Sun: alt %.1f° (astronomical night below −18°)   local sidereal time %.2f h",
            eph.sun().altitude(), eph.localSiderealTime() / 15), 20, y0 + 20);
        g.drawString(String.format("Airglow: %.1f mag/arcsec² at zenith, %.0f km layer   Milky Way: %.1f mag/arcsec² "
            + "at the centre, |b| scale %.0f°", airglow.zenithMagnitude(), airglow.layerHeightKm(),
            milkyWay.centreMagnitude(), milkyWay.latitudeScaleDegrees()), 20, y0 + 40);
        g.dispose();
        ImageIO.write(image, "png", dir().resolve(file).toFile());
    }

    /** Adds a soft disc of radius {@code r} and colour (0–1) to the image: stars never darken the sky. */
    private static void splat(BufferedImage image, double x, double y, double r, double red, double green,
        double blue) {
        int reach = (int) Math.ceil(r + 1.5);
        for (int py = (int) y - reach; py <= (int) y + reach; py++) {
            for (int px = (int) x - reach; px <= (int) x + reach; px++) {
                if (px < 0 || py < 0 || px >= image.getWidth() || py >= image.getHeight()) {
                    continue;
                }
                double d = Math.hypot(px + 0.5 - x, py + 0.5 - y);
                double w = Math.max(0, Math.min(1, r + 0.5 - d));
                if (w <= 0) {
                    continue;
                }
                int rgb = image.getRGB(px, py);
                int nr = (int) Math.min(255, ((rgb >> 16) & 255) + 255 * red * w);
                int ng = (int) Math.min(255, ((rgb >> 8) & 255) + 255 * green * w);
                int nb = (int) Math.min(255, (rgb & 255) + 255 * blue * w);
                image.setRGB(px, py, (nr << 16) | (ng << 8) | nb);
            }
        }
    }

    /** The chart point a small step north (dDec) or east (dRa, degrees) of a body. */
    private static double[] towards(Ephemeris eph, CelestialBody body, double dDec, double dRa, double cx, double cy,
        double radius) {
        double[] v = new double[3];
        Galactic.equatorialVector(body.rightAscension() + dRa, body.declination() + dDec, v, 0);
        double[] m = eph.equatorialToHorizon();
        return chart(m[0] * v[0] + m[1] * v[1] + m[2] * v[2], m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
            m[6] * v[0] + m[7] * v[1] + m[8] * v[2], cx, cy, radius);
    }

    @Test
    void moonPhasesOverAMonth() throws Exception {
        int cols = 10;
        int rows = 3;
        int cell = 120;
        BufferedImage image = new BufferedImage(cols * cell, rows * (cell + 40) + 60, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = graphics(image);
        g.setColor(new Color(12, 14, 22));
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        g.drawString("Moon phases, 2026-11-01 to 11-30, 00:00 UTC, geocentric (north up; bright limb from position "
            + "angle χ)", 12, 28);
        Ephemeris eph = new Ephemeris(EphemerisConfig.defaults().withTopocentric(false));
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        for (int day = 0; day < 30; day++) {
            eph.update(AstroTime.julianDay(2026, 11, 1 + day, 0, 0, 0));
            int col = day % cols;
            int row = day / cols;
            double x = col * cell + cell / 2.0;
            double y = 60 + row * (cell + 40) + cell / 2.0;
            double chi = Math.toRadians(eph.moonPhase().brightLimb());
            // North up, east left (as seen in the sky): position angle χ counted from north through east.
            moon(image, x, y, 44, -Math.sin(chi), -Math.cos(chi), eph.moonPhase().phaseAngle());
            g.setColor(Color.LIGHT_GRAY);
            g.drawString(String.format("11-%02d  %3.0f%%", 1 + day, 100 * eph.moonPhase().illuminatedFraction()),
                (int) x - 40, (int) y + 62);
            g.drawString(String.format("χ %3.0f°", eph.moonPhase().brightLimb()), (int) x - 22, (int) y + 76);
        }
        g.dispose();
        ImageIO.write(image, "png", dir().resolve("2-moon-phases-november-2026.png").toFile());
    }

    @Test
    void sunPaths() throws Exception {
        double[] latitudes = {0, 48.2, 66.6, 78.2, -33.9};
        String[] places = {"Equator", "Vienna 48.2° N", "Arctic Circle 66.6° N", "Svalbard 78.2° N", "Sydney 33.9° S"};
        int[][] dates = {{2026, 3, 20}, {2026, 6, 21}, {2026, 12, 21}};
        Color[] colors = {new Color(120, 200, 120), new Color(240, 170, 60), new Color(110, 160, 250)};
        String[] labels = {"Mar 20 equinox", "Jun 21 solstice", "Dec 21 solstice"};
        int pw = 560;
        int ph = 300;
        BufferedImage image = new BufferedImage(2 * pw + 30, 3 * (ph + 40) + 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = graphics(image);
        g.setColor(new Color(250, 250, 248));
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        g.drawString("Sun paths: apparent altitude vs azimuth over one day (local meridian, 10-minute steps, with "
            + "refraction)", 12, 28);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        for (int k = 0; k < 3; k++) {
            g.setColor(colors[k]);
            g.fillRect(12 + k * 200, 42, 14, 14);
            g.setColor(Color.BLACK);
            g.drawString(labels[k], 32 + k * 200, 54);
        }
        Ephemeris eph = new Ephemeris(EphemerisConfig.defaults());
        for (int p = 0; p < latitudes.length; p++) {
            int ox = 15 + (p % 2) * (pw + 10);
            int oy = 80 + (p / 2) * (ph + 40);
            g.setColor(new Color(235, 238, 245));
            g.fillRect(ox, oy, pw, ph);
            g.setColor(Color.GRAY);
            int zero = oy + (int) (ph * 90 / 120.0);
            g.drawLine(ox, zero, ox + pw, zero);
            for (int az = 0; az <= 360; az += 90) {
                int x = ox + az * pw / 360;
                g.drawLine(x, oy, x, oy + ph);
                g.drawString(new String[] {"N", "E", "S", "W", "N"}[az / 90], x + 3, oy + ph - 4);
            }
            for (int alt = -30; alt <= 90; alt += 30) {
                int yy = oy + (int) (ph * (90 - alt) / 120.0);
                g.drawString(alt + "°", ox + 3, yy - 2);
            }
            g.setColor(Color.BLACK);
            g.drawString(places[p], ox + 40, oy + 16);
            eph.setObserver(new Observer(latitudes[p], 0, 0));
            g.setClip(ox, oy, pw, ph);
            for (int k = 0; k < dates.length; k++) {
                g.setColor(colors[k]);
                g.setStroke(new BasicStroke(2f));
                double lastX = Double.NaN;
                double lastY = 0;
                for (int minute = 0; minute <= 1440; minute += 10) {
                    eph.update(AstroTime.julianDay(dates[k][0], dates[k][1], dates[k][2]) + minute / 1440.0);
                    double x = ox + eph.sun().azimuth() / 360 * pw;
                    double y = oy + ph * (90 - eph.sun().altitude()) / 120.0;
                    if (!Double.isNaN(lastX) && Math.abs(x - lastX) < pw / 4.0) {
                        g.drawLine((int) lastX, (int) lastY, (int) x, (int) y);
                    }
                    lastX = x;
                    lastY = y;
                }
            }
            g.setStroke(new BasicStroke(1f));
            g.setClip(null);
        }
        g.setColor(Color.DARK_GRAY);
        int ny = 80 + 2 * (ph + 40) + 40;
        g.drawString("Altitude −30° to 90°; curves leave the panel below −30°.", 600, ny);
        g.drawString("Near the zenith (equator, equinox) the azimuth swings", 600, ny + 20);
        g.drawString("from east to west within minutes: the gap at the top.", 600, ny + 40);
        g.drawString("Longitude 0°, times in UTC; ephemeris: Meeus ch. 25.", 600, ny + 60);
        g.dispose();
        ImageIO.write(image, "png", dir().resolve("3-sun-paths.png").toFile());
    }
}
