package com.truesim.effects.atmosphere.sky;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.assets.AssetInput;
import com.truesim.scene.assets.AssetLoader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Reads star catalogues in the HYG database's CSV columns (a header row names them; the order does not matter):
 * {@code ra} (hours) and {@code dec} (degrees) at J2000.0, {@code pmra} (μα·cos δ) and {@code pmdec} in mas/yr,
 * {@code mag} (V), and optionally {@code ci} (B−V, may be empty), {@code hip}, {@code proper} and {@code bf}. The bundled subset and the full HYG file both read. Values hold no quotes or commas (the subset
 * tool strips them). Registered for {@code .csv} by {@link SkyAssets#install}.
 */
public final class StarCatalogueImporter implements AssetLoader<StarCatalogue> {

    @Override
    public StarCatalogue load(AssetInput input) throws IOException {
        try (InputStream in = input.open()) {
            return read(in, input.path() == null ? input.id().toString() : input.path().toString());
        }
    }

    /** Reads a catalogue from {@code in} (not closed), naming it {@code name}. */
    @AllowAllocation("loading")
    public static StarCatalogue read(InputStream in, String name) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String header = reader.readLine();
        if (header == null) {
            throw new IOException("Empty star catalogue: " + name);
        }
        String[] columns = header.replace("\"", "").split(",", -1);
        int ra = required(columns, "ra", name);
        int dec = required(columns, "dec", name);
        int pmra = required(columns, "pmra", name);
        int pmdec = required(columns, "pmdec", name);
        int mag = required(columns, "mag", name);
        int ci = indexOf(columns, "ci");
        int hip = indexOf(columns, "hip");
        int proper = indexOf(columns, "proper");
        int bf = indexOf(columns, "bf");

        int capacity = 1024;
        double[] ras = new double[capacity];
        double[] decs = new double[capacity];
        float[] pmRa = new float[capacity];
        float[] pmDec = new float[capacity];
        float[] mags = new float[capacity];
        float[] bv = new float[capacity];
        int[] hips = new int[capacity];
        String[] names = new String[capacity];
        String[] designations = new String[capacity];
        int n = 0;
        int lineNumber = 1;
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            String[] f = line.replace("\"", "").split(",", -1);
            if (f.length < columns.length) {
                throw new IOException(name + ":" + lineNumber + ": " + f.length + " fields, the header has "
                    + columns.length);
            }
            if (n == capacity) {
                capacity *= 2;
                ras = Arrays.copyOf(ras, capacity);
                decs = Arrays.copyOf(decs, capacity);
                pmRa = Arrays.copyOf(pmRa, capacity);
                pmDec = Arrays.copyOf(pmDec, capacity);
                mags = Arrays.copyOf(mags, capacity);
                bv = Arrays.copyOf(bv, capacity);
                hips = Arrays.copyOf(hips, capacity);
                names = Arrays.copyOf(names, capacity);
                designations = Arrays.copyOf(designations, capacity);
            }
            try {
                ras[n] = Double.parseDouble(f[ra]) * 15.0;
                decs[n] = Double.parseDouble(f[dec]);
                pmRa[n] = parseFloat(f[pmra], 0f);
                pmDec[n] = parseFloat(f[pmdec], 0f);
                mags[n] = Float.parseFloat(f[mag]);
                bv[n] = ci < 0 ? Float.NaN : parseFloat(f[ci], Float.NaN);
                hips[n] = hip < 0 || f[hip].isBlank() ? 0 : Integer.parseInt(f[hip].trim());
            } catch (NumberFormatException e) {
                throw new IOException(name + ":" + lineNumber + ": " + e.getMessage(), e);
            }
            names[n] = proper < 0 || f[proper].isBlank() ? null : f[proper].trim();
            designations[n] = bf < 0 || f[bf].isBlank() ? null : f[bf].trim();
            n++;
        }
        return new StarCatalogue(name, n, ras, decs, pmRa, pmDec, mags, bv, hips, names, designations);
    }

    private static float parseFloat(String text, float empty) {
        return text.isBlank() ? empty : Float.parseFloat(text.trim());
    }

    private static int indexOf(String[] columns, String column) {
        for (int i = 0; i < columns.length; i++) {
            if (columns[i].trim().equalsIgnoreCase(column)) {
                return i;
            }
        }
        return -1;
    }

    private static int required(String[] columns, String column, String name) throws IOException {
        int i = indexOf(columns, column);
        if (i < 0) {
            throw new IOException("Star catalogue " + name + " has no column '" + column + "'");
        }
        return i;
    }
}
