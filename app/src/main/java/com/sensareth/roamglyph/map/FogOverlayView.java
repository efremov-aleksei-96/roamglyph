package com.sensareth.roamglyph.map;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.annotation.NonNull;

import com.uber.h3core.util.LatLng;

import org.maplibre.android.maps.MapLibreMap;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Screen-attached fog with an exact-H3, supersampled, inward-feathered
 * raster alpha cutout. A mask pixel cannot cut outside the original H3
 * coverage: the CPU feather never increases source alpha, and the GPU
 * display list additionally clips the raster against exact vector cells.
 *
 * The worker prepares snapshots without blocking camera animations. The
 * last complete snapshot remains visible until the latest one is ready.
 * Only one bitmap draw and exact-geometry GPU clip run per camera frame;
 * geographic vertices are never reprojected in the draw loop.
 */
public final class FogOverlayView extends View {
    private static final int FOG_ALPHA = 210;

    private final Paint cutoutPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerOutline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix transform = new Matrix();
    private final float density;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final AtomicReference<MaskJob> queuedJob = new AtomicReference<>();
    private final AtomicBoolean workerRunning = new AtomicBoolean(false);
    private ExecutorService featherWorker = Executors.newSingleThreadExecutor();

    private MapLibreMap map;
    private boolean enabled = true;
    private boolean cacheDirty = true;
    private boolean disposed;
    private int revision;
    private int snapshotWidth;
    private int snapshotHeight;
    private Bitmap[] maskMipmaps = new Bitmap[0];
    private double referenceZoom;
    private Path snapshotExactPath;
    private final LatLng[] referenceGeo = new LatLng[4];
    private final float[] referencePixels = new float[8];
    private final float[] currentPixels = new float[8];
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

    private static final class MaskJob {
        final int revision;
        final Path exact;
        final int width;
        final int height;
        final double zoom;
        final float cellDiameterPx;
        final float density;
        final LatLng[] geoCorners;
        final float[] pixelCorners;

        MaskJob(int revision, Path exact, int width, int height,
                double zoom, float cellDiameterPx, float density,
                LatLng[] geoCorners, float[] pixelCorners) {
            this.revision = revision;
            this.exact = exact;
            this.width = width;
            this.height = height;
            this.zoom = zoom;
            this.cellDiameterPx = cellDiameterPx;
            this.density = density;
            this.geoCorners = geoCorners;
            this.pixelCorners = pixelCorners;
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
        if (!value) clearVectorSnapshot();
        else cacheDirty = true;
        invalidate();
    }

    public void setGeometry(@NonNull List<List<List<LatLng>>> newPolygons) {
        polygons = newPolygons;
        cacheDirty = true;
        if (newPolygons.isEmpty()) clearVectorSnapshot();
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
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (disposed) {
            disposed = false;
            featherWorker = Executors.newSingleThreadExecutor();
            cacheDirty = true;
            invalidate();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        disposed = true;
        clearVectorSnapshot();
        featherWorker.shutdownNow();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        clearVectorSnapshot();
        cacheDirty = true;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (!enabled) {
            if (map != null) drawForegroundMarkers(canvas, false);
            return;
        }

        if (cacheDirty && !disposed) {
            cacheDirty = false;
            try {
                buildVectorSnapshot();
            } catch (RuntimeException | OutOfMemoryError failure) {
                clearVectorSnapshot();
            }
        }

        // Fail dark for all unknown pixels, including those outside the old
        // viewport during a fast gesture. Bitmap reuse cannot over-reveal,
        // because the bitmap is clipped to current-scale exact H3 geometry.
        int fogLayer = canvas.saveLayer(0f, 0f, getWidth(), getHeight(), null);
        canvas.drawColor(Color.argb(FOG_ALPHA, 17, 20, 24));
        if (map != null && maskMipmaps.length > 0
                && snapshotExactPath != null && transformedMaskIsSafe()) {
            double currentZoom = map.getCameraPosition().zoom;
            int level = FogMipLevel.forZoomDelta(
                    referenceZoom - currentZoom, maskMipmaps.length);
            int saved = canvas.save();
            canvas.concat(transform);
            // Exact vector clipping occurs at the CURRENT zoom, not
            // in a cached source-resolution display-list texture.
            // Bilinear magnification cannot reveal outside H3 cells.
            canvas.clipPath(snapshotExactPath);
            canvas.drawBitmap(maskMipmaps[level], null,
                    new RectF(0f, 0f, snapshotWidth, snapshotHeight),
                    cutoutPaint);
            canvas.restoreToCount(saved);
        }
        canvas.restoreToCount(fogLayer);

        if (map != null) drawForegroundMarkers(canvas, true);
    }

    private void clearVectorSnapshot() {
        revision++;
        queuedJob.set(null);
        recycleMasks(maskMipmaps);
        maskMipmaps = new Bitmap[0];
        snapshotExactPath = null;
        for (int i = 0; i < referenceGeo.length; i++) referenceGeo[i] = null;
    }

    private static void recycleMasks(Bitmap[] masks) {
        if (masks == null) return;
        for (Bitmap mask : masks) if (mask != null) mask.recycle();
    }

    /** Project exact H3 geography once per refreshed viewport. */
    private void buildVectorSnapshot() {
        if (map == null || getWidth() <= 0 || getHeight() <= 0
                || polygons.isEmpty()) {
            clearVectorSnapshot();
            return;
        }
        double zoom = map.getCameraPosition() == null
                ? 15.0 : map.getCameraPosition().zoom;
        double latitude = map.getCameraPosition() == null
                || map.getCameraPosition().target == null
                ? 40.0 : map.getCameraPosition().target.getLatitude();
        double metersPerPixel = 156543.03392
                * Math.cos(Math.toRadians(Math.max(-85.0,
                        Math.min(85.0, latitude))))
                / Math.pow(2.0, zoom);
        float cellDiameterPx = (float) (8.2
                / Math.max(0.000001, metersPerPixel));

        Path exact = new Path();
        exact.setFillType(Path.FillType.EVEN_ODD);
        for (List<List<LatLng>> polygon : polygons) {
            for (List<LatLng> ring : polygon) {
                if (ring.size() < 3) continue;
                Path ringPath = new Path();
                for (int i = 0; i < ring.size(); i++) {
                    LatLng point = ring.get(i);
                    PointF screen = projectPoint(point.lat, point.lng);
                    if (screen == null)
                        throw new IllegalArgumentException("Projection failed");
                    if (i == 0) ringPath.moveTo(screen.x, screen.y);
                    else ringPath.lineTo(screen.x, screen.y);
                }
                ringPath.close();
                exact.addPath(ringPath);
            }
        }

        float width = getWidth(), height = getHeight();
        float[] corners = {0f, 0f, width, 0f, width, height, 0f, height};
        LatLng[] geo = new LatLng[4];
        for (int i = 0; i < 4; i++) {
            org.maplibre.android.geometry.LatLng point =
                    map.getProjection().fromScreenLocation(
                            new PointF(corners[i * 2], corners[i * 2 + 1]));
            geo[i] = new LatLng(point.getLatitude(), point.getLongitude());
        }
        MaskJob job = new MaskJob(++revision, exact, getWidth(), getHeight(),
                zoom, cellDiameterPx, density, geo, corners);
        queuedJob.set(job);
        startWorkerIfNeeded();
    }

    private void startWorkerIfNeeded() {
        if (disposed || !workerRunning.compareAndSet(false, true)) return;
        try {
            featherWorker.execute(this::drainMaskJobs);
        } catch (RuntimeException failure) {
            workerRunning.set(false);
            clearVectorSnapshot();
        }
    }

    private void drainMaskJobs() {
        try {
            MaskJob job;
            while (!Thread.currentThread().isInterrupted()
                    && (job = queuedJob.getAndSet(null)) != null) {
                final MaskJob completedJob = job;
                Bitmap[] masks = null;
                try {
                    masks = FogRasterFeather.createPyramid(
                            job.exact, job.width, job.height,
                            job.cellDiameterPx, job.density);
                } catch (RuntimeException | OutOfMemoryError ignored) {
                    // The main thread will fail dark rather than use a
                    // partial or incorrectly feathered mask.
                }
                final Bitmap[] result = masks;
                uiHandler.post(() -> publishMask(completedJob, result));
            }
        } finally {
            workerRunning.set(false);
            if (!disposed && queuedJob.get() != null) startWorkerIfNeeded();
        }
    }

    private void publishMask(MaskJob job, Bitmap[] masks) {
        if (disposed || !enabled || job.revision != revision
                || job.width != getWidth() || job.height != getHeight()
                || masks == null || masks.length == 0) {
            recycleMasks(masks);
            if ((masks == null || masks.length == 0)
                    && !disposed && job.revision == revision) {
                clearVectorSnapshot();
                invalidate();
            }
            return;
        }
        recycleMasks(maskMipmaps);
        maskMipmaps = masks;
        referenceZoom = job.zoom;
        snapshotExactPath = job.exact;
        snapshotWidth = job.width;
        snapshotHeight = job.height;
        System.arraycopy(job.geoCorners, 0, referenceGeo, 0, 4);
        System.arraycopy(job.pixelCorners, 0, referencePixels, 0, 8);
        invalidate();
    }

    private boolean transformedMaskIsSafe() {
        if (map.getCameraPosition() == null) return false;
        for (int i = 0; i < 4; i++) {
            LatLng point = referenceGeo[i];
            if (point == null) return false;
            PointF screen = map.getProjection().toScreenLocation(
                    new org.maplibre.android.geometry.LatLng(
                            point.lat, point.lng));
            if (!Float.isFinite(screen.x) || !Float.isFinite(screen.y)
                    || Math.abs(screen.x) > 10_000_000f
                    || Math.abs(screen.y) > 10_000_000f) return false;
            currentPixels[2 * i] = screen.x;
            currentPixels[2 * i + 1] = screen.y;
        }
        return transform.setPolyToPoly(
                referencePixels, 0, currentPixels, 0, 4);
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
