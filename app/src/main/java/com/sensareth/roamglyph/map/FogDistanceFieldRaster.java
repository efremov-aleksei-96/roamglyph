package com.sensareth.roamglyph.map;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

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
        if (size < 1 || rasterScale <= 0f || !Float.isFinite(rasterScale)) {
            throw new IllegalArgumentException("Invalid world tile dimensions");
        }
        List<Bitmap> mipmaps = new ArrayList<>();
        try {
            Bitmap base = create(exact, size, cellDiameterPx, rasterScale, cancelled);
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

    private static Bitmap create(Path exact, int tileSize, float diameter,
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

            int[] pixels = new int[workSize * workSize];
            work.getPixels(pixels, 0, workSize, 0, 0, workSize, workSize);
            FogSignedDistanceField.renderInPlace(
                    pixels, workSize, workSize, feather, cancelled);
            work.setPixels(pixels, 0, workSize, 0, 0, workSize, workSize);

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
