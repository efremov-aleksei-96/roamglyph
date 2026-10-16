package com.sensareth.roamglyph.map;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Geographic mask contour regularization, NOT merely H3-edge transparency.
 *
 * Two separable uniform-kernel passes smooth the actual 2-D occupancy before
 * thresholding it into a new silhouette. The result is clipped to a small
 * (world-metre-derived) exterior budget around H3 + accepted GPS ribbons.
 *
 * Working raster tiles include the entire 2*radius + exterior halo. Identical
 * geographic samples therefore yield identical values at neighbouring tile
 * seams. O(width*height), with no per-pixel radius loops.
 */
final class FogOrganicContour {
    private FogOrganicContour() {}

    static void renderInPlace(int[] pixels, int width, int height, int radius,
                              int maxExteriorPixels, BooleanSupplier cancel) {
        if (pixels == null || cancel == null || width < 1 || height < 1
                || (long) width * height != pixels.length
                || radius < 1 || maxExteriorPixels < 0) {
            throw new IllegalArgumentException("Invalid organic contour input");
        }
        final int len = pixels.length;
        byte[] a = new byte[len];
        byte[] b = new byte[len];
        for (int i = 0; i < len; i++) a[i] = (byte) (pixels[i] >>> 24);

        // Two area-normalized separable box passes approximate a Gaussian.
        // Unlike V2's exact-alpha gate, this changes the GEOMETRIC shape:
        // inward H3 tips recede and small gaps between neighbouring cells fill.
        for (int pass = 0; pass < 2; pass++) {
            horizontal(a, b, width, height, radius, cancel);
            vertical(b, a, width, height, radius, cancel);
        }

        // A binary summed-area table enforces the maximum outward reveal.
        // This is a strict display-only bound; nothing is written to Room/H3.
        int stride = width + 1;
        int[] integral = new int[(height + 1) * stride];
        for (int y = 1; y <= height; y++) {
            check(cancel, y);
            int total = 0;
            for (int x = 1; x <= width; x++) {
                int alpha = pixels[(y - 1) * width + x - 1] >>> 24;
                total += alpha >= 16 ? 1 : 0;
                integral[y * stride + x] = integral[(y - 1) * stride + x] + total;
            }
        }

        // Source already includes a rounded, real-world-width GPS path when
        // available, so the H3 teeth cannot override a smooth route ribbon.
        for (int y = 0; y < height; y++) {
            check(cancel, y);
            int top = Math.max(0, y - maxExteriorPixels);
            int bottom = Math.min(height, y + maxExteriorPixels + 1);
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int index = row + x;
                int alpha = pixels[index] >>> 24;
                if (alpha < 16) {
                    int left = Math.max(0, x - maxExteriorPixels);
                    int right = Math.min(width, x + maxExteriorPixels + 1);
                    int near = integral[bottom * stride + right]
                            - integral[top * stride + right]
                            - integral[bottom * stride + left]
                            + integral[top * stride + left];
                    if (near == 0) {
                        pixels[index] = 0;
                        continue;
                    }
                }
                float occupancy = (a[index] & 255) / 255f;
                // Transition width is narrow relative to the shape kernel.
                // The geometric outline comes from occupancy=~0.48 instead
                // of the original stepped hex perimeter.
                float t = (occupancy - 0.28f) / 0.40f;
                t = Math.max(0f, Math.min(1f, t));
                float reveal = t * t * (3f - 2f * t);
                pixels[index] = (Math.round(255f * reveal) << 24) | 0xFFFFFF;
            }
        }
        if (cancel.getAsBoolean()) throw new CancellationException("Superseded organic fog");
    }

    private static void horizontal(byte[] src, byte[] dst, int w, int h, int r,
                                   BooleanSupplier cancel) {
        int span = r * 2 + 1;
        for (int y = 0; y < h; y++) {
            check(cancel, y);
            int row = y * w;
            int sum = 0;
            for (int dx = -r; dx <= r; dx++) {
                sum += src[row + clamp(dx, w)] & 255;
            }
            for (int x = 0; x < w; x++) {
                dst[row + x] = (byte) ((sum + span / 2) / span);
                sum += (src[row + clamp(x + r + 1, w)] & 255)
                        - (src[row + clamp(x - r, w)] & 255);
            }
        }
    }

    private static void vertical(byte[] src, byte[] dst, int w, int h, int r,
                                 BooleanSupplier cancel) {
        int span = r * 2 + 1;
        for (int x = 0; x < w; x++) {
            if ((x & 31) == 0) check(cancel, x);
            int sum = 0;
            for (int dy = -r; dy <= r; dy++) {
                sum += src[clamp(dy, h) * w + x] & 255;
            }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = (byte) ((sum + span / 2) / span);
                sum += (src[clamp(y + r + 1, h) * w + x] & 255)
                        - (src[clamp(y - r, h) * w + x] & 255);
            }
        }
    }

    private static int clamp(int i, int size) {
        return Math.max(0, Math.min(size - 1, i));
    }

    private static void check(BooleanSupplier cancel, int counter) {
        if ((counter & 31) == 0 && (Thread.currentThread().isInterrupted()
                || cancel.getAsBoolean())) {
            throw new CancellationException("Superseded organic fog");
        }
    }
}
