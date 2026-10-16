package com.sensareth.roamglyph.map;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Renderer V2 prototype: an inward, source-clipped signed-distance silhouette.
 *
 * All distances are calculated from an anti-aliased exact H3 occupancy mask.
 * It never turns an unknown source pixel into an explored pixel: every output
 * alpha is bounded by its original exact-coverage alpha. The Euclidean
 * distance transform is O(width * height), with no polygon work per frame.
 *
 * This pure-Java stage is intentionally independent of Android and MapLibre,
 * so geometric behaviour can be regression-tested on the JVM.
 */
final class FogSignedDistanceField {
    private static final int FAR = 1_000_000;

    private FogSignedDistanceField() {}

    static void renderInPlace(int[] argb, int width, int height,
                              int featherPixels, BooleanSupplier cancelled) {
        if (argb == null || cancelled == null || width < 1 || height < 1
                || (long) width * height != argb.length || featherPixels < 1) {
            throw new IllegalArgumentException("Invalid SDF input");
        }
        final int pixels = argb.length;
        int[] squared = new int[pixels];
        int[] distances = new int[pixels];
        int span = Math.max(width, height);
        int[] source = new int[span];
        int[] transformed = new int[span];
        int[] locations = new int[span];
        double[] intersections = new double[span + 1];

        // Nearest unvisited pixel: first solve the horizontal dimension.
        for (int y = 0; y < height; y++) {
            if ((y & 31) == 0) check(cancelled);
            int offset = y * width;
            for (int x = 0; x < width; x++) {
                source[x] = (argb[offset + x] >>> 24) >= 128 ? FAR : 0;
            }
            transform(source, width, transformed, locations, intersections);
            System.arraycopy(transformed, 0, squared, offset, width);
        }
        // Then the vertical dimension: squared Euclidean distance to exterior.
        for (int x = 0; x < width; x++) {
            if ((x & 31) == 0) check(cancelled);
            for (int y = 0; y < height; y++) source[y] = squared[y * width + x];
            transform(source, height, transformed, locations, intersections);
            for (int y = 0; y < height; y++) {
                distances[y * width + x] = transformed[y];
            }
        }

        // Integral-image occupancy gives a stable shape estimate for tips and
        // corners. The raster has an off-tile geographic halo so the sampling
        // is not clamped to rectangular tile boundaries.
        final int stride = width + 1;
        long[] integral = new long[(height + 1) * stride];
        for (int y = 1; y <= height; y++) {
            if ((y & 31) == 0) check(cancelled);
            long row = 0;
            for (int x = 1; x <= width; x++) {
                row += argb[(y - 1) * width + x - 1] >>> 24;
                integral[y * stride + x] = integral[(y - 1) * stride + x] + row;
            }
        }

        int neighbourhood = Math.min(12, Math.max(2, featherPixels * 2));
        float floor = featherPixels <= 1 ? 0.85f : 0.32f;
        for (int y = 0; y < height; y++) {
            if ((y & 31) == 0) check(cancelled);
            int top = Math.max(0, y - neighbourhood);
            int bottom = Math.min(height, y + neighbourhood + 1);
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                int originalAlpha = argb[i] >>> 24;
                if (originalAlpha == 0) {
                    argb[i] = 0;
                    continue;
                }
                int left = Math.max(0, x - neighbourhood);
                int right = Math.min(width, x + neighbourhood + 1);
                long sum = integral[bottom * stride + right]
                        - integral[top * stride + right]
                        - integral[bottom * stride + left]
                        + integral[top * stride + left];
                float occupancy = sum / (255f * (bottom - top) * (right - left));
                float contour = smoothstep(0.15f, 0.80f, occupancy);
                float distance = (float) Math.sqrt(distances[i]);
                float edge = smoothstep(0.4f, featherPixels + 0.6f, distance);
                float openness = Math.max(floor, edge * (0.62f + 0.38f * contour));
                int alpha = Math.min(originalAlpha,
                        Math.round(originalAlpha * openness));
                argb[i] = (alpha << 24) | 0x00FFFFFF;
            }
        }
        check(cancelled);
    }

    private static float smoothstep(float start, float end, float value) {
        float t = Math.max(0f, Math.min(1f, (value - start) / (end - start)));
        return t * t * (3f - 2f * t);
    }

    /**
     * One-dimensional lower envelope of squared parabolas (Felzenszwalb EDT).
     * Uses finite FAR to support uniformly explored whole-tile interiors.
     */
    private static void transform(int[] input, int count, int[] result,
                                  int[] indices, double[] boundaries) {
        int k = 0;
        indices[0] = 0;
        boundaries[0] = Double.NEGATIVE_INFINITY;
        boundaries[1] = Double.POSITIVE_INFINITY;
        for (int q = 1; q < count; q++) {
            double intersection;
            do {
                int previous = indices[k];
                intersection = ((double) input[q] + (double) q * q
                        - input[previous] - (double) previous * previous)
                        / (2.0 * (q - previous));
                if (intersection <= boundaries[k]) k--;
                else break;
            } while (k >= 0);
            if (k < 0) {
                k = 0;
                indices[0] = q;
                boundaries[0] = Double.NEGATIVE_INFINITY;
            } else {
                k++;
                indices[k] = q;
                boundaries[k] = intersection;
            }
            boundaries[k + 1] = Double.POSITIVE_INFINITY;
        }
        k = 0;
        for (int q = 0; q < count; q++) {
            while (boundaries[k + 1] < q) k++;
            int delta = q - indices[k];
            result[q] = delta * delta + input[indices[k]];
        }
    }

    private static void check(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
            throw new CancellationException("Superseded V2 tile");
        }
    }
}
