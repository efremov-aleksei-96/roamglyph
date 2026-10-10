package com.sensareth.roamglyph.map;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Keep isolated visited H3 islands while replacing the immediate corridor
 * vicinity with a smooth GPS-derived mask.
 *
 * The two-pass distance field is raster-only, and output is ALWAYS bounded
 * by the original exact-H3 alpha from before any filtering.
 */
final class FogGpsBlend {
    private FogGpsBlend() {}

    static void blend(int[] originalH3, int[] h3Feathered, int[] gpsFeathered,
                      int[] rawGps, int width, int height,
                      float cellDiameterPx, BooleanSupplier cancelled) {
        if ((long) width * height != originalH3.length
                || originalH3.length != h3Feathered.length
                || rawGps.length != originalH3.length
                || gpsFeathered.length != originalH3.length) {
            throw new IllegalArgumentException("GPS and H3 mask dimensions differ");
        }
        int n = width * height;
        byte[] distance = new byte[n];
        // 3 chamfer steps per raster pixel; 255 means >=85px distance.
        for (int i = 0; i < n; i++) {
            distance[i] = (byte) (((rawGps[i] >>> 24) >= 24) ? 0 : 255);
        }
        for (int y = 0; y < height; y++) {
            if ((y & 31) == 0 && (Thread.currentThread().isInterrupted()
                    || cancelled.getAsBoolean())) throw new CancellationException();
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int i = row + x;
                int d = distance[i] & 255;
                if (x > 0) d = Math.min(d, (distance[i - 1] & 255) + 3);
                if (y > 0) {
                    d = Math.min(d, (distance[i - width] & 255) + 3);
                    if (x > 0) d = Math.min(d, (distance[i - width - 1] & 255) + 4);
                    if (x + 1 < width) d = Math.min(d, (distance[i - width + 1] & 255) + 4);
                }
                distance[i] = (byte) d;
            }
        }
        for (int y = height - 1; y >= 0; y--) {
            if ((y & 31) == 0 && (Thread.currentThread().isInterrupted()
                    || cancelled.getAsBoolean())) throw new CancellationException();
            int row = y * width;
            for (int x = width - 1; x >= 0; x--) {
                int i = row + x;
                int d = distance[i] & 255;
                if (x + 1 < width) d = Math.min(d, (distance[i + 1] & 255) + 3);
                if (y + 1 < height) {
                    d = Math.min(d, (distance[i + width] & 255) + 3);
                    if (x > 0) d = Math.min(d, (distance[i + width - 1] & 255) + 4);
                    if (x + 1 < width) d = Math.min(d, (distance[i + width + 1] & 255) + 4);
                }
                distance[i] = (byte) d;
            }
        }

        float near = Math.max(2f, Math.min(70f, cellDiameterPx * 1.1f));
        float blendWidth = Math.max(2f, near * 0.75f);
        for (int i = 0; i < n; i++) {
            int original = originalH3[i] >>> 24;
            if (original == 0) {
                gpsFeathered[i] = 0x00FFFFFF;
                continue;
            }
            float d = (distance[i] & 255) / 3f;
            float t = Math.max(0f, Math.min(1f, (d - near) / blendWidth));
            float revealH3 = t * t * (3f - 2f * t);
            int gps = gpsFeathered[i] >>> 24;
            int backup = Math.round((h3Feathered[i] >>> 24) * revealH3);
            int alpha = Math.min(original, Math.max(gps, backup));
            gpsFeathered[i] = (alpha << 24) | 0xFFFFFF;
        }
    }
}
