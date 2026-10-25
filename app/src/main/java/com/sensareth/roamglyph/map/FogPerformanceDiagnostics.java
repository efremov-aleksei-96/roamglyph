package com.sensareth.roamglyph.map;

import java.util.Arrays;
import java.util.Locale;

/**
 * Local, privacy-preserving performance samples for the development channel.
 * No GPS coordinates, route names, persistence, telemetry or networking.
 *
 * Window frame timings measure this Activity's Android UI window, not all
 * MapLibre OpenGL SurfaceView GPU work. They cannot by themselves prove FPS.
 * All per-event methods do bounded O(1) work; sorting happens ONLY on demand.
 */
public final class FogPerformanceDiagnostics {
    private static final int MAX_SAMPLES = 8192;
    public static final int BUILD_SUCCESS = 0;
    public static final int BUILD_CANCELLED = 1;
    public static final int BUILD_NO_RESULT = 2;

    /** Immutable in-memory summary; counts have their own sample denominators. */
    public static final class BenchmarkSummary {
        public final long windowSamples;
        public final double windowAverageMs, windowP95Ms, windowP99Ms;
        public final long windowAbove16;
        public final double fogDrawP95Ms, queueP95Ms;
        public final double successfulQueueP95Ms, successfulQueueP99Ms,
                successfulBuildP95Ms;
        public final long successfulQueueSamples;
        public final long tileBuilds, tileSuccess, tileCancelled, tileNoResult;
        public final long missingLookups;
        public final int peakPending, peakQueueDepth;
        public final long prunedQueuedJobs, deferredPrefetchRequests;
        public final long mapFrameRedraws, mapFrameFallbacks;

        private BenchmarkSummary(Phase p) {
            windowSamples = p.uiFrame.totalCount;
            windowAverageMs = p.uiFrame.totalCount == 0 ? Double.NaN
                    : p.uiFrame.totalMillis / p.uiFrame.totalCount;
            windowP95Ms = p.uiFrame.percentileValue(95);
            windowP99Ms = p.uiFrame.percentileValue(99);
            windowAbove16 = p.uiFrame.above16;
            fogDrawP95Ms = p.fogDraw.percentileValue(95);
            queueP95Ms = p.queueWait.percentileValue(95);
            successfulQueueP95Ms = p.successQueueWait.percentileValue(95);
            successfulQueueP99Ms = p.successQueueWait.percentileValue(99);
            successfulBuildP95Ms = p.successTileBuild.percentileValue(95);
            successfulQueueSamples = p.successQueueWait.totalCount;
            tileBuilds = p.tileBuild.totalCount;
            tileSuccess = p.builtTiles;
            tileCancelled = p.cancelledTiles;
            tileNoResult = p.noResultTiles;
            missingLookups = p.requestedMissingTiles;
            peakPending = p.peakPending;
            peakQueueDepth = p.peakQueueDepth;
            prunedQueuedJobs = p.prunedQueuedJobs;
            deferredPrefetchRequests = p.deferredPrefetchRequests;
            mapFrameRedraws = p.mapFrameRedraws;
            mapFrameFallbacks = p.mapFrameFallbacks;
        }
    }

    private boolean fogEnabled;
    private long startedAtMillis;
    private Phase on = new Phase();
    private Phase off = new Phase();

    private static final class Samples {
        final double[] values = new double[MAX_SAMPLES];
        int count;
        long totalCount;
        double totalMillis;
        double worstMillis;
        long above8, above16, above33;
        long index;

        void add(long ns) {
            if (ns < 0) return;
            double ms = ns / 1_000_000.0;
            if (!Double.isFinite(ms)) return;
            values[(int) (index++ % MAX_SAMPLES)] = ms;
            if (count < MAX_SAMPLES) count++;
            totalCount++;
            totalMillis += ms;
            worstMillis = Math.max(worstMillis, ms);
            if (ms > 8.33) above8++;
            if (ms > 16.67) above16++;
            if (ms > 33.33) above33++;
        }

        double percentileValue(double p) {
            if (count == 0) return Double.NaN;
            double[] sample = Arrays.copyOf(values, count);
            Arrays.sort(sample);
            return percentile(sample, p);
        }

        String report() {
            if (totalCount == 0) return "none";
            double[] sample = Arrays.copyOf(values, count);
            Arrays.sort(sample);
            return String.format(Locale.US,
                    "n=%d; avg=%.2fms; p50=%.2fms; p95=%.2fms; p99=%.2fms; "
                    + "max=%.2fms; >8.3ms=%d; >16.7ms=%d; >33.3ms=%d",
                    totalCount, totalMillis / totalCount, percentile(sample, 50),
                    percentile(sample, 95), percentile(sample, 99),
                    worstMillis, above8, above16, above33);
        }

        private static double percentile(double[] sorted, double p) {
            if (sorted.length == 0) return 0;
            int at = Math.max(0, (int) Math.ceil(p / 100.0 * sorted.length) - 1);
            return sorted[Math.min(at, sorted.length - 1)];
        }
    }

    private static final class Phase {
        final Samples uiFrame = new Samples();
        final Samples fogDraw = new Samples();
        final Samples demand = new Samples();
        final Samples tileBuild = new Samples();
        final Samples queueWait = new Samples();
        final Samples successTileBuild = new Samples();
        final Samples successQueueWait = new Samples();
        final Samples cameraToMapFrame = new Samples();
        final Samples mapFrameToFogDraw = new Samples();
        long mapFrameCallbacks;
        long mapFrameRedraws;
        long mapFrameFallbacks;
        long droppedFrameCallbacks;
        long requestedPresentTiles;
        long requestedMissingTiles;
        long queuedTiles;
        long builtTiles;
        long failedTiles;
        long cancelledTiles;
        long noResultTiles;
        long prunedQueuedJobs;
        long deferredPrefetchRequests;
        int peakQueueDepth;
        int peakPending;
        int visibleTiles;
        int residentTiles;
        int pendingTiles;
        long residentBytes;
        long projectionFallbacks;

        String report(String heading) {
            StringBuilder s = new StringBuilder("\n[").append(heading).append("]\n");
            s.append("Window frame TOTAL_DURATION: ").append(uiFrame.report()).append('\n');
            s.append("MapLibre rendered-frame callbacks: ")
                    .append(mapFrameCallbacks).append("; redraws=")
                    .append(mapFrameRedraws).append("; fallbacks=")
                    .append(mapFrameFallbacks).append('\n');
            s.append("Camera event -> MapLibre frame callback: ")
                    .append(cameraToMapFrame.report()).append('\n');
            s.append("MapLibre frame callback -> Fog.onDraw CPU start: ")
                    .append(mapFrameToFogDraw.report()).append('\n');
            s.append("Window listener dropped callbacks: ")
                    .append(droppedFrameCallbacks).append('\n');
            s.append("Fog View.onDraw CPU recording: ").append(fogDraw.report())
                    .append('\n');
            s.append("Fog tile-demand UI handling: ").append(demand.report())
                    .append('\n');
            s.append("Fog tile build (background worker): ").append(tileBuild.report())
                    .append('\n');
            s.append("Tile queue delay (background worker): ").append(queueWait.report())
                    .append('\n');
            s.append("Tile demand lookups: cached=").append(requestedPresentTiles)
                    .append("; missing=").append(requestedMissingTiles)
                    .append("; queued=").append(queuedTiles).append('\n');
            s.append("Successful tile queue delay (worker): ")
                    .append(successQueueWait.report()).append('\n');
            s.append("Successful tile build time (worker): ")
                    .append(successTileBuild.report()).append('\n');
            s.append("Build outcomes: success=").append(builtTiles)
                    .append("; aborted/failed=").append(failedTiles).append('\n');
            s.append("Build detail: cancelled=").append(cancelledTiles)
                    .append("; no-result=").append(noResultTiles).append('\n');
            s.append("Peak pending tiles: ").append(peakPending).append('\n');
            s.append("Worker queue hygiene: pruned-obsolete=").append(prunedQueuedJobs)
                    .append("; peak-queued=").append(peakQueueDepth)
                    .append("; deferred-prefetch=").append(deferredPrefetchRequests)
                    .append('\n');
            s.append("Last cache: ").append(residentTiles)
                    .append(" tiles / ").append(residentBytes / (1024 * 1024))
                    .append(" MiB; visible=").append(visibleTiles)
                    .append("; pending=").append(pendingTiles).append('\n');
            s.append("Fallback per-tile projections: ")
                    .append(projectionFallbacks).append('\n');
            return s.toString();
        }
    }

    public FogPerformanceDiagnostics(boolean initialFogEnabled) {
        fogEnabled = initialFogEnabled;
        startedAtMillis = System.currentTimeMillis();
    }

    public synchronized void setFogEnabled(boolean enabled) {
        fogEnabled = enabled;
    }

    public synchronized void reset() {
        on = new Phase();
        off = new Phase();
        startedAtMillis = System.currentTimeMillis();
    }

    private Phase current() {
        return fogEnabled ? on : off;
    }

    /** Called from Window.OnFrameMetricsAvailableListener's handler thread. */
    public synchronized void recordWindowFrame(long durationNs, int callbackDrops) {
        current().uiFrame.add(durationNs);
        current().droppedFrameCallbacks += Math.max(0, callbackDrops);
    }

    /** Java/View draw recording, not GPU rasterization or MapLibre rendering. */
    public synchronized void recordFogDraw(long durationNs) {
        current().fogDraw.add(durationNs);
    }

    /** Called at most once per real tile-demand update, not per cache entry. */
    public synchronized void recordDemand(long durationNs, int hit, int miss,
                                          int queued, int visible, int pending,
                                          int cacheCount, long cacheBytes) {
        Phase p = current();
        p.demand.add(durationNs);
        p.requestedPresentTiles += Math.max(0, hit);
        p.requestedMissingTiles += Math.max(0, miss);
        p.queuedTiles += Math.max(0, queued);
        p.visibleTiles = Math.max(0, visible);
        p.pendingTiles = Math.max(0, pending);
        p.peakPending = Math.max(p.peakPending, p.pendingTiles);
        p.residentTiles = Math.max(0, cacheCount);
        p.residentBytes = Math.max(0, cacheBytes);
    }

    /**
     * Queue pruning happens on the UI camera-demand path. It is NOT a
     * finished build and must not be counted as a worker cancellation.
     */
    public synchronized void recordQueueMaintenance(int pruned, int queuedDepth) {
        Phase p = current();
        p.prunedQueuedJobs += Math.max(0, pruned);
        p.peakQueueDepth = Math.max(p.peakQueueDepth, Math.max(0, queuedDepth));
    }

    /** Counts halo requests postponed until required screen tiles are ready. */
    public synchronized void recordPrefetchDeferral(int deferred) {
        current().deferredPrefetchRequests += Math.max(0, deferred);
    }

    /** Compatibility overload for existing diagnostic callers and tests. */
    public synchronized void recordTileBuild(long queueDelayNs,
                                             long processingNs, boolean success) {
        recordTileBuild(queueDelayNs, processingNs,
                success ? BUILD_SUCCESS : BUILD_NO_RESULT);
    }

    /** Distinguish obsolete work from builds which produced no tile. */
    public synchronized void recordTileBuild(long queueDelayNs,
                                             long processingNs, int outcome) {
        Phase p = current();
        p.queueWait.add(queueDelayNs);
        p.tileBuild.add(processingNs);
        if (outcome == BUILD_SUCCESS) {
            p.builtTiles++;
            p.successQueueWait.add(queueDelayNs);
            p.successTileBuild.add(processingNs);
        } else {
            p.failedTiles++;
            if (outcome == BUILD_CANCELLED) p.cancelledTiles++;
            else p.noResultTiles++;
        }
    }

    /** Snapshot is called at mode end, off the hot per-frame rendering path. */
    public synchronized BenchmarkSummary benchmarkSummary() {
        return new BenchmarkSummary(current());
    }

    /** Callback timestamps do not represent physical display presentation. */
    public synchronized void recordRenderedMapFrame(long cameraToFrameNs) {
        Phase p = current();
        p.mapFrameCallbacks++;
        if (cameraToFrameNs >= 0L) p.cameraToMapFrame.add(cameraToFrameNs);
    }

    public synchronized void recordMapFrameRedraw() {
        current().mapFrameRedraws++;
    }

    public synchronized void recordMapFrameFallback() {
        current().mapFrameFallbacks++;
    }

    public synchronized void recordMapFrameToFogDraw(long callbackToDrawNs) {
        current().mapFrameToFogDraw.add(callbackToDrawNs);
    }

    public synchronized void recordProjectionFallback() {
        current().projectionFallbacks++;
    }

    public synchronized String report() {
        StringBuilder s = new StringBuilder();
        s.append("RoamGlyph local performance diagnostics\n");
        s.append("Measurement start (unix ms): ").append(startedAtMillis).append('\n');
        s.append("Elapsed: ").append(Math.max(0L,
                (System.currentTimeMillis() - startedAtMillis) / 1000L))
                .append(" seconds\n");
        s.append("Current fog: ").append(fogEnabled ? "ON" : "OFF").append('\n');
        s.append("Percentiles use latest ").append(MAX_SAMPLES)
                .append(" samples; averages and counts use all samples since reset.\n");
        s.append(on.report("FOG ON"));
        s.append(off.report("FOG OFF"));
        s.append("\nIMPORTANT: Android Window FrameMetrics may exclude MapLibre "
                + "GL SurfaceView composition/GPU. Window and Java timings do NOT "
                + "establish actual map FPS or render-thread/GPU stalls. "
                + "Map-frame callback durations are NOT presentation latency. "
                + "Compare fog ON/OFF and use Perfetto for root cause.\n");
        s.append("No location coordinates, trips, saved history or personal "
                + "identifiers are included.\n");
        return s.toString();
    }
}
