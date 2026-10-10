package com.sensareth.roamglyph.map;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

/**
 * Software-rasterized, supersampled fog cutout with inward Gaussian-like
 * feathering. Intended for an off-UI-thread viewport snapshot worker.
 *
 * Two separable box passes low-pass the exact mask; a chamfer distance
 * transform prevents the blur from uncovering ANY outside H3 pixel.
 * Supersampling is capped to bound working memory on high-DPI phones.
 */
final class FogRasterFeather {
    private static final int MAX_RASTER_PIXELS = 5_000_000;
    private static final float MAX_SUPERSAMPLE = 2f;

    private FogRasterFeather() {}

    static Bitmap create(Path exact, int viewWidth, int viewHeight,
                         float cellDiameterPx, float density) {
        if (viewWidth <= 0 || viewHeight <= 0) return null;
        float scale = Math.min(MAX_SUPERSAMPLE,
                (float) Math.sqrt((double) MAX_RASTER_PIXELS
                        / ((double) viewWidth * viewHeight)));
        // Extremely large displays gracefully use a reduced-resolution
        // mask rather than allocating unbounded raster buffers.
        scale = Math.min(MAX_SUPERSAMPLE, Math.max(0.25f, scale));
        int width = Math.max(1, Math.round(viewWidth * scale));
        int height = Math.max(1, Math.round(viewHeight * scale));
        if ((long) width * height > MAX_RASTER_PIXELS + 2_048L) {
            scale *= 0.98f;
            width = Math.max(1, Math.round(viewWidth * scale));
            height = Math.max(1, Math.round(viewHeight * scale));
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale((float) width / viewWidth, (float) height / viewHeight);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(exact, paint);

        int n = width * height;
        int[] pixels = new int[n];
        int[] intermediate = new int[n];
        byte[] distance = new byte[n];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        final float effectiveScale = (float) width / viewWidth;
        int blurRadius = Math.max(2, Math.min(64,
                Math.round(Math.max(2f * density,
                        Math.min(24f * density, cellDiameterPx * 0.85f))
                        * effectiveScale)));
        int featherRadius = Math.max(3, Math.min(80,
                Math.round(Math.max(10f * density,
                        Math.min(32f * density, cellDiameterPx * 1.65f))
                        * effectiveScale)));

        // Distances are inside-only, capped at 255 steps (85 raster px).
        // The exact source alpha gates every resulting pixel.
        for (int i = 0; i < n; i++) {
            distance[i] = (byte) (((pixels[i] >>> 24) >= 24) ? 255 : 0);
        }
        for (int y = 0; y < height; y++) {
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

        // Separable O(width*height) low-pass filter. Edge values extend
        // across viewport borders so a cropped view doesn't fade to black.
        int span = 2 * blurRadius + 1;
        for (int y = 0; y < height; y++) {
            int offset = y * width;
            long sum = 0;
            for (int dx = -blurRadius; dx <= blurRadius; dx++) {
                int x = Math.max(0, Math.min(width - 1, dx));
                sum += pixels[offset + x] >>> 24;
            }
            for (int x = 0; x < width; x++) {
                intermediate[offset + x] = (int) ((sum + span / 2) / span);
                int gone = Math.max(0, x - blurRadius);
                int added = Math.min(width - 1, x + blurRadius + 1);
                sum += (pixels[offset + added] >>> 24)
                        - (pixels[offset + gone] >>> 24);
            }
        }
        for (int x = 0; x < width; x++) {
            long sum = 0;
            for (int dy = -blurRadius; dy <= blurRadius; dy++) {
                int y = Math.max(0, Math.min(height - 1, dy));
                sum += intermediate[y * width + x];
            }
            for (int y = 0; y < height; y++) {
                int i = y * width + x;
                int rawAlpha = pixels[i] >>> 24;
                int blurred = (int) ((sum + span / 2) / span);
                int alpha = FogFeatherProfile.cutoutAlpha(
                        rawAlpha, blurred, distance[i] & 255, featherRadius);
                pixels[i] = (alpha << 24) | 0xFFFFFF;
                int gone = Math.max(0, y - blurRadius);
                int added = Math.min(height - 1, y + blurRadius + 1);
                sum += intermediate[added * width + x]
                        - intermediate[gone * width + x];
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }
}
