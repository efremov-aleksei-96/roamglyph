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
import android.os.SystemClock;
import android.os.Trace;
import android.view.View;

import androidx.annotation.NonNull;

import com.uber.h3core.H3Core;
import com.uber.h3core.util.LatLng;

import org.maplibre.android.maps.MapLibreMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Geographic, world-anchored cached fog tiles. No bitmap is clipped to the
 * camera viewport: H3 coverage is queried/rasterized independently per tile.
 * Completed tiles follow the MapLibre camera synchronously during gestures;
 * new/preloaded tiles are calculated off the UI thread.
 *
 * A full-screen fog layer always covers unknown regions. Every tile's
 * smoothed alpha is source-gated and clipped to an exact res-13 H3 Path.
 */
public final class FogOverlayView extends View {
    private static final int FOG_ALPHA = 210;
    private static final int MAX_CACHE = 1024;
    private static final int MAX_DEMAND = 32;
    private static final int MAX_VISIBLE = 96;
    private static final long MAX_CACHE_BYTES = 64L * 1024L * 1024L;
    private static final long DEMAND_INTERVAL_MS = 110L;
    private static final RectF TILE_DEST = new RectF(
            0f, 0f, FogWorldTileScheme.TILE_PX, FogWorldTileScheme.TILE_PX);
    private static final float[] FRAME_SOURCE = {
            0f, 0f, FogWorldTileScheme.TILE_PX, 0f,
            FogWorldTileScheme.TILE_PX, FogWorldTileScheme.TILE_PX,
            0f, FogWorldTileScheme.TILE_PX
    };

    private final Paint cutoutPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerOutline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix tileMatrix = new Matrix();
    // One geographic-to-screen homography per frame, NOT four native
    // MapLibre projections for each cached geographic tile.
    private final Matrix worldToScreen = new Matrix();
    private final float[] corners = new float[8];
    private final float[] target = new float[8];
    private final float density;
    private final LinkedHashMap<FogWorldTileScheme.Key, Tile> cache =
            new LinkedHashMap<>(48, 0.75f, true);
    private final ConcurrentLinkedQueue<TileJob> jobs = new ConcurrentLinkedQueue<>();
    private final Set<FogWorldTileScheme.Key> pending = new HashSet<>();
    private Set<FogWorldTileScheme.Key> pinned = Collections.emptySet();
    private long cacheBytes;
    private float lastCoarsenScale = Float.POSITIVE_INFINITY;
    private final AtomicBoolean workerRunning = new AtomicBoolean(false);
    private ExecutorService worker = Executors.newSingleThreadExecutor();

    private MapLibreMap map;
    private H3Core h3;
    private ExplorationCoverageIndex coverage;
    private volatile FogPerformanceDiagnostics diagnostics;

    /** Debug-only sampling; nullable in production builds. */
    public void setPerformanceDiagnostics(FogPerformanceDiagnostics value) {
        diagnostics = value;
    }
    private volatile boolean disposed;
    private volatile boolean enabled = true;
    private volatile boolean rendererV2;
    private volatile boolean rendererV3;
    private volatile FogGpsCorridors gpsCorridors = FogGpsCorridors.empty();
    private volatile int coverageEpoch = 1;
    private volatile Set<FogWorldTileScheme.Key> wanted = Collections.emptySet();
    private List<FogWorldTileScheme.Key> visible = Collections.emptyList();
    private volatile float latestRasterDemand = 1.0f;
    private int requestedZoom = -1;
    private int displayZoom = -1;
    private long lastDemandAt;

    private boolean hasLocation;
    private double locationLat;
    private double locationLng;
    private List<PoiMarker> discoveredMarkers = Collections.emptyList();
    private List<PoiMarker> hintMarkers = Collections.emptyList();

    /** Foreground markers do not depend on the fog tile life cycle. */
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

    private static final class TileJob {
        final FogWorldTileScheme.Key key;
        final int epoch;
        final float rasterScale;
        final long enqueuedAtNanos;
        // Sticky: the camera may leave and re-enter this tile before the
        // worker posts its result. Never reinterpret an aborted job as
        // a deterministic empty H3 tile.
        volatile boolean wasCancelled;
        TileJob(FogWorldTileScheme.Key key, int epoch, float rasterScale) {
            this.key = key;
            this.epoch = epoch;
            this.rasterScale = rasterScale;
            this.enqueuedAtNanos = SystemClock.elapsedRealtimeNanos();
        }
    }

    private static final class Tile {
        final FogWorldTileScheme.Key key;
        final Path exactPath;
        Bitmap[] mipmaps;
        final int epoch;

        Tile(FogWorldTileScheme.Key key, Path exactPath,
             Bitmap[] mipmaps, int epoch) {
            this.key = key;
            this.exactPath = exactPath;
            this.mipmaps = mipmaps;
            this.epoch = epoch;
        }

        /** Drop the highest-res bitmap, retaining its already area-filtered mip. */
        boolean reduceOneLevel() {
            if (mipmaps.length <= 1) return false;
            Bitmap old = mipmaps[0];
            Bitmap[] remaining = new Bitmap[mipmaps.length - 1];
            System.arraycopy(mipmaps, 1, remaining, 0, remaining.length);
            mipmaps = remaining;
            old.recycle();
            return true;
        }

        float effectiveScale() {
            return mipmaps.length == 0 ? 0f
                    : mipmaps[0].getWidth() / (float) FogWorldTileScheme.TILE_PX;
        }

        long bytes() {
            long total = 0;
            for (Bitmap mask : mipmaps) if (mask != null && !mask.isRecycled()) {
                total += mask.getAllocationByteCount();
            }
            return total;
        }

        void recycle() {
            for (Bitmap mask : mipmaps) if (mask != null && !mask.isRecycled()) {
                mask.recycle();
            }
        }
    }

    public FogOverlayView(@NonNull Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setClickable(false);
        setWillNotDraw(false);
        // The foreground layer MUST have its own compositing surface. The
        // DST_OUT bitmaps punch holes only in this View's dark texture, never
        // in the underlying MapLibre surface. This persistent layer replaces
        // allocating a full-screen saveLayer on every animated camera frame.
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        cutoutPaint.setColor(Color.WHITE);
        cutoutPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        markerOutline.setColor(Color.WHITE);
        markerOutline.setStyle(Paint.Style.STROKE);
        markerOutline.setStrokeWidth(2f * density);
    }

    public void attachMap(@NonNull MapLibreMap attachedMap) {
        map = attachedMap;
        requestTiles(true);
        invalidate();
    }

    public void attachCoverage(@NonNull H3Core attachedH3,
                               @NonNull ExplorationCoverageIndex attachedCoverage) {
        h3 = attachedH3;
        coverage = attachedCoverage;
        requestTiles(true);
    }

    /** History updates are monotonic except when a backup resets coverage. */
    public void onCoverageChanged(boolean mayHaveRemovedCells) {
        coverageEpoch++;
        jobs.clear();
        pending.clear();
        if (mayHaveRemovedCells) {
            clearCache();
        }
        requestTiles(true);
        invalidate();
    }

    /** Switch A/B raster generators without changing GPS, H3 or Room data. */
    public void setRendererV2(boolean useV2) {
        if (rendererV2 == useV2) return;
        rendererV2 = useV2;
        // A world-tile bitmap must never be mistaken for the other renderer's
        // output. Cancel all in-flight jobs and rebuild from the same H3 index.
        coverageEpoch++;
        jobs.clear();
        pending.clear();
        wanted = Collections.emptySet();
        clearCache();
        requestTiles(true);
        invalidate();
    }

    public boolean isRendererV2() { return rendererV2; }

    /** V3 changes only the visual mask; accepted GPS data remains immutable. */
    public void setRendererV3(boolean enabled) {
        if (rendererV3 == enabled) return;
        rendererV3 = enabled;
        coverageEpoch++;
        jobs.clear();
        pending.clear();
        wanted = Collections.emptySet();
        clearCache();
        requestTiles(true);
        invalidate();
    }

    public void setGpsCorridors(@NonNull FogGpsCorridors snapshot) {
        gpsCorridors = snapshot;
        if (!rendererV3) return;
        coverageEpoch++;
        jobs.clear();
        pending.clear();
        clearCache();
        requestTiles(true);
        invalidate();
    }

    public void setFogEnabled(boolean value) {
        enabled = value;
        if (!value) {
            coverageEpoch++;
            jobs.clear();
            pending.clear();
            wanted = Collections.emptySet();
            clearCache();
        } else {
            requestTiles(true);
        }
        invalidate();
    }

    public void setCurrentLocation(boolean available, double lat, double lng) {
        hasLocation = available;
        locationLat = lat;
        locationLng = lng;
        invalidate();
    }

    public void setDiscoveries(@NonNull List<PoiMarker> discovered,
                               @NonNull List<PoiMarker> hints) {
        discoveredMarkers = discovered;
        hintMarkers = hints;
        invalidate();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (disposed) {
            disposed = false;
            worker = Executors.newSingleThreadExecutor();
            requestTiles(true);
        }
    }

    @Override protected void onDetachedFromWindow() {
        disposed = true;
        coverageEpoch++;
        jobs.clear();
        pending.clear();
        wanted = Collections.emptySet();
        worker.shutdownNow();
        clearCache();
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        requestTiles(true);
    }

    private void clearCache() {
        for (Tile tile : cache.values()) tile.recycle();
        cache.clear();
        cacheBytes = 0L;
        lastCoarsenScale = Float.POSITIVE_INFINITY;
        displayZoom = -1;
        pinned = Collections.emptySet();
    }

    /** Android memory pressure: discard textures rather than triggering OOM. */
    public void trimForLowMemory() {
        coverageEpoch++; // Cancel an active raster job, not just queued work.
        jobs.clear();
        pending.clear();
        wanted = Collections.emptySet();
        clearCache();
        invalidate();
    }

    /**
     * Cheap camera-move demand update. No H3 unions, polygons or raster
     * projections execute on the UI thread.
     */
    public void requestTiles() { requestTiles(false); }

    /** Camera-idle/coverage refresh bypasses the gesture throttle. */
    public void requestTilesNow() { requestTiles(true); }

    private void requestTiles(boolean forced) {
        if (disposed || !enabled || map == null || h3 == null
                || coverage == null || getWidth() < 1 || getHeight() < 1) return;
        long now = SystemClock.uptimeMillis();
        if (!forced && now - lastDemandAt < DEMAND_INTERVAL_MS) return;
        lastDemandAt = now;
        long startedNanos = SystemClock.elapsedRealtimeNanos();
        int hits = 0, misses = 0, queued = 0;
        try {
            org.maplibre.android.camera.CameraPosition camera = map.getCameraPosition();
            if (camera == null) return;
            int suggestedZoom = FogWorldTileScheme.zoomLevel(camera.zoom);
            if (requestedZoom < 0 ||
                    (suggestedZoom != requestedZoom &&
                            Math.abs(camera.zoom - requestedZoom) >= 0.85)) {
                requestedZoom = suggestedZoom;
            }
            org.maplibre.android.geometry.LatLngBounds b =
                    map.getProjection().getVisibleRegion().latLngBounds;
            double north = b.getLatNorth(), south = b.getLatSouth();
            double east = b.getLonEast(), west = b.getLonWest();
            // Large or pitched viewports must use a coarser *render grid*
            // rather than dropping any visible geographic tile. H3 cell
            // storage and the conservative exact cutout are unchanged.
            requestedZoom = FogWorldTileScheme.budgetedZoom(
                    north, east, south, west, requestedZoom, MAX_VISIBLE);
            List<FogWorldTileScheme.Key> newVisible =
                    FogWorldTileScheme.covering(
                            north, east, south, west, requestedZoom, 0, MAX_VISIBLE);
            List<FogWorldTileScheme.Key> preload =
                    FogWorldTileScheme.covering(
                            north, east, south, west, requestedZoom, 1, MAX_DEMAND);
            visible = newVisible;
            // Always demand every visible tile before any prefetch halo.
            // Otherwise sorting a 32-tile cap over the halo could exclude
            // a corner that is actually on screen.
            java.util.LinkedHashSet<FogWorldTileScheme.Key> prioritized =
                    new java.util.LinkedHashSet<>(newVisible);
            int budgeted = Math.max(MAX_DEMAND, newVisible.size());
            for (FogWorldTileScheme.Key key : preload) {
                if (prioritized.size() >= budgeted) break;
                prioritized.add(key);
            }
            wanted = Collections.unmodifiableSet(new HashSet<>(prioritized));

            // Protect both the new visible set and the old visible zoom
            // during a transition. Halo tiles may be evicted first.
            Set<FogWorldTileScheme.Key> keep = new HashSet<>(newVisible);
            if (displayZoom >= 0 && displayZoom != requestedZoom) {
                // Pin every resident old-zoom tile intersecting the camera.
                // An enumerated grid limited to 512 entries can exclude an
                // edge tile that drawCachedTiles() still intends to show.
                for (FogWorldTileScheme.Key oldKey : cache.keySet()) {
                    if (oldKey.z == displayZoom
                            && FogWorldTileScheme.intersectsBounds(
                                    oldKey, north, east, south, west)) {
                        keep.add(oldKey);
                    }
                }
            }
            pinned = keep;

            // Tile-local geometry is immutable for a given coverage epoch.
            // An older completed tile remains visible while its updated
            // version renders, preventing area flashing during movement.
            int epoch = coverageEpoch;
            // Gigantic tablets/external displays can show dozens of tiles
            // simultaneously. Lower their raster resolution proactively
            // rather than silently dropping on-screen geography or OOMing.
            float rasterScale =
                    FogWorldTileScheme.rasterScaleForVisibleTiles(newVisible.size());
            latestRasterDemand = rasterScale;
            // Resizing or rotating can raise the number of visible tiles.
            // Previously cached full-resolution bitmaps must shrink with
            // the new generation; otherwise mixed scales break the byte
            // budget and lead to eviction of visible content.
            // A camera fling typically keeps the same raster scale. Never
            // traverse/recycle the entire LRU every 110ms in that case.
            if (rasterScale < lastCoarsenScale * 0.98f) {
                coarsenCachedTilesTo(rasterScale);
            }
            lastCoarsenScale = rasterScale;
            if (cache.size() > MAX_CACHE || cacheBytes > MAX_CACHE_BYTES) {
                trimCacheToBudget();
            }
            for (FogWorldTileScheme.Key key : prioritized) {
                Tile current = cache.get(key);
                if (current == null) misses++; else hits++;
                // Zooming back into a normal-size viewport can restore a
                // higher raster budget after visible mipmaps were coarsened.
                // Rebuild such cells asynchronously; retain the old tile
                // until the sharper replacement is ready.
                boolean undersampled = current != null
                        && current.mipmaps.length > 0
                        && FogWorldTileScheme.needsSharperRaster(
                                current.effectiveScale(), rasterScale);
                if ((current == null || current.epoch != epoch || undersampled)
                        && pending.add(key)) {
                    jobs.add(new TileJob(key, epoch, rasterScale));
                    queued++;
                }
            }
            startWorker();
            invalidate();
        } catch (RuntimeException ignored) {
            // Unprojectable poles/dateline or an incomplete style: fail dark.
        } finally {
            FogPerformanceDiagnostics current = diagnostics;
            if (current != null) current.recordDemand(
                    SystemClock.elapsedRealtimeNanos() - startedNanos,
                    hits, misses, queued, visible.size(), pending.size(),
                    cache.size(), cacheBytes);
        }
    }

    private void startWorker() {
        if (disposed || !enabled || !workerRunning.compareAndSet(false, true)) return;
        try {
            worker.execute(this::drainJobs);
        } catch (RuntimeException e) {
            workerRunning.set(false);
        }
    }

    private void drainJobs() {
        try {
            TileJob job;
            while (!Thread.currentThread().isInterrupted()
                    && (job = jobs.poll()) != null) {
                final TileJob work = job;
                Tile tile = null;
                long buildStartedNanos = SystemClock.elapsedRealtimeNanos();
                if (!cancelled(work)) {
                    try {
                        tile = buildTile(work);
                    } catch (RuntimeException | OutOfMemoryError ignored) {
                        // Unknown geography remains safely dark.
                    }
                }
                FogPerformanceDiagnostics metrics = diagnostics;
                if (metrics != null) metrics.recordTileBuild(
                        Math.max(0L, buildStartedNanos - work.enqueuedAtNanos),
                        SystemClock.elapsedRealtimeNanos() - buildStartedNanos,
                        tile != null && !work.wasCancelled);
                Tile result = tile;
                // A detached view can reject posts; do not leak the
                // finished bitmap when no UI consumer will receive it.
                if (!post(() -> finishTile(work, result)) && result != null) {
                    result.recycle();
                }
            }
        } finally {
            workerRunning.set(false);
            if (!disposed && !jobs.isEmpty()) startWorker();
        }
    }

    private boolean cancelled(TileJob job) {
        boolean aborted = Thread.currentThread().isInterrupted() || disposed
                || !enabled || job.epoch != coverageEpoch
                || !wanted.contains(job.key);
        if (aborted) job.wasCancelled = true;
        return aborted;
    }

    private Tile buildTile(TileJob job) {
        FogWorldTileScheme.Key key = job.key;
        // The index is shared with MainActivity's serialized GPS updates.
        // Both operations use this lock; no mutable spatial HashMap is read
        // concurrently with addAll/replaceAll.
        ViewportOverlayBuilder.Result result;
        synchronized (coverage) {
            if (cancelled(job)) return null;
            result = ViewportOverlayBuilder.build(h3, coverage,
                    FogWorldTileScheme.north(key),
                    FogWorldTileScheme.east(key),
                    FogWorldTileScheme.south(key),
                    FogWorldTileScheme.west(key), key.z);
        }
        if (cancelled(job) || result.preservePreviousGeometry) return null;

        Path exact = new Path();
        exact.setFillType(Path.FillType.EVEN_ODD);
        for (List<List<LatLng>> polygon : result.polygons) {
            if (cancelled(job)) return null;
            for (List<LatLng> ring : polygon) {
                if (ring.size() < 3) continue;
                Path ringPath = new Path();
                boolean first = true;
                float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
                float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
                for (LatLng geo : ring) {
                    float x = FogWorldTileScheme.localX(geo.lng, key);
                    float y = FogWorldTileScheme.localY(geo.lat, key);
                    if (!Float.isFinite(x) || !Float.isFinite(y)) continue;
                    if (first) ringPath.moveTo(x, y);
                    else ringPath.lineTo(x, y);
                    first = false;
                    minX = Math.min(minX, x); minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
                }
                // The feather raster reads off-tile source pixels.
                // Include rings intersecting that neighbourhood as well.
                if (!first && maxX >= -300 && minX <=
                        FogWorldTileScheme.TILE_PX + 300
                        && maxY >= -300 && minY <=
                        FogWorldTileScheme.TILE_PX + 300) {
                    ringPath.close();
                    exact.addPath(ringPath);
                }
            }
        }

        if (cancelled(job)) return null;
        if (exact.isEmpty()) return new Tile(key, exact, new Bitmap[0], job.epoch);

        double centerLat = (FogWorldTileScheme.north(key)
                + FogWorldTileScheme.south(key)) * .5;
        double mpp = 40075016.68557849 *
                Math.cos(Math.toRadians(centerLat))
                / (FogWorldTileScheme.TILE_PX * Math.pow(2, key.z));
        float diameter = (float) (8.2 / Math.max(0.000001, mpp));
        FogGpsCorridors gpsSnapshot = gpsCorridors;
        Bitmap[] masks = rendererV3
                ? FogDistanceFieldRaster.createPyramid(
                        exact, gpsSnapshot.tilePath(key),
                        FogWorldTileScheme.TILE_PX, diameter,
                        job.rasterScale, () -> cancelled(job))
                : rendererV2
                ? FogDistanceFieldRaster.createPyramid(
                        exact, FogWorldTileScheme.TILE_PX, diameter,
                        job.rasterScale, () -> cancelled(job))
                : FogRasterFeather.createPyramid(
                        exact, FogWorldTileScheme.TILE_PX, FogWorldTileScheme.TILE_PX,
                        diameter, density, () -> cancelled(job), job.rasterScale);
        if (cancelled(job)) {
            for (Bitmap bitmap : masks) bitmap.recycle();
            return null;
        }
        return new Tile(key, exact, masks, job.epoch);
    }

    private void finishTile(TileJob job, Tile tile) {
        pending.remove(job.key);
        if (job.wasCancelled || disposed || !enabled || job.epoch != coverageEpoch
                || !wanted.contains(job.key)) {
            if (tile != null) tile.recycle();
            // A cancelled tile can become wanted again during a rapid
            // back-and-forth pan. Requeue only when it is genuinely wanted,
            // never cache an aborted result as a terminal blank tile.
            if (!disposed && enabled && wanted.contains(job.key)) requestTiles(true);
            return;
        }
        if (tile == null) {
            // A cancelled, over-budget or failed tile is NOT an empty tile.
            // Do not mark this geographic key as "ready" with a blank cache
            // entry: that made explored routes permanently disappear while
            // zooming out. The next gesture/idle requests it again; existing
            // exact tiles remain intact until a valid replacement arrives.
            return;
        }
        Tile old = cache.put(job.key, tile);
        if (old != null) {
            cacheBytes -= old.bytes();
            old.recycle();
        }
        cacheBytes += tile.bytes();
        trimCacheToBudget();
        // A low-scale job can have been queued BEFORE the camera returned
        // to a smaller viewport. pending.add() then suppressed a sharper
        // request. Install the conservative older bitmap now, but schedule
        // one newer replacement without waiting for another user gesture.
        // Comparing the original job budget avoids retry loops when memory
        // pressure makes the desired scale physically unattainable.
        float desired = latestRasterDemand;
        if (tile.mipmaps.length > 0 && wanted.contains(job.key)
                && FogWorldTileScheme.needsSharperRaster(job.rasterScale, desired)
                && pending.add(job.key)) {
            jobs.add(new TileJob(job.key, job.epoch, desired));
            startWorker();
        }
        invalidate();
    }

    private void coarsenCachedTilesTo(float targetScale) {
        for (Tile tile : cache.values()) {
            // A tile that is already area-minified does not need a new H3
            // union; its next mipmap is the same source-alpha-bounded field.
            while (tile.effectiveScale() > targetScale * 1.1f
                    && tile.mipmaps.length > 1) {
                long oldSize = tile.bytes();
                if (!tile.reduceOneLevel()) break;
                cacheBytes -= oldSize - tile.bytes();
            }
        }
    }

    private void trimCacheToBudget() {
        // First discard off-camera tiles; never evict visible ones because
        // the zoom handoff requires every visible tile to be resident.
        while ((cache.size() > MAX_CACHE || cacheBytes > MAX_CACHE_BYTES)
                && !cache.isEmpty()) {
            FogWorldTileScheme.Key victim = null;
            for (FogWorldTileScheme.Key key : cache.keySet()) {
                if (!pinned.contains(key)) { victim = key; break; }
            }
            if (victim != null) {
                Tile removed = cache.remove(victim);
                if (removed != null) {
                    cacheBytes -= removed.bytes();
                    removed.recycle();
                }
                continue;
            }

            // If two very large visible generations alone exceed the byte
            // budget, lower their already-filtered raster resolution.
            // Preserve all their geographic extents and exact vector clips.
            Tile largest = null;
            for (Tile tile : cache.values()) {
                if (tile.mipmaps.length > 1 &&
                        (largest == null || tile.bytes() > largest.bytes())) {
                    largest = tile;
                }
            }
            if (largest == null) break; // all tiles already minimum resolution
            long before = largest.bytes();
            largest.reduceOneLevel();
            cacheBytes -= before - largest.bytes();
        }
    }

    @Override protected void onDraw(@NonNull Canvas canvas) {
        long drawStartedNanos = SystemClock.elapsedRealtimeNanos();
        Trace.beginSection("RoamGlyph.fogDraw");
        try {
            super.onDraw(canvas);
            if (!enabled) {
                if (map != null) drawForegroundMarkers(canvas, false);
                return;
            }
            // This View owns a persistent HARDWARE layer; drawing the fog
            // directly avoids a second full-screen offscreen saveLayer for
            // every MapLibre animation frame. Destination-out is confined to
            // this overlay layer, not the basemap or the POI views above it.
            canvas.drawColor(Color.argb(FOG_ALPHA, 17, 20, 24));
            if (map != null) drawCachedTiles(canvas);
            if (map != null) drawForegroundMarkers(canvas, true);
        } finally {
            Trace.endSection();
            FogPerformanceDiagnostics metrics = diagnostics;
            if (metrics != null) metrics.recordFogDraw(
                    SystemClock.elapsedRealtimeNanos() - drawStartedNanos);
        }
    }

    private void drawCachedTiles(Canvas canvas) {
        // Keep prior geographic zoom tiles until the complete newly requested
        // visible set is available; never briefly hide an explored road.
        if (displayZoom < 0) displayZoom = requestedZoom;
        if (displayZoom != requestedZoom && !visible.isEmpty()) {
            boolean ready = true;
            for (FogWorldTileScheme.Key key : visible) {
                if (!cache.containsKey(key)) { ready = false; break; }
            }
            if (ready) displayZoom = requestedZoom;
        }
        try {
            org.maplibre.android.geometry.LatLngBounds b =
                    map.getProjection().getVisibleRegion().latLngBounds;
            double north = b.getLatNorth(), south = b.getLatSouth();
            double east = b.getLonEast(), west = b.getLonWest();
            if (!(north > south && east > west && east - west <= 180.0)) return;

            // The geographic culling rectangle is calculated ONCE per frame,
            // not with repeated Mercator logarithms for every cache entry.
            int left = (int) Math.floor(
                    FogWorldTileScheme.longitudeX(west, displayZoom)
                            / FogWorldTileScheme.TILE_PX);
            int right = (int) Math.floor(
                    FogWorldTileScheme.longitudeX(east, displayZoom)
                            / FogWorldTileScheme.TILE_PX);
            int top = (int) Math.floor(
                    FogWorldTileScheme.latitudeY(north, displayZoom)
                            / FogWorldTileScheme.TILE_PX);
            int bottom = (int) Math.floor(
                    FogWorldTileScheme.latitudeY(south, displayZoom)
                            / FogWorldTileScheme.TILE_PX);
            double currentZoom = map.getCameraPosition().zoom;

            // A flat Mercator map remains a plane under bearing and pitch.
            // Four anchor projections define the same projective transform
            // for ALL tiles in a zoom generation. This removes 4*N JNI map
            // projections during every rapid pan animation frame.
            FogWorldTileScheme.Key anchor = prepareViewportProjection(displayZoom);
            if (anchor == null && diagnostics != null) {
                diagnostics.recordProjectionFallback();
            }
            for (Tile tile : cache.values()) {
                if (tile.key.z != displayZoom || tile.mipmaps.length == 0) continue;
                if (tile.key.x < left || tile.key.x > right
                        || tile.key.y < top || tile.key.y > bottom) continue;
                if (anchor == null && !tileTransform(tile.key)) continue;
                int saved = canvas.save();
                if (anchor != null) {
                    canvas.concat(worldToScreen);
                    // Keep values small: global z20 pixel coordinates cannot
                    // be represented with subpixel accuracy in float.
                    canvas.translate((tile.key.x - anchor.x) * (float) FogWorldTileScheme.TILE_PX,
                            (tile.key.y - anchor.y) * (float) FogWorldTileScheme.TILE_PX);
                } else {
                    // A degenerate/horizon-crossing projection falls back to
                    // the earlier independently projected per-tile matrix.
                    canvas.concat(tileMatrix);
                }
                // The expensive H3 vector clip is already baked into the
                // conservative bitmap alpha by FogSilhouetteField. Repeating
                // GPU clipPath for every tile on every pan frame reproduced
                // H3 corner facets and caused substantial RenderThread work.
                // The rasterizer keeps non-visited source pixels fully dark.
                int level = FogMipLevel.forZoomDelta(
                        tile.key.z - currentZoom, tile.effectiveScale(),
                        tile.mipmaps.length);
                canvas.drawBitmap(tile.mipmaps[level], null, TILE_DEST, cutoutPaint);
                canvas.restoreToCount(saved);
            }
        } catch (RuntimeException ignored) {
            // Incomplete map projection: unvisited geography remains dark.
        }
    }

    private FogWorldTileScheme.Key prepareViewportProjection(int zoom) {
        org.maplibre.android.camera.CameraPosition camera = map.getCameraPosition();
        if (camera == null || camera.target == null) return null;
        FogWorldTileScheme.Key anchor = FogWorldTileScheme.projectionAnchor(
                camera.target.getLatitude(), camera.target.getLongitude(), zoom);
        if (anchor == null) return null;
        double west = FogWorldTileScheme.west(anchor);
        double east = FogWorldTileScheme.east(anchor);
        double north = FogWorldTileScheme.north(anchor);
        double south = FogWorldTileScheme.south(anchor);
        double[] lat = {north, north, south, south};
        double[] lng = {west, east, east, west};
        try {
            for (int i = 0; i < 4; i++) {
                PointF point = map.getProjection().toScreenLocation(
                        new org.maplibre.android.geometry.LatLng(lat[i], lng[i]));
                if (!Float.isFinite(point.x) || !Float.isFinite(point.y)) return null;
                target[2 * i] = point.x;
                target[2 * i + 1] = point.y;
            }
            // A projected, pitched ground tile must be a convex quad. If it
            // crosses a wrapped longitude seam or the horizon, do not let a
            // malformed homography distort explored coverage.
            if (!convexQuad(target)) return null;
            return worldToScreen.setPolyToPoly(FRAME_SOURCE, 0, target, 0, 4)
                    ? anchor : null;
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static boolean convexQuad(float[] p) {
        float orientation = 0f;
        for (int i = 0; i < 4; i++) {
            int a = i * 2, b = ((i + 1) & 3) * 2, c = ((i + 2) & 3) * 2;
            float abx = p[b] - p[a], aby = p[b + 1] - p[a + 1];
            float bcx = p[c] - p[b], bcy = p[c + 1] - p[b + 1];
            float cross = abx * bcy - aby * bcx;
            if (!Float.isFinite(cross) || Math.abs(cross) < 0.01f) return false;
            if (orientation != 0f && cross * orientation <= 0f) return false;
            orientation = cross;
        }
        return true;
    }

    private boolean tileTransform(FogWorldTileScheme.Key key) {
        double west = FogWorldTileScheme.west(key);
        double east = FogWorldTileScheme.east(key);
        double north = FogWorldTileScheme.north(key);
        double south = FogWorldTileScheme.south(key);
        double[] lat = {north, north, south, south};
        double[] lng = {west, east, east, west};
        float pixels = FogWorldTileScheme.TILE_PX;
        float[] source = {0f, 0f, pixels, 0f, pixels, pixels, 0f, pixels};
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        try {
            for (int i = 0; i < 4; i++) {
                PointF xy = map.getProjection().toScreenLocation(
                        new org.maplibre.android.geometry.LatLng(lat[i], lng[i]));
                if (!Float.isFinite(xy.x) || !Float.isFinite(xy.y)) return false;
                target[2 * i] = xy.x;
                target[2 * i + 1] = xy.y;
                minX = Math.min(minX, xy.x); maxX = Math.max(maxX, xy.x);
                minY = Math.min(minY, xy.y); maxY = Math.max(maxY, xy.y);
            }
            if (minX > getWidth() + 20 || maxX < -20 ||
                    minY > getHeight() + 20 || maxY < -20) return false;
            return tileMatrix.setPolyToPoly(source, 0, target, 0, 4);
        } catch (RuntimeException failure) {
            return false;
        }
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
