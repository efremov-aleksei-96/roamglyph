package com.sensareth.roamglyph.map;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * Software-rasterized, supersampled fog cutout with inward Gaussian-like
 * feathering. Intended for an off-UI-thread viewport snapshot worker.
 *
 * Two two-dimensional low-pass passes smooth the SHAPE instead of its hex
 * boundary; an inward chamfer fade and strict source-alpha stencil prevent
 * the blur from uncovering any unknown geography.
 * Supersampling is capped to bound working memory on high-DPI phones.
 */
final class FogRasterFeather {
    private static final int MAX_RASTER_PIXELS = 5_000_000;
    private static final float MAX_SUPERSAMPLE = 2f;

    private FogRasterFeather() {}

    static Bitmap[] createPyramid(Path exact, int viewWidth, int viewHeight,
                                  float cellDiameterPx, float density) {
        List<Bitmap> levels = new ArrayList<>();
        try {
            Bitmap current = create(exact, viewWidth, viewHeight,
                    cellDiameterPx, density);
            if (current == null) return new Bitmap[0];
            levels.add(current);
            // Repeated 2x2 filtered reductions preserve average alpha of
            // subpixel trails when a camera gesture minifies the bitmap.
            for (int i = 0; i < 14; i++) {
                checkInterrupted();
                if (current.getWidth() == 1 && current.getHeight() == 1) break;
                int nextWidth = Math.max(1, current.getWidth() / 2);
                int nextHeight = Math.max(1, current.getHeight() / 2);
                current = Bitmap.createScaledBitmap(
                        current, nextWidth, nextHeight, true);
                levels.add(current);
            }
            return levels.toArray(new Bitmap[0]);
        } catch (RuntimeException | OutOfMemoryError failure) {
            for (Bitmap bitmap : levels) bitmap.recycle();
            throw failure;
        }
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Stale fog mask job");
        }
    }

    static Bitmap create(Path exact, int viewWidth, int viewHeight,
                         float cellDiameterPx, float density) {
        if (viewWidth <= 0 || viewHeight <= 0) return null;
        double scale = Math.min(MAX_SUPERSAMPLE,
                Math.sqrt((double) MAX_RASTER_PIXELS
                        / ((double) viewWidth * viewHeight)));
        // The cap MUST also hold for very large external displays.
        int width = Math.max(1, (int) Math.floor(viewWidth * scale));
        int height = Math.max(1, (int) Math.floor(viewHeight * scale));
        while ((long) width * height > MAX_RASTER_PIXELS) {
            if (width >= height && width > 1) width--;
            else height--;
        }
        checkInterrupted();
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        boolean complete = false;
        try {
        Canvas canvas = new Canvas(bitmap);
        canvas.scale((float) width / viewWidth, (float) height / viewHeight);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(exact, paint);

        int n = width * height;
        int[] pixels = new int[n];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        final float effectiveScale = (float) width / viewWidth;
        // Two full separable box passes produce a near-Gaussian occupancy
        // field. Keep radius tied to H3 size, not density alone: a small
        // route is still recognizable when projected to a few screen px.
        int blurRadius = Math.max(1, Math.min(64,
                Math.round(Math.max(1f / effectiveScale,
                        Math.min(30f * density, cellDiameterPx * 0.78f))
                        * effectiveScale)));
        int featherRadius = Math.max(1, Math.min(80,
                Math.round(Math.max(1f / effectiveScale,
                        Math.min(42f * density, cellDiameterPx * 1.7f))
                        * effectiveScale)));

        // This replaces contour-following strokes with a 2D spatially
        // smoothed silhouette. Pixel output is always clipped by original
        // H3 source alpha; no off-route cell is made transparent.
        FogSilhouetteField.renderInPlace(
                pixels, width, height, blurRadius, featherRadius);
        checkInterrupted();
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        complete = true;
        return bitmap;
        } finally {
            if (!complete) bitmap.recycle();
        }
    }
}
