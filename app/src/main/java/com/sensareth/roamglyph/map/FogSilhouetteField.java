package com.sensareth.roamglyph.map;

import java.util.concurrent.CancellationException;

/**
 * CPU-only fog silhouette builder. Unlike stroking the original H3 edges,
 * the visible boundary follows a spatially smoothed occupancy field.
 *
 * Two successive, separable, area-preserving box filters approximate a
 * Gaussian without O(radius * imageSize) cost. The input source alpha is
 * preserved until the final write so *no* unvisited pixel can be cleared.
 * Renders into an existing ARGB int[] with no Android API dependencies.
 */
final class FogSilhouetteField {
    private FogSilhouetteField() {}

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Superseded fog silhouette");
        }
    }

    static void renderInPlace(int[] argb, int width, int height,
                              int blurRadius, int featherRadius) {
        if (width <= 0 || height <= 0 || (long) width * height != argb.length
                || blurRadius < 1 || featherRadius < 1) {
            throw new IllegalArgumentException("Invalid fog mask dimensions");
        }
        final int length = argb.length;
        byte[] distance = new byte[length];
        byte[] horizontal = new byte[length];
        byte[] blurred = new byte[length];

        // Exact-H3 stencil: keep the original AA pixel coverage for output.
        // Distances are at most 85 supersampled pixels (255 / 3).
        for (int i = 0; i < length; i++) {
            distance[i] = (byte) ((argb[i] >>> 24) >= 24 ? 255 : 0);
        }
        for (int y = 0; y < height; y++) {
            if ((y & 31) == 0) checkInterrupted();
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int i = row + x;
                int d = distance[i] & 255;
                if (d == 0) continue;
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
            if ((y & 31) == 0) checkInterrupted();
            int row = y * width;
            for (int x = width - 1; x >= 0; x--) {
                int i = row + x;
                int d = distance[i] & 255;
                if (d == 0) continue;
                if (x + 1 < width) d = Math.min(d, (distance[i + 1] & 255) + 3);
                if (y + 1 < height) {
                    d = Math.min(d, (distance[i + width] & 255) + 3);
                    if (x > 0) d = Math.min(d, (distance[i + width - 1] & 255) + 4);
                    if (x + 1 < width) d = Math.min(d, (distance[i + width + 1] & 255) + 4);
                }
                distance[i] = (byte) d;
            }
        }

        // Smooth the SHAPE, not its hard outline. Multiple filtering passes
        // soften changes in boundary direction (H3 teeth) over neighbouring
        // cells before the actual cutout transparency is chosen.
        horizontalFromArgb(argb, horizontal, width, height, blurRadius);
        verticalFromByte(horizontal, blurred, width, height, blurRadius);
        horizontalFromByte(blurred, horizontal, width, height, blurRadius);
        verticalFromByte(horizontal, blurred, width, height, blurRadius);

        for (int y = 0; y < height; y++) {
            if ((y & 31) == 0) checkInterrupted();
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int i = row + x;
                int raw = argb[i] >>> 24;
                int alpha = FogFeatherProfile.cutoutAlpha(
                        raw, blurred[i] & 255, distance[i] & 255, featherRadius);
                argb[i] = (alpha << 24) | 0xFFFFFF;
            }
        }
        checkInterrupted();
    }

    private static void horizontalFromArgb(int[] input, byte[] out,
                                           int width, int height, int radius) {
        int span = radius * 2 + 1;
        for (int y = 0; y < height; y++) {
            if ((y & 31) == 0) checkInterrupted();
            int row = y * width;
            long sum = 0;
            for (int dx = -radius; dx <= radius; dx++) {
                int x = Math.max(0, Math.min(width - 1, dx));
                sum += input[row + x] >>> 24;
            }
            for (int x = 0; x < width; x++) {
                out[row + x] = (byte) ((sum + span / 2) / span);
                int gone = Math.max(0, x - radius);
                int added = Math.min(width - 1, x + radius + 1);
                sum += (input[row + added] >>> 24)
                        - (input[row + gone] >>> 24);
            }
        }
    }

    private static void horizontalFromByte(byte[] input, byte[] out,
                                           int width, int height, int radius) {
        int span = radius * 2 + 1;
        for (int y = 0; y < height; y++) {
            if ((y & 31) == 0) checkInterrupted();
            int row = y * width;
            long sum = 0;
            for (int dx = -radius; dx <= radius; dx++) {
                int x = Math.max(0, Math.min(width - 1, dx));
                sum += input[row + x] & 255;
            }
            for (int x = 0; x < width; x++) {
                out[row + x] = (byte) ((sum + span / 2) / span);
                int gone = Math.max(0, x - radius);
                int added = Math.min(width - 1, x + radius + 1);
                sum += (input[row + added] & 255)
                        - (input[row + gone] & 255);
            }
        }
    }

    private static void verticalFromByte(byte[] input, byte[] out,
                                         int width, int height, int radius) {
        int span = radius * 2 + 1;
        for (int x = 0; x < width; x++) {
            if ((x & 31) == 0) checkInterrupted();
            long sum = 0;
            for (int dy = -radius; dy <= radius; dy++) {
                int y = Math.max(0, Math.min(height - 1, dy));
                sum += input[y * width + x] & 255;
            }
            for (int y = 0; y < height; y++) {
                out[y * width + x] = (byte) ((sum + span / 2) / span);
                int gone = Math.max(0, y - radius);
                int added = Math.min(height - 1, y + radius + 1);
                sum += (input[added * width + x] & 255)
                        - (input[gone * width + x] & 255);
            }
        }
    }
}
