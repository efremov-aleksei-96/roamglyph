package com.sensareth.roamglyph.map;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Supersampled exact-H3 raster mask, with a padded 2D smoothed silhouette.
 *
 * The work buffer includes at least the FULL support of both box blur passes
 * beyond the visible map rectangle. Viewport edges therefore cannot be
 * mistaken for real exploration borders during a pan or a zoom.
 * Both the oversized work area and its bitmaps stay within a 5MP cap.
 */
final class FogRasterFeather {
    private static final int MAX_RASTER_PIXELS = 5_000_000;
    private static final float MAX_SUPERSAMPLE = 2f;

    private FogRasterFeather() {}

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
            throw new CancellationException("Superseded fog mask");
        }
    }

    static Bitmap[] createPyramid(Path exact, int viewWidth, int viewHeight,
                                  float cellDiameterPx, float density) {
        return createPyramid(exact, viewWidth, viewHeight,
                cellDiameterPx, density, () -> false);
    }

    static Bitmap[] createPyramid(Path exact, int viewWidth, int viewHeight,
                                  float cellDiameterPx, float density,
                                  BooleanSupplier cancelled) {
        return createPyramid(exact, viewWidth, viewHeight,
                cellDiameterPx, density, cancelled, MAX_SUPERSAMPLE);
    }

    // The map's persistent tile cache uses 1x raster resolution to keep
    // many adjacent/multiple-zoom tiles in memory without OOM. The former
    // viewport renderer retains 2x as an optional offscreen setting.
    static Bitmap[] createPyramid(Path exact, int viewWidth, int viewHeight,
                                  float cellDiameterPx, float density,
                                  BooleanSupplier cancelled, float maxScale) {
        return createPyramid(exact, null, viewWidth, viewHeight,
                cellDiameterPx, density, cancelled, maxScale);
    }

    /**
     * routeFootprint is optional. When present its smooth GPS corridor
     * replaces the jagged H3 display silhouette but is raster-clipped to
     * exact stored H3 polygons, so no new terrain is ever revealed.
     */
    static Bitmap[] createPyramid(Path exact, Path routeFootprint,
                                  int viewWidth, int viewHeight,
                                  float cellDiameterPx, float density,
                                  BooleanSupplier cancelled, float maxScale) {
        List<Bitmap> levels = new ArrayList<>();
        try {
            Bitmap current = create(exact, routeFootprint,
                    viewWidth, viewHeight,
                    cellDiameterPx, density, cancelled, maxScale);
            if (current == null) return new Bitmap[0];
            levels.add(current);
            // Area filtering preserves small legitimate trails on zoom-out.
            for (int i = 0; i < 14; i++) {
                checkCancelled(cancelled);
                if (current.getWidth() == 1 && current.getHeight() == 1) break;
                int nextWidth = Math.max(1, current.getWidth() / 2);
                int nextHeight = Math.max(1, current.getHeight() / 2);
                current = Bitmap.createScaledBitmap(
                        current, nextWidth, nextHeight, true);
                levels.add(current);
            }
            checkCancelled(cancelled);
            return levels.toArray(new Bitmap[0]);
        } catch (RuntimeException | OutOfMemoryError failure) {
            for (Bitmap bitmap : levels) bitmap.recycle();
            throw failure;
        }
    }

    static Bitmap create(Path exact, int viewWidth, int viewHeight,
                         float cellDiameterPx, float density) {
        return create(exact, viewWidth, viewHeight,
                cellDiameterPx, density, () -> false);
    }

    static Bitmap create(Path exact, int viewWidth, int viewHeight,
                         float cellDiameterPx, float density,
                         BooleanSupplier cancelled) {
        return create(exact, viewWidth, viewHeight,
                cellDiameterPx, density, cancelled, MAX_SUPERSAMPLE);
    }

    static Bitmap create(Path exact, int viewWidth, int viewHeight,
                         float cellDiameterPx, float density,
                         BooleanSupplier cancelled, float maxScale) {
        return create(exact, null, viewWidth, viewHeight,
                cellDiameterPx, density, cancelled, maxScale);
    }

    static Bitmap create(Path exact, Path routeFootprint,
                         int viewWidth, int viewHeight,
                         float cellDiameterPx, float density,
                         BooleanSupplier cancelled, float maxScale) {
        if (viewWidth <= 0 || viewHeight <= 0) return null;
        if (!Float.isFinite(maxScale) || maxScale <= 0) {
            throw new IllegalArgumentException("Invalid fog sampling scale");
        }

        double scale = Math.min(Math.min(MAX_SUPERSAMPLE, maxScale),
                Math.sqrt((double) MAX_RASTER_PIXELS
                        / ((double) viewWidth * viewHeight)));
        int width = 0, height = 0, blur = 0, feather = 0, pad = 0;
        long area = Long.MAX_VALUE;

        // Compute padding in RASTER pixels after scaling. Two full
        // 2D box-filter passes have 2*r kernel support in each direction.
        // The gradient/distance raster also needs real H3 input outside
        // the screen. Decrease render scale until all padded pixels fit.
        for (int pass = 0; pass < 40; pass++) {
            width = Math.max(1, (int) Math.floor(viewWidth * scale));
            height = Math.max(1, (int) Math.floor(viewHeight * scale));
            float effective = (float) width / viewWidth;
            blur = Math.max(1, Math.min(64,
                    Math.round(Math.max(1f / effective,
                            Math.min(30f * density, cellDiameterPx * 0.78f))
                            * effective)));
            feather = Math.max(1, Math.min(80,
                    Math.round(Math.max(1f / effective,
                            Math.min(42f * density, cellDiameterPx * 1.7f))
                            * effective)));
            pad = Math.max(2 * blur + 3, feather + 3);
            area = ((long) width + 2L * pad) * (height + 2L * pad);
            if (area <= MAX_RASTER_PIXELS) break;
            scale *= Math.max(0.15,
                    Math.sqrt((double) MAX_RASTER_PIXELS / area) * 0.985);
        }
        if (area > MAX_RASTER_PIXELS) {
            // This should never happen in a realistic viewport. Refuse
            // oversized allocations and let the overlay fail dark.
            throw new IllegalArgumentException("Padded fog buffer exceeds cap");
        }

        checkCancelled(cancelled);
        int workWidth = width + 2 * pad;
        int workHeight = height + 2 * pad;
        Bitmap work = Bitmap.createBitmap(
                workWidth, workHeight, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(work);
            // Actual geographic vertices were projected for the visible
            // viewport, but the full Path includes off-screen geometry.
            // Translate BEFORE scaling to keep that geometry in the pad.
            canvas.translate(pad, pad);
            canvas.scale((float) width / viewWidth,
                    (float) height / viewHeight);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.FILL);
            int[] originalH3 = null;
            int[] h3Feathered = null;
            if (routeFootprint != null) {
                // First preserve exact H3 source alpha and its normal
                // conservative feather. This keeps unrelated imported or
                // historical visits visible in a tile containing GPS too.
                canvas.drawPath(exact, paint);
                originalH3 = new int[workWidth * workHeight];
                work.getPixels(originalH3, 0, workWidth, 0, 0,
                        workWidth, workHeight);
                h3Feathered = originalH3.clone();
                FogSilhouetteField.renderInPlace(
                        h3Feathered, workWidth, workHeight,
                        blur, feather, cancelled);
                checkCancelled(cancelled);
                work.eraseColor(Color.TRANSPARENT);

                // GPS shape is still constrained to exact H3 coverage
                // in the SOURCE raster, not merely at the UI draw layer.
                int clipped = canvas.save();
                canvas.clipPath(exact);
                canvas.drawPath(routeFootprint, paint);
                canvas.restoreToCount(clipped);
            } else {
                canvas.drawPath(exact, paint);
            }

            checkCancelled(cancelled);
            int[] pixels = new int[workWidth * workHeight];
            work.getPixels(pixels, 0, workWidth, 0, 0,
                    workWidth, workHeight);
            int[] rawGps = routeFootprint == null ? null : pixels.clone();
            FogSilhouetteField.renderInPlace(
                    pixels, workWidth, workHeight, blur, feather, cancelled);
            if (rawGps != null) {
                // Near the actual GPS line, only the rounded corridor
                // determines visibility. Separate H3 islands farther
                // away keep their own fade. All results are min-clamped
                // against original exact-H3 coverage.
                FogGpsBlend.blend(originalH3, h3Feathered, pixels, rawGps,
                        workWidth, workHeight,
                        cellDiameterPx * (float) width / viewWidth, cancelled);
            }
            checkCancelled(cancelled);
            work.setPixels(pixels, 0, workWidth, 0, 0,
                    workWidth, workHeight);

            // Only the central viewport travels with the geographic
            // camera matrix. Its alpha was calculated with a real,
            // continuous neighbourhood instead of clamped edge samples.
            Bitmap cropped = Bitmap.createBitmap(
                    work, pad, pad, width, height);
            try {
                checkCancelled(cancelled);
                return cropped;
            } catch (RuntimeException | OutOfMemoryError failure) {
                cropped.recycle();
                throw failure;
            }
        } finally {
            work.recycle();
        }
    }
}
