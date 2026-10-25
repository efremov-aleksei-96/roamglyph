package com.sensareth.roamglyph.map;

import org.junit.Test;
import static org.junit.Assert.assertTrue;

public class FogPerformanceDiagnosticsTest {
    @Test public void onOffPhasesKeepIndependentFrameSamples() {
        FogPerformanceDiagnostics diagnostics = new FogPerformanceDiagnostics(true);
        diagnostics.recordWindowFrame(10_000_000L, 1);
        diagnostics.recordFogDraw(2_000_000L);
        diagnostics.setFogEnabled(false);
        diagnostics.recordWindowFrame(30_000_000L, 0);
        String report = diagnostics.report();
        assertTrue(report.contains("[FOG ON]\nWindow frame TOTAL_DURATION: n=1; avg=10.00ms"));
        assertTrue(report.contains("[FOG OFF]\nWindow frame TOTAL_DURATION: n=1; avg=30.00ms"));
        assertTrue(report.contains("Window listener dropped callbacks: 1"));
        assertTrue(report.contains("Window listener dropped callbacks: 0"));
    }

    @Test public void percentilesSortSamplesAndCountLongFrames() {
        FogPerformanceDiagnostics diagnostics = new FogPerformanceDiagnostics(true);
        for (int i = 20; i >= 1; i--) {
            diagnostics.recordWindowFrame(i * 1_000_000L, 0);
        }
        String report = diagnostics.report();
        assertTrue(report.contains("p50=10.00ms; p95=19.00ms; p99=20.00ms"));
        assertTrue(report.contains(">8.3ms=12; >16.7ms=4; >33.3ms=0"));
    }

    @Test public void capturesBuildAndCachePressureThenReset() {
        FogPerformanceDiagnostics diagnostics = new FogPerformanceDiagnostics(true);
        diagnostics.recordDemand(2_000_000L, 3, 2, 1, 6, 2, 7, 16L * 1024 * 1024);
        diagnostics.recordTileBuild(10_000_000L, 55_000_000L, true);
        diagnostics.recordTileBuild(20_000_000L, 80_000_000L, false);
        diagnostics.recordProjectionFallback();
        String report = diagnostics.report();
        assertTrue(report.contains("Tile demand lookups: cached=3; missing=2; queued=1"));
        assertTrue(report.contains("Build outcomes: success=1; aborted/failed=1"));
        assertTrue(report.contains("Last cache: 7 tiles / 16 MiB; visible=6; pending=2"));
        assertTrue(report.contains("Fallback per-tile projections: 1"));
        diagnostics.reset();
        String reset = diagnostics.report();
        assertTrue(reset.contains("Window frame TOTAL_DURATION: none"));
        assertTrue(reset.contains("Build outcomes: success=0; aborted/failed=0"));
    }

    @Test public void onlyInMemoryAggregateNoGeoData() {
        FogPerformanceDiagnostics diagnostics = new FogPerformanceDiagnostics(false);
        String report = diagnostics.report();
        assertTrue(report.contains("No location coordinates"));
        assertTrue(report.contains("may exclude MapLibre GL SurfaceView"));
    }
    @Test public void mapFrameAndFogDrawTimingsAreSeparateAndPrivacyPreserving() {
        FogPerformanceDiagnostics diagnostics = new FogPerformanceDiagnostics(true);
        diagnostics.recordRenderedMapFrame(12_000_000L);
        diagnostics.recordMapFrameRedraw();
        diagnostics.recordMapFrameToFogDraw(4_000_000L);
        diagnostics.recordRenderedMapFrame(-1L);
        diagnostics.recordMapFrameFallback();
        String report = diagnostics.report();
        assertTrue(report.contains("MapLibre rendered-frame callbacks: 2; redraws=1; fallbacks=1"));
        assertTrue(report.contains(
                "Camera event -> MapLibre frame callback: n=1; avg=12.00ms"));
        assertTrue(report.contains(
                "MapLibre frame callback -> Fog.onDraw CPU start: n=1; avg=4.00ms"));
        diagnostics.reset();
        String reset = diagnostics.report();
        assertTrue(reset.contains("MapLibre rendered-frame callbacks: 0; redraws=0; fallbacks=0"));
        assertTrue(reset.contains("Camera event -> MapLibre frame callback: none"));
    }

    @Test public void benchmarkSnapshotSeparatesCancelledWorkAndFailure() {
        FogPerformanceDiagnostics metrics = new FogPerformanceDiagnostics(true);
        metrics.recordWindowFrame(10_000_000L, 0);
        metrics.recordWindowFrame(20_000_000L, 0);
        metrics.recordDemand(1_000_000L, 7, 4, 3, 4, 3, 8, 8_000_000);
        metrics.recordTileBuild(30_000_000L, 70_000_000L,
                FogPerformanceDiagnostics.BUILD_SUCCESS);
        metrics.recordTileBuild(20_000_000L, 10_000_000L,
                FogPerformanceDiagnostics.BUILD_CANCELLED);
        metrics.recordTileBuild(5_000_000L, 10_000_000L,
                FogPerformanceDiagnostics.BUILD_NO_RESULT);
        metrics.recordQueueMaintenance(9, 15);
        metrics.recordQueueMaintenance(3, 5);
        FogPerformanceDiagnostics.BenchmarkSummary summary = metrics.benchmarkSummary();
        assertTrue(summary.windowSamples == 2);
        assertTrue(summary.windowAbove16 == 1);
        assertTrue(Math.abs(summary.windowP95Ms - 20.0) < 0.001);
        assertTrue(summary.missingLookups == 4);
        assertTrue(summary.tileBuilds == 3);
        assertTrue(summary.tileSuccess == 1);
        assertTrue(summary.successfulQueueSamples == 1);
        assertTrue(Math.abs(summary.successfulQueueP95Ms - 30.0) < .001);
        assertTrue(Math.abs(summary.successfulQueueP99Ms - 30.0) < .001);
        assertTrue(Math.abs(summary.successfulBuildP95Ms - 70.0) < .001);
        assertTrue(summary.tileCancelled == 1);
        assertTrue(summary.tileNoResult == 1);
        assertTrue(summary.peakPending == 3);
        assertTrue(summary.prunedQueuedJobs == 12);
        assertTrue(summary.peakQueueDepth == 15);
        assertTrue(metrics.report().contains("Build detail: cancelled=1; no-result=1"));
        assertTrue(metrics.report().contains(
                "Successful tile queue delay (worker): n=1; avg=30.00ms"));
        assertTrue(metrics.report().contains(
                "Successful tile build time (worker): n=1; avg=70.00ms"));
        assertTrue(metrics.report().contains("Worker queue hygiene: pruned-obsolete=12; peak-queued=15"));
    }

    @Test public void mapFrameMetricsSeparateFogOnOffPhases() {
        FogPerformanceDiagnostics diagnostics = new FogPerformanceDiagnostics(true);
        diagnostics.recordRenderedMapFrame(5_000_000L);
        diagnostics.setFogEnabled(false);
        diagnostics.recordRenderedMapFrame(25_000_000L);
        String report = diagnostics.report();
        assertTrue(report.contains("[FOG ON]\nWindow frame TOTAL_DURATION: none\nMapLibre rendered-frame callbacks: 1"));
        assertTrue(report.contains("[FOG OFF]\nWindow frame TOTAL_DURATION: none\nMapLibre rendered-frame callbacks: 1"));
    }

}
