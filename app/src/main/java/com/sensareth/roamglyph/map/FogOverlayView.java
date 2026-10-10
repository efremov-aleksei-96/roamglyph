package com.sensareth.roamglyph.map;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RenderNode;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.view.View;

import androidx.annotation.NonNull;

import com.uber.h3core.util.LatLng;

import org.maplibre.android.maps.MapLibreMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Non-quantized vector Fog of War.
 *
 * Exact H3-13 geographical boundaries are projected only when the viewport
 * worker publishes data; the camera reprojection transforms cached vector
 * Paths at ANY zoom, without enlarging a rasterized/antialiased mask.
 *
 * All visited cutouts and gradient bands are clipped against exact H3 cells.
 * The full screen is always dark first; missing coverage fails dark.
 */
public final class FogOverlayView extends View {
    private static final int FOG_COLOR = Color.rgb(17, 20, 24);
    private static final int FOG_ALPHA = 210;

    private final Paint cutoutPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerOutline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix transform = new Matrix();
    private final float density;

    private MapLibreMap map;
    private boolean enabled = true;
    private boolean cacheDirty = true;
    private Path exactPath;
    private Path roundedPath;
    private Path outlinePath;
    private RenderNode fogDisplayList;
    private final LatLng[] referenceGeo = new LatLng[4];
    private final float[] referencePixels = new float[8];
    private final float[] currentPixels = new float[8];
    private float bandWidthPx;
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

        cutoutPaint.setColor(Color.WHITE);
        cutoutPaint.setStyle(Paint.Style.FILL);
        cutoutPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

        gradientPaint.setStyle(Paint.Style.STROKE);
        gradientPaint.setStrokeJoin(Paint.Join.ROUND);
        gradientPaint.setStrokeCap(Paint.Cap.ROUND);
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
            if (map != null) drawForegroundMarkers(canvas, false);
            return;
        }

        if (cacheDirty) {
            cacheDirty = false;
            try {
                buildVectorSnapshot();
            } catch (RuntimeException | OutOfMemoryError failure) {
                clearVectorSnapshot();
            }
        }

        // Unknown geography is always covered before a transformed snapshot
        // is drawn. The exact old viewport may cover only part of the screen.
        int fogLayer = canvas.saveLayer(0f, 0f, getWidth(), getHeight(), null);
        canvas.drawColor(Color.argb(FOG_ALPHA, 17, 20, 24));
        if (map != null && fogDisplayList != null
                && fogDisplayList.hasDisplayList()
                && transformedMaskIsSafe()) {
            int saved = canvas.save();
            canvas.concat(transform);

            // Make room for the correctly transformed cached snapshot,
            // without exposing any pixels not covered by its dark backdrop.
            canvas.drawRect(0, 0, getWidth(), getHeight(), clearPaint);
            if (canvas.isHardwareAccelerated()) {
                // Hardware display list captures five gradient paths once;
                // only one GPU node draw is issued per camera frame.
                canvas.drawRenderNode(fogDisplayList);
            } else {
                renderVectorFog(canvas);
            }
            canvas.restoreToCount(saved);
        }
        canvas.restoreToCount(fogLayer);

        if (map != null) drawForegroundMarkers(canvas, true);
    }

    private void clearVectorSnapshot() {
        if (fogDisplayList != null) fogDisplayList.discardDisplayList();
        fogDisplayList = null;
        exactPath = null;
        roundedPath = null;
        outlinePath = null;
    }

    private void renderVectorFog(@NonNull Canvas canvas) {
        canvas.drawColor(Color.argb(FOG_ALPHA, 17, 20, 24));
        int saved = canvas.save();
        canvas.clipPath(exactPath);
        canvas.drawPath(roundedPath, cutoutPaint);
        canvas.clipPath(roundedPath);
        final float[] widths = {1.0f, 0.78f, 0.56f, 0.35f, 0.16f};
        final int[] alphas = {25, 32, 39, 51, 68};
        for (int i = 0; i < widths.length; i++) {
            gradientPaint.setStrokeWidth(bandWidthPx * widths[i]);
            gradientPaint.setColor(Color.argb(alphas[i], 17, 20, 24));
            canvas.drawPath(outlinePath, gradientPaint);
        }
        canvas.restoreToCount(saved);
    }

    /** Project geographic vertices once per new viewport, not per animation frame. */
    private void buildVectorSnapshot() {
        clearVectorSnapshot();
        if (map == null || getWidth() <= 0 || getHeight() <= 0
                || polygons.isEmpty()) return;

        double zoom = map.getCameraPosition() == null
                ? 15.0 : map.getCameraPosition().zoom;
        double latitude = map.getCameraPosition() == null
                || map.getCameraPosition().target == null
                ? 40.0 : map.getCameraPosition().target.getLatitude();
        double metersPerPixel = 156543.03392
                * Math.cos(Math.toRadians(Math.max(-85.0, Math.min(85.0, latitude))))
                / Math.pow(2.0, zoom);
        float cellDiameterPx = (float) (8.2 / Math.max(0.000001, metersPerPixel));

        // Smaller zigzags are removed from the DISPLAY outline; the exact
        // clip never changes. Long straight stretches stay straight.
        float simplificationPx = Math.max(1.8f * density,
                Math.min(17.0f * density, cellDiameterPx * 0.42f));
        // Do not let the inward gradient from opposite edges swallow a
        // single-cell-width visited trail. Leave a clear central corridor.
        bandWidthPx = Math.max(1.5f * density,
                Math.min(20.0f * density, cellDiameterPx * 0.72f));

        Path exact = new Path();
        Path smoothed = new Path();
        Path outlines = new Path();
        exact.setFillType(Path.FillType.EVEN_ODD);
        smoothed.setFillType(Path.FillType.EVEN_ODD);
        outlines.setFillType(Path.FillType.EVEN_ODD);

        for (List<List<LatLng>> polygon : polygons) {
            for (List<LatLng> ring : polygon) {
                if (ring.size() < 3) continue;
                ProjectedRing projected = projectRoundedRing(ring, simplificationPx);
                exact.addPath(projected.exact);
                smoothed.addPath(projected.rounded);
                outlines.addPath(projected.rounded);
            }
        }

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

        // Publish complete snapshots only. An invalid projection must never
        // leave a partially changed vector mask visible.
        exactPath = exact;
        roundedPath = smoothed;
        outlinePath = outlines;

        // API 29+ RenderNode stores the vector DRAW COMMANDS as a display
        // list; unlike a bitmap it is not quantized to reference pixels.
        // Animating the 4-corner matrix doesn't re-issue five huge paths
        // from the UI draw loop. Android HWUI replays the cached node.
        RenderNode node = new RenderNode("Roamglyph exact fog");
        node.setPosition(0, 0, getWidth(), getHeight());
        Canvas recording = node.beginRecording(getWidth(), getHeight());
        try {
            renderVectorFog(recording);
        } finally {
            node.endRecording();
        }
        fogDisplayList = node;
    }

    private boolean transformedMaskIsSafe() {
        // A cached vector path is not raster-quantized: zooming in cannot
        // enlarge individual antialiased pixels. Keep projecting it at all
        // finite zoom levels instead of switching to an all-dark frame.
        if (map.getCameraPosition() == null) return false;
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

    private static final class ProjectedRing {
        final Path exact;
        final Path rounded;
        ProjectedRing(Path exact, Path rounded) {
            this.exact = exact;
            this.rounded = rounded;
        }
    }

    @NonNull
    private ProjectedRing projectRoundedRing(
            @NonNull List<LatLng> ring, float tolerancePx) {
        int n = ring.size();
        if (n > 3) {
            LatLng first = ring.get(0);
            LatLng last = ring.get(n - 1);
            if (Math.abs(first.lat - last.lat) < 1e-12
                    && Math.abs(first.lng - last.lng) < 1e-12) n--;
        }
        if (n < 3) return new ProjectedRing(new Path(), new Path());

        List<FogContourSmoother.Vertex> screenPoints = new ArrayList<>(n);
        Path exact = new Path();
        for (int i = 0; i < n; i++) {
            LatLng coordinate = ring.get(i);
            PointF screen = projectPoint(coordinate.lat, coordinate.lng);
            if (screen == null) throw new IllegalArgumentException("Projection failed");
            screenPoints.add(new FogContourSmoother.Vertex(screen.x, screen.y));
            if (i == 0) exact.moveTo(screen.x, screen.y);
            else exact.lineTo(screen.x, screen.y);
        }
        exact.close();

        // An isolated H3 cell should never look like a small rounded
        // hexagon. Display it as an inscribed smooth circle instead. It stays
        // entirely inside the exact hex footprint and cannot over-reveal.
        if (n == 6) {
            Path circle = inscribedSingleCellCircle(screenPoints);
            if (circle != null) return new ProjectedRing(exact, circle);
        }

        List<FogContourSmoother.Vertex> simplified =
                FogContourSmoother.simplifyClosed(screenPoints, tolerancePx);
        int size = simplified.size();
        Path rounded = new Path();
        // Long RDP-simplified segments are represented as lines; curves only
        // round the *corners*. This avoids hexagonal zigzags and excessive
        // snaking while retaining smooth bends.
        for (int i = 0; i < size; i++) {
            FogContourSmoother.Vertex prev = simplified.get((i + size - 1) % size);
            FogContourSmoother.Vertex at = simplified.get(i);
            FogContourSmoother.Vertex next = simplified.get((i + 1) % size);
            float incoming = (float) Math.hypot(at.x - prev.x, at.y - prev.y);
            float outgoing = (float) Math.hypot(next.x - at.x, next.y - at.y);
            float roundingPx = Math.min(
                    10.0f * density, Math.min(incoming, outgoing) * 0.45f);
            float fractionIn = incoming > 0.0001f ? roundingPx / incoming : 0f;
            float fractionOut = outgoing > 0.0001f ? roundingPx / outgoing : 0f;
            float entryX = lerp(at.x, prev.x, fractionIn);
            float entryY = lerp(at.y, prev.y, fractionIn);
            float exitX = lerp(at.x, next.x, fractionOut);
            float exitY = lerp(at.y, next.y, fractionOut);
            if (i == 0) rounded.moveTo(entryX, entryY);
            else rounded.lineTo(entryX, entryY);
            rounded.quadTo(at.x, at.y, exitX, exitY);
        }
        rounded.close();
        return new ProjectedRing(exact, rounded);
    }

    private Path inscribedSingleCellCircle(
            List<FogContourSmoother.Vertex> vertices) {
        float centerX = 0f, centerY = 0f;
        float shortestSide = Float.MAX_VALUE, longestSide = 0f;
        for (int i = 0; i < 6; i++) {
            FogContourSmoother.Vertex p = vertices.get(i);
            FogContourSmoother.Vertex next = vertices.get((i + 1) % 6);
            centerX += p.x / 6f;
            centerY += p.y / 6f;
            float side = (float) Math.hypot(next.x - p.x, next.y - p.y);
            shortestSide = Math.min(shortestSide, side);
            longestSide = Math.max(longestSide, side);
        }
        // Reject heavily skewed polygons: these might not represent a
        // regular single cell in the current tilted camera projection.
        if (shortestSide < 0.01f || longestSide > shortestSide * 1.8f)
            return null;

        float radius = Float.MAX_VALUE;
        for (int i = 0; i < 6; i++) {
            FogContourSmoother.Vertex a = vertices.get(i);
            FogContourSmoother.Vertex b = vertices.get((i + 1) % 6);
            float dx = b.x - a.x, dy = b.y - a.y;
            float length = (float) Math.hypot(dx, dy);
            float distance = Math.abs(
                    (centerX - a.x) * dy - (centerY - a.y) * dx) / length;
            radius = Math.min(radius, distance);
        }
        if (!Float.isFinite(radius) || radius <= 0) return null;
        Path circle = new Path();
        circle.addCircle(centerX, centerY, 0.98f * radius, Path.Direction.CW);
        return circle;
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
                markerOutline.setColor(Color.WHITE);
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
