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

        // A square neighbourhood can disclose up to sqrt(2) times the
        // promised exterior radius at diagonal corners. Use the exact
        // squared Euclidean distance to the ORIGINAL coverage instead.
        // The transform runs in O(width * height); history is untouched.
        int[] distanceSquared = maxExteriorPixels == 0 ? null
                : squaredDistanceToSource(pixels, width, height, cancel);
        long exteriorSquared = (long) maxExteriorPixels * maxExteriorPixels;

        // Source already includes a rounded, real-world-width GPS path when
        // available, so the H3 teeth cannot override a smooth route ribbon.
        for (int y = 0; y < height; y++) {
            check(cancel, y);
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int index = row + x;
                int alpha = pixels[index] >>> 24;
                if (alpha < 16 && (maxExteriorPixels == 0
                        || distanceSquared[index] > exteriorSquared)) {
                    pixels[index] = 0;
                    continue;
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

    private static int[] squaredDistanceToSource(int[] pixels, int w, int h,
                                                  BooleanSupplier cancel) {
        final int far = 100_000_000;
        int[] squared = new int[w * h];
        int span = Math.max(w, h);
        int[] input = new int[span];
        int[] transformed = new int[span];
        int[] sites = new int[span];
        double[] edges = new double[span + 1];
        for (int y = 0; y < h; y++) {
            check(cancel, y);
            int row = y * w;
            for (int x = 0; x < w; x++) {
                input[x] = (pixels[row + x] >>> 24) >= 16 ? 0 : far;
            }
            transform(input, w, transformed, sites, edges);
            System.arraycopy(transformed, 0, squared, row, w);
        }
        for (int x = 0; x < w; x++) {
            check(cancel, x);
            for (int y = 0; y < h; y++) input[y] = squared[y * w + x];
            transform(input, h, transformed, sites, edges);
            // Each column has already been copied into input; write its
            // final squared distances back in place to save one full int[].
            for (int y = 0; y < h; y++) squared[y * w + x] = transformed[y];
        }
        return squared;
    }

    /** Linear-time 1-D squared Euclidean distance transform. */
    private static void transform(int[] input, int n, int[] out,
                                  int[] sites, double[] edges) {
        int k = 0;
        sites[0] = 0;
        edges[0] = Double.NEGATIVE_INFINITY;
        edges[1] = Double.POSITIVE_INFINITY;
        for (int q = 1; q < n; q++) {
            double intersection;
            do {
                int p = sites[k];
                intersection = ((double) input[q] + (double) q * q
                        - input[p] - (double) p * p) / (2.0 * (q - p));
                if (intersection <= edges[k]) k--;
                else break;
            } while (k >= 0);
            if (k < 0) {
                k = 0;
                sites[0] = q;
                edges[0] = Double.NEGATIVE_INFINITY;
            } else {
                k++;
                sites[k] = q;
                edges[k] = intersection;
            }
            edges[k + 1] = Double.POSITIVE_INFINITY;
        }
        k = 0;
        for (int q = 0; q < n; q++) {
            while (edges[k + 1] < q) k++;
            int d = q - sites[k];
            out[q] = d * d + input[sites[k]];
        }
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
