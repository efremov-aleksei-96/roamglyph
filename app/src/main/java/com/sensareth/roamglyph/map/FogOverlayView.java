package com.sensareth.roamglyph.map;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.view.View;

import androidx.annotation.NonNull;

import com.uber.h3core.util.LatLng;

import org.maplibre.android.maps.MapLibreMap;

import java.util.Collections;
import java.util.List;

/**
 * Screen-attached, fail-dark Fog of War.
 *
 * Unlike viewport GeoJSON world polygons, the full-screen dark mask is never
 * clipped at a tile or viewport edge. Map camera motion reprojects the SAME
 * geographic res-13 visited geometry before drawing each frame. A pending
 * background computation may hide an explored cell, but cannot reveal unknown
 * land or enlarge a narrow explored trail at low zoom.
 */
public final class FogOverlayView extends View {
    private static final int FOG_COLOR = Color.rgb(17, 20, 24);

    private final Paint fogPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    private MapLibreMap map;
    private boolean enabled = true;
    private List<List<List<LatLng>>> polygons = Collections.emptyList();

    public FogOverlayView(@NonNull Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setClickable(false);
        setWillNotDraw(false);
        fogPaint.setStyle(Paint.Style.FILL);
        fogPaint.setColor(FOG_COLOR);
        fogPaint.setAlpha(210);
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeJoin(Paint.Join.ROUND);
        edgePaint.setStrokeCap(Paint.Cap.ROUND);
        edgePaint.setPathEffect(new CornerPathEffect(2.0f * density));
    }

    public void attachMap(@NonNull MapLibreMap map) {
        this.map = map;
        invalidate();
    }

    public void setFogEnabled(boolean value) {
        enabled = value;
        invalidate();
    }

    public void setGeometry(@NonNull List<List<List<LatLng>>> newPolygons) {
        // Worker-built geometry is immutable after publication on the UI thread.
        polygons = newPolygons;
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (!enabled) return;

        // Even before the map initializes or Room finishes loading, every
        // visible pixel stays covered by a screen-space dark fill.
        if (map == null || polygons.isEmpty()) {
            canvas.drawColor(Color.argb(210, 17, 20, 24));
            return;
        }

        Path mask = new Path();
        mask.setFillType(Path.FillType.EVEN_ODD);
        mask.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);

        Path outlines = new Path();
        try {
            for (List<List<LatLng>> polygon : polygons) {
                for (List<LatLng> ring : polygon) {
                    if (ring.size() < 3) continue;
                    Path projected = projectRing(ring);
                    mask.addPath(projected);
                    outlines.addPath(projected);
                }
            }
        } catch (RuntimeException error) {
            // Projection failure must never expose unknown map tiles.
            canvas.drawColor(Color.argb(210, 17, 20, 24));
            return;
        }

        // The only pixels that show through are inside exact res-13 H3
        // footprints. Anti-aliased subpixel footprints stay subpixel.
        canvas.drawPath(mask, fogPaint);

        // A feathered edge is made by faint progressively narrower strokes.
        // Crucially this DARKENS the border rather than expanding the
        // transparent/explored footprint (no false exploration).
        // At overview zoom the exact geometry is too small for a wide halo.
        double zoom = map.getCameraPosition() == null
                ? 15.0 : map.getCameraPosition().zoom;
        float maxWidth = zoom >= 16.0 ? 10.0f : zoom >= 14.0 ? 5.0f : 1.0f;
        int passes = zoom >= 14.0 ? 8 : 2;
        for (int pass = passes; pass >= 1; pass--) {
            edgePaint.setStrokeWidth(maxWidth * density * pass / passes);
            edgePaint.setColor(Color.argb(
                    zoom >= 14.0 ? 12 : 9, 17, 20, 24
            ));
            canvas.drawPath(outlines, edgePaint);
        }
    }

    @NonNull
    private Path projectRing(@NonNull List<LatLng> ring) {
        Path path = new Path();
        for (int i = 0; i < ring.size(); i++) {
            LatLng coordinate = ring.get(i);
            PointF screen = map.getProjection().toScreenLocation(
                    new org.maplibre.android.geometry.LatLng(
                            coordinate.lat, coordinate.lng
                    )
            );
            if (!Float.isFinite(screen.x) || !Float.isFinite(screen.y)) {
                throw new IllegalArgumentException("Non-finite map projection");
            }
            if (i == 0) path.moveTo(screen.x, screen.y);
            else path.lineTo(screen.x, screen.y);
        }
        path.close();
        return path;
    }
}
