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
 * Renderer V4: geographic shape regularization before compositing the fog.
 * Does not reuse V2's source-clipped SDF or V3's five-pixel edge blur.
 *
 * H3 remains authoritative for persistence and metrics. This renderer
 * intentionally allows <= 2.5 m of cosmetic extension beyond H3/GPS.
 */
final class FogOrganicRaster {
    private static final float H3_CELL_METERS = 8.2f;
    private static final float ROUTE_RADIUS_METERS = 10f;
    private static final float MAX_EXTERIOR_METERS = 2.5f;

    private FogOrganicRaster() {}

    static Bitmap[] createPyramid(Path exact, Path gps, int tilePx,
                                  float diameterPx, float sampling,
                                  BooleanSupplier cancelled) {
        if (tilePx < 1 || !Float.isFinite(diameterPx) || diameterPx <= 0f
                || !Float.isFinite(sampling) || sampling <= 0f) {
            throw new IllegalArgumentException("Invalid organic tile geometry");
        }
        ArrayList<Bitmap> levels = new ArrayList<>();
        try {
            Bitmap current = render(exact, gps, tilePx, diameterPx,
                    sampling, cancelled);
            levels.add(current);
            while (current.getWidth() > 1 || current.getHeight() > 1) {
                if (cancelled.getAsBoolean()) {
                    throw new CancellationException("Superseded V4 mip");
                }
                current = Bitmap.createScaledBitmap(current,
                        Math.max(1, current.getWidth() / 2),
                        Math.max(1, current.getHeight() / 2), true);
                levels.add(current);
            }
            return levels.toArray(new Bitmap[0]);
        } catch (RuntimeException | OutOfMemoryError failed) {
            for (Bitmap bitmap : levels) if (!bitmap.isRecycled()) bitmap.recycle();
            throw failed;
        }
    }

    private static Bitmap render(Path exact, Path gps, int tilePx,
                                 float diameterPx, float sampling,
                                 BooleanSupplier cancelled) {
        float scale = Math.max(0.20f, Math.min(1f, sampling));
        int tileSize = Math.max(1, Math.round(tilePx * scale));
        float actualDiameter = diameterPx * tileSize / tilePx;

        // A physical-radius shape filter, NOT a screen-zoom-based fog halo:
        // two 2-D box filters regularize the repeating res-13 hex teeth.
        int radius = Math.max(1, Math.min(60, Math.round(actualDiameter * 0.58f)));
        // For coarse maps, no artificial >=1px reveal outside the actual
        // H3 footprint: one zoomed-out pixel can represent many metres.
        int exterior = Math.max(0, Math.round(
                actualDiameter * MAX_EXTERIOR_METERS / H3_CELL_METERS));
        int routeRadius = (gps == null || gps.isEmpty()) ? 0 : Math.max(0,
                Math.round(actualDiameter * ROUTE_RADIUS_METERS / H3_CELL_METERS));
        // A route extends farther than the original H3 raster: its complete
        // stroke, the two smoothing passes and the permitted exterior pixels
        // must all fit outside the cropped geographic tile rectangle.
        int padding = Math.max(8, Math.max(
                2 * radius + exterior + 8,
                routeRadius + 2 * radius + exterior + 8));

        int side = tileSize + 2 * padding;
        if ((long) side * side > 2_000_000L) {
            throw new IllegalArgumentException("Fog organic working buffer too large");
        }
        Bitmap work = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(work);
            canvas.translate(padding, padding);
            canvas.scale(tileSize / (float) tilePx, tileSize / (float) tilePx);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawPath(exact, paint);
            if (gps != null && !gps.isEmpty()) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setStrokeWidth(diameterPx * (2f * ROUTE_RADIUS_METERS / H3_CELL_METERS));
                canvas.drawPath(gps, paint);
            }
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("Superseded V4 geometry");
            }
            int[] alpha = new int[side * side];
            work.getPixels(alpha, 0, side, 0, 0, side, side);
            FogOrganicContour.renderInPlace(alpha, side, side, radius,
                    exterior, cancelled);
            work.setPixels(alpha, 0, side, 0, 0, side, side);
            Bitmap cropped = Bitmap.createBitmap(
                    work, padding, padding, tileSize, tileSize);
            if (cancelled.getAsBoolean()) {
                cropped.recycle();
                throw new CancellationException("Superseded V4 raster");
            }
            return cropped;
        } finally {
            work.recycle();
        }
    }
}
