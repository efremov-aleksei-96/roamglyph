package com.sensareth.roamglyph.map;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * V2-specific tile rasterizer. The UI/camera cache remains a shared transport
 * for the first A/B prototype; V2 owns the shape-generation algorithm entirely.
 * Geographic Path coordinates are expressed in fixed 384px world-tile space.
 */
final class FogDistanceFieldRaster {
    private FogDistanceFieldRaster() {}

    static Bitmap[] createPyramid(Path exact, int size, float cellDiameterPx,
                                  float rasterScale, BooleanSupplier cancelled) {
        return createPyramid(exact, null, size, cellDiameterPx, rasterScale, cancelled);
    }

    static Bitmap[] createPyramid(Path exact, Path gpsCorridor, int size,
                                  float cellDiameterPx, float rasterScale,
                                  BooleanSupplier cancelled) {
        if (size < 1 || rasterScale <= 0f || !Float.isFinite(rasterScale)) {
            throw new IllegalArgumentException("Invalid world tile dimensions");
        }
        List<Bitmap> mipmaps = new ArrayList<>();
        try {
            Bitmap base = create(exact, gpsCorridor, size, cellDiameterPx, rasterScale, cancelled);
            mipmaps.add(base);
            Bitmap current = base;
            // Area-filtered overview textures keep small visited routes visible
            // rather than flooring them to whole pixels while zooming out.
            while (current.getWidth() > 1 || current.getHeight() > 1) {
                if (cancelled.getAsBoolean()) {
                    throw new java.util.concurrent.CancellationException("Superseded V2 mip");
                }
                Bitmap smaller = Bitmap.createScaledBitmap(current,
                        Math.max(1, current.getWidth() / 2),
                        Math.max(1, current.getHeight() / 2), true);
                mipmaps.add(smaller);
                current = smaller;
            }
            return mipmaps.toArray(new Bitmap[0]);
        } catch (RuntimeException | OutOfMemoryError error) {
            for (Bitmap mip : mipmaps) if (!mip.isRecycled()) mip.recycle();
            throw error;
        }
    }

    private static Bitmap create(Path exact, Path gpsCorridor, int tileSize, float diameter,
                                 float rasterScale, BooleanSupplier cancelled) {
        float scale = Math.max(0.20f, Math.min(1.0f, rasterScale));
        int size = Math.max(1, Math.round(tileSize * scale));
        // A bounded, inward screen-space fade. The source geometry, not the
        // strength of this effect, defines which land has been explored.
        int feather = Math.max(1, Math.min(9,
                Math.round(Math.max(1f, Math.min(6f, diameter * 0.36f)) * scale)));
        int padding = Math.max(18, feather * 4);
        int workSize = size + 2 * padding;
        Bitmap work = Bitmap.createBitmap(workSize, workSize, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(work);
            canvas.translate(padding, padding);
            canvas.scale(size / (float) tileSize, size / (float) tileSize);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.WHITE);
            canvas.drawPath(exact, paint);
            if (gpsCorridor != null && !gpsCorridor.isEmpty()) {
                // Map zoom determines pixel width of a geographically fixed
                // 10-metre radius; rounded GPS corridors are a display mask,
                // never persisted as new H3 coverage.
                double latitude = 0; // Radius is encoded in caller's stroke width.
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setStrokeWidth(Math.max(1f, diameter * 0.90f));
                canvas.drawPath(gpsCorridor, paint);
            }

            int[] pixels = new int[workSize * workSize];
            work.getPixels(pixels, 0, workSize, 0, 0, workSize, workSize);
            FogSignedDistanceField.renderInPlace(
                    pixels, workSize, workSize, feather, cancelled);
            // Small, bounded exterior feather: unlike the previous V2
            // source-clipped stencil, this is allowed to cross H3 outlines by
            // <= roughly two raster pixels. Never changes saved exploration.
            int[] smoothed = pixels.clone();
            for (int y = 1; y < workSize-1; y++) {
                if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
                for (int x = 1; x < workSize-1; x++) {
                    int idx = y*workSize+x;
                    int center = pixels[idx] >>> 24;
                    int total = center * 4;
                    total += (pixels[idx-1] >>> 24) + (pixels[idx+1] >>> 24);
                    total += (pixels[idx-workSize] >>> 24) + (pixels[idx+workSize] >>> 24);
                    int softened = Math.min(255, Math.max(center, total / 8));
                    smoothed[idx] = (softened << 24) | 0xFFFFFF;
                }
            }
            work.setPixels(smoothed(pixels, 0, workSize, 0, 0, workSize, workSize);

            // Off-tile sampling halo is stripped only AFTER the distance
            // transform, allowing adjacent world tiles to share boundaries.
            Bitmap cropped = Bitmap.createBitmap(work, padding, padding, size, size);
            if (cancelled.getAsBoolean()) {
                cropped.recycle();
                throw new java.util.concurrent.CancellationException("Superseded V2 tile");
            }
            return cropped;
        } finally {
            work.recycle();
        }
    }
}
