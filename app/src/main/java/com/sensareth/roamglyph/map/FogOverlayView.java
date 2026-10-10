package com.sensareth.roamglyph.map;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.view.View;

import androidx.annotation.NonNull;

import com.uber.h3core.util.LatLng;

import org.maplibre.android.maps.MapLibreMap;

import java.util.Collections;
import java.util.List;

/**
 * Screen-attached, fail-dark fog. A single raster mask is built from exact
 * resolution-13 visited geometry when a worker publishes new coverage.
 * During camera gestures only a 4-corner projective bitmap transform is
 * updated; the UI thread never reprojects thousands of H3 vertices per frame.
 *
 * The full-screen dark fill is drawn BEFORE subtracting the transformed
 * visited mask, so moving outside the cached area cannot reveal side strips.
 */
public final class FogOverlayView extends View {
    private static final int FOG_COLOR = Color.rgb(17, 20, 24);
    private static final int FOG_ALPHA = 210;

    private final Paint fogPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cutoutPaint = new Paint(
            Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerOutline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix transform = new Matrix();
    private final float density;

    private MapLibreMap map;
    private boolean enabled = true;
    private boolean cacheDirty = true;
    private Bitmap maskBitmap;
    private final LatLng[] referenceGeo = new LatLng[4];
    private final float[] referencePixels = new float[8];
    private final float[] currentPixels = new float[8];
    private double snapshotZoom = Double.NaN;
    private double snapshotTilt = Double.NaN;
    private double snapshotCellDiameterPx = Double.NaN;
    private List<List<List<LatLng>>> polygons = Collections.emptyList();

    private boolean hasLocation;
    private double locationLat;
    private double locationLng;
    private List<PoiMarker> discoveredMarkers = Collections.emptyList();
    private List<PoiMarker> hintMarkers = Collections.emptyList();

    /** A drawable POI; no remote tiles, fonts or icon assets are required. */
    public static final class PoiMarker {
        public final double latitude;
        public final double longitude;
        @NonNull public final String emoji;

        public PoiMarker(double latitude, double longitude,
                         @NonNull String category, String subclass) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.emoji = DiscoveryCategoryIcons.iconFor(category, subclass);
        }
    }

    public FogOverlayView(@NonNull Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setClickable(false);
        setWillNotDraw(false);

        fogPaint.setStyle(Paint.Style.FILL);
        fogPaint.setColor(FOG_COLOR);
        fogPaint.setAlpha(FOG_ALPHA);
        maskPaint.setColor(Color.WHITE);
        maskPaint.setStyle(Paint.Style.FILL);
        cutoutPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeJoin(Paint.Join.ROUND);
        edgePaint.setStrokeCap(Paint.Cap.ROUND);
        edgePaint.setPathEffect(new CornerPathEffect(2.0f * density));
        edgePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        markerOutline.setStyle(Paint.Style.STROKE);
        markerOutline.setStrokeWidth(2.0f * density);
        markerOutline.setColor(Color.WHITE);
    }

    public void attachMap(@NonNull MapLibreMap map) {
        this.map = map;
        cacheDirty = true;
        invalidate();
    }

    public void setFogEnabled(boolean value) {
        enabled = value;
        invalidate();
    }

    public void setGeometry(@NonNull List<List<List<LatLng>>> newPolygons) {
        polygons = newPolygons;
        cacheDirty = true;
        invalidate();
    }

    public void setCurrentLocation(boolean available, double lat, double lng) {
        hasLocation = available;
        locationLat = lat;
        locationLng = lng;
        invalidate();
    }

    public void setDiscoveries(
            @NonNull List<PoiMarker> discovered,
            @NonNull List<PoiMarker> hints
    ) {
        discoveredMarkers = discovered;
        hintMarkers = hints;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cacheDirty = true;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (!enabled) {
            // Category emoji remain useful when the fog is disabled.
            if (map != null) drawForegroundMarkers(canvas, false);
            return;
        }

        if (cacheDirty) {
            cacheDirty = false;
            try {
                buildMaskSnapshot();
            } catch (RuntimeException | OutOfMemoryError error) {
                maskBitmap = null;
            }
        }

        // Compose into a separate layer so DST_OUT removes fog alpha only,
        // not previously rendered MapLibre map pixels.
        int layer = canvas.saveLayer(
                0, 0, getWidth(), getHeight(), null);
        canvas.drawColor(Color.argb(FOG_ALPHA, 17, 20, 24));
        if (maskBitmap != null && map != null && transformedMaskIsSafe()) {
            canvas.drawBitmap(maskBitmap, transform, cutoutPaint);
        }
        canvas.restoreToCount(layer);

        // MapLibre GL annotations are below this Android overlay; redraw
        // lightweight location and discovery markers above it.
        if (map != null) drawForegroundMarkers(canvas, true);
    }

    /** Build full-resolution geometry just once per changed viewport. */
    private void buildMaskSnapshot() {
        if (map == null || getWidth() <= 0 || getHeight() <= 0
                || polygons.isEmpty()) {
            maskBitmap = null;
            return;
        }

        if (maskBitmap == null || maskBitmap.getWidth() != getWidth()
                || maskBitmap.getHeight() != getHeight()) {
            maskBitmap = Bitmap.createBitmap(
                    getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
        }
        maskBitmap.eraseColor(Color.TRANSPARENT);

        Canvas snapshot = new Canvas(maskBitmap);
        Path exact = new Path();
        exact.setFillType(Path.FillType.EVEN_ODD);
        Path rounded = new Path();
        rounded.setFillType(Path.FillType.EVEN_ODD);
        Path roundedOutlines = new Path();

        for (List<List<LatLng>> polygon : polygons) {
            for (List<LatLng> ring : polygon) {
                if (ring.size() < 3) continue;
                ProjectedRing projected = projectRoundedRing(ring);
                exact.addPath(projected.exact);
                rounded.addPath(projected.rounded);
                roundedOutlines.addPath(projected.rounded);
            }
        }

        // Actual curvature, not merely feathering a sharp hexagonal edge.
        // Rounding may cross concave H3 boundaries. The exact H3 mask is an
        // immutable clip so NO unvisited pixel can ever be cleared.
        snapshot.save();
        snapshot.clipPath(exact);
        snapshot.drawPath(rounded, maskPaint);
        snapshot.restore();

        double zoom = map.getCameraPosition() == null
                ? 15.0 : map.getCameraPosition().zoom;
        double latitude = map.getCameraPosition() == null
                || map.getCameraPosition().target == null
                ? 40.0 : map.getCameraPosition().target.getLatitude();
        double metersPerPixel = 156543.03392
                * Math.cos(Math.toRadians(Math.max(-85.0, Math.min(85.0, latitude))))
                / Math.pow(2.0, zoom);
        float cellDiameterPx = (float) (8.2 / Math.max(0.000001, metersPerPixel));
        snapshotCellDiameterPx = cellDiameterPx;
        float maxWidthPx = Math.max(0.4f,
                Math.min(4.0f * density, 0.38f * cellDiameterPx));
        int passes = cellDiameterPx >= 1.0f ? 12 : 3;

        // Feathering subtracts mask alpha INSIDE explored areas only.
        // It never creates extra transparent pixels in unknown territory.
        snapshot.save();
        snapshot.clipPath(exact);
        snapshot.clipPath(rounded);
        for (int i = passes; i >= 1; i--) {
            edgePaint.setStrokeWidth(maxWidthPx * i / passes);
            edgePaint.setColor(Color.argb(passes == 12 ? 45 : 150, 0, 0, 0));
            snapshot.drawPath(roundedOutlines, edgePaint);
        }
        snapshot.restore();

        // Sample four visible screen corners. Camera transitions on a flat
        // Mercator map are representable by a projective 2D matrix.
        float width = getWidth();
        float height = getHeight();
        float[] corners = {0f, 0f, width, 0f, width, height, 0f, height};
        System.arraycopy(corners, 0, referencePixels, 0, 8);
        for (int i = 0; i < 4; i++) {
            org.maplibre.android.geometry.LatLng geo =
                    map.getProjection().fromScreenLocation(
                            new PointF(corners[i * 2], corners[i * 2 + 1]));
            referenceGeo[i] = new LatLng(geo.getLatitude(), geo.getLongitude());
        }
        if (map.getCameraPosition() != null) {
            snapshotZoom = map.getCameraPosition().zoom;
            snapshotTilt = map.getCameraPosition().tilt;
        }
    }

    private boolean transformedMaskIsSafe() {
        if (map.getCameraPosition() == null
                || !FogMaskReusePolicy.mayReuse(
                        snapshotZoom,
                        map.getCameraPosition().zoom,
                        snapshotTilt,
                        map.getCameraPosition().tilt,
                        snapshotCellDiameterPx)) {
            // Bitmap antialiasing is already quantized at the reference
            // camera; zooming in would enlarge visited pixels beyond H3.
            // Keep screen fully dark until the exact geometry is rebuilt.
            return false;
        }
        for (int i = 0; i < 4; i++) {
            LatLng point = referenceGeo[i];
            if (point == null) return false;
            PointF screen = map.getProjection().toScreenLocation(
                    new org.maplibre.android.geometry.LatLng(point.lat, point.lng));
            if (!Float.isFinite(screen.x) || !Float.isFinite(screen.y)
                    || Math.abs(screen.x) > 10_000_000f
                    || Math.abs(screen.y) > 10_000_000f) return false;
            currentPixels[2 * i] = screen.x;
            currentPixels[2 * i + 1] = screen.y;
        }
        return transform.setPolyToPoly(
                referencePixels, 0, currentPixels, 0, 4);
    }

    private static final float CORNER_ROUNDING = 0.43f;

    private static final class ProjectedRing {
        final Path exact;
        final Path rounded;

        ProjectedRing(Path exact, Path rounded) {
            this.exact = exact;
            this.rounded = rounded;
        }
    }

    @NonNull
    private ProjectedRing projectRoundedRing(@NonNull List<LatLng> ring) {
        int n = ring.size();
        if (n > 3) {
            LatLng first = ring.get(0);
            LatLng last = ring.get(n - 1);
            if (Math.abs(first.lat - last.lat) < 1e-12
                    && Math.abs(first.lng - last.lng) < 1e-12) n--;
        }
        if (n < 3) return new ProjectedRing(new Path(), new Path());

        PointF[] points = new PointF[n];
        Path exact = new Path();
        for (int i = 0; i < n; i++) {
            LatLng coordinate = ring.get(i);
            PointF screen = projectPoint(coordinate.lat, coordinate.lng);
            if (screen == null) {
                throw new IllegalArgumentException("Non-finite H3 projection");
            }
            points[i] = screen;
            if (i == 0) exact.moveTo(screen.x, screen.y);
            else exact.lineTo(screen.x, screen.y);
        }
        exact.close();

        // Quadratic corners turn solitary hexagons into rounded islands and
        // connect neighbouring H3 footprints without sawtooth-looking edges.
        Path rounded = new Path();
        for (int i = 0; i < n; i++) {
            PointF previous = points[(i + n - 1) % n];
            PointF current = points[i];
            PointF next = points[(i + 1) % n];
            float entryX = lerp(current.x, previous.x, CORNER_ROUNDING);
            float entryY = lerp(current.y, previous.y, CORNER_ROUNDING);
            float exitX = lerp(current.x, next.x, CORNER_ROUNDING);
            float exitY = lerp(current.y, next.y, CORNER_ROUNDING);
            if (i == 0) rounded.moveTo(entryX, entryY);
            else rounded.lineTo(entryX, entryY);
            rounded.quadTo(current.x, current.y, exitX, exitY);
        }
        rounded.close();
        return new ProjectedRing(exact, rounded);
    }

    private static float lerp(float a, float b, float factor) {
        return a + (b - a) * factor;
    }

    private void drawForegroundMarkers(Canvas canvas, boolean drawLocation) {
        for (PoiMarker hint : hintMarkers) {
            drawPoiMarker(canvas, hint, 0xFFF9AB00);
        }
        for (PoiMarker discovery : discoveredMarkers) {
            drawPoiMarker(canvas, discovery, 0xFF34A853);
        }
        if (drawLocation && hasLocation) {
            PointF pos = projectPoint(locationLat, locationLng);
            if (pos != null && insideView(pos, 25f * density)) {
                markerPaint.setStyle(Paint.Style.FILL);
                markerPaint.setColor(0x444285F4);
                canvas.drawCircle(pos.x, pos.y, 16f * density, markerPaint);
                markerPaint.setColor(0xFF1A73E8);
                canvas.drawCircle(pos.x, pos.y, 7f * density, markerPaint);
                canvas.drawCircle(pos.x, pos.y, 7f * density, markerOutline);
            }
        }
    }

    private void drawPoiMarker(Canvas canvas, PoiMarker marker, int ringColor) {
        PointF pos = projectPoint(marker.latitude, marker.longitude);
        if (pos == null || !insideView(pos, 24f * density)) return;
        float radius = 13.5f * density;
        markerPaint.setStyle(Paint.Style.FILL);
        markerPaint.setColor(Color.WHITE);
        canvas.drawCircle(pos.x, pos.y, radius, markerPaint);
        markerOutline.setColor(ringColor);
        canvas.drawCircle(pos.x, pos.y, radius, markerOutline);

        // Android's system emoji fallback renders color glyphs without
        // proprietary font/icon packs; tapping still uses MapLibre hit tests.
        markerPaint.setTextAlign(Paint.Align.CENTER);
        markerPaint.setTextSize(16f * density);
        markerPaint.setColor(Color.BLACK);
        canvas.drawText(marker.emoji, pos.x, pos.y + 5.5f * density, markerPaint);
    }

    private PointF projectPoint(double latitude, double longitude) {
        try {
            PointF point = map.getProjection().toScreenLocation(
                    new org.maplibre.android.geometry.LatLng(latitude, longitude));
            return Float.isFinite(point.x) && Float.isFinite(point.y) ? point : null;
        } catch (RuntimeException error) {
            return null;
        }
    }

    private boolean insideView(PointF point, float margin) {
        return point.x >= -margin && point.y >= -margin
                && point.x <= getWidth() + margin
                && point.y <= getHeight() + margin;
    }
}
