package com.sensareth.roamglyph;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.widget.Toast;

import com.sensareth.roamglyph.map.AutoBenchmarkPlan;
import com.sensareth.roamglyph.map.AutoBenchmarkPath;
import com.sensareth.roamglyph.map.FogOverlayView;
import com.sensareth.roamglyph.map.FogPerformanceDiagnostics;
import com.sensareth.roamglyph.map.FogRendererChoice;

import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Opt-in on-device benchmark. It moves only the MapLibre CAMERA, not the GPS.
 * Temporary modes and camera anchor exist in process memory only; persistent
 * renderer choices, history, H3, Room and tracking are never written here.
 * Losing the process aborts the test and safely restores stored preferences.
 */
public final class AutoBenchmarkRunner {
    private static final long WARMUP_MS = 6000L;
    private static final double METERS_PER_LAT_DEGREE = 111_320.0;
    private static Session session;
    private static Finished finished;

    private static final class Session {
        final List<AutoBenchmarkPlan.Mode> modes;
        final CameraPosition original;
        final AutoBenchmarkPath.Scenario scenario;
        final String runId = UUID.randomUUID().toString().substring(0, 8);
        final ArrayList<String> reports = new ArrayList<>();
        final ArrayList<String> summaryRows = new ArrayList<>();
        int step;

        Session(boolean allRenderers, AutoBenchmarkPath.Scenario scenario,
                CameraPosition original) {
            this.modes = AutoBenchmarkPlan.modes(allRenderers);
            this.scenario = scenario;
            this.original = original;
        }

        AutoBenchmarkPlan.Mode mode() { return modes.get(step); }
    }

    private static final class Finished {
        final CameraPosition camera;
        final String report; // null when stopped
        Finished(CameraPosition camera, String report) {
            this.camera = camera;
            this.report = report;
        }
    }

    private final Activity activity;
    private final MapView view;
    private final FogOverlayView fog;
    private final FogPerformanceDiagnostics metrics;
    private final Runnable refreshCorridors;
    private final ArrayList<Runnable> scheduled = new ArrayList<>();
    private MapLibreMap map;
    private boolean resumed;
    private boolean stageStarted;
    private boolean completionShowing;

    public AutoBenchmarkRunner(Activity activity, MapView view, FogOverlayView fog,
                               FogPerformanceDiagnostics metrics, Runnable refreshCorridors) {
        this.activity = activity;
        this.view = view;
        this.fog = fog;
        this.metrics = metrics;
        this.refreshCorridors = refreshCorridors;
    }

    public static boolean isRunning() { return session != null; }

    public static int rendererChoice(VisitedStore store) {
        return session == null
                ? FogRendererChoice.current(store.isRendererV2Enabled(),
                        store.isRendererV3Enabled(), store.isRendererV4Enabled())
                : session.mode().renderer;
    }

    public static boolean textureView(VisitedStore store) {
        return session == null ? store.isTextureMapTestEnabled()
                : session.mode().textureView;
    }

    public static boolean mapFrameSync(VisitedStore store) {
        return session == null ? store.isFogMapFrameSyncEnabled()
                : session.mode().mapFrameSync;
    }

    public static boolean fogEnabled(VisitedStore store) {
        return session != null || store.isFogEnabled();
    }

    /** During recreation keep the same geography without recording GPS data. */
    public static CameraPosition initialCamera() {
        if (session != null) return camera(session.original,
                session.original.target.getLongitude());
        return finished == null ? null : finished.camera;
    }

    private static CameraPosition camera(CameraPosition start, double longitude) {
        return new CameraPosition.Builder()
                .target(new LatLng(start.target.getLatitude(), longitude))
                .zoom(17.5)
                .bearing(start.bearing)
                .tilt(start.tilt)
                .build();
    }

    /** Convert a reproducible local-metre route to a camera target only.
     * Neither the target nor GPS coordinates enter the benchmark export.
     */
    private static CameraPosition cameraAtOffset(CameraPosition original,
                                                  AutoBenchmarkPath.Offset delta) {
        double latitude = Math.max(-84.0, Math.min(84.0,
                original.target.getLatitude() + delta.northMeters / METERS_PER_LAT_DEGREE));
        double scale = Math.max(0.15,
                Math.cos(Math.toRadians(original.target.getLatitude())));
        double longitude = original.target.getLongitude()
                + delta.eastMeters / (METERS_PER_LAT_DEGREE * scale);
        longitude = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
        return new CameraPosition.Builder().target(new LatLng(latitude, longitude))
                .zoom(17.5).bearing(original.bearing).tilt(original.tilt).build();
    }

    public void setMap(MapLibreMap value) { map = value; }

    public void onStyleReady() {
        view.post(() -> {
            if (session != null) startStep();
            else showFinished();
        });
    }

    public void onResume() {
        resumed = true;
        view.post(() -> {
            if (session != null) startStep();
            else showFinished();
        });
    }

    public void onPause() {
        resumed = false;
        clearTasks();
        stageStarted = false;
        // On resume the interrupted mode is restarted from a fresh reset.
    }

    private void post(Runnable work, long delay) {
        scheduled.add(work);
        view.postDelayed(work, delay);
    }

    private void clearTasks() {
        for (Runnable r : scheduled) view.removeCallbacks(r);
        scheduled.clear();
    }

    public void choosePlan(boolean tracking) {
        if (session != null) { showStopDialog(); return; }
        if (metrics == null || map == null || map.getStyle() == null
                || map.getCameraPosition() == null) {
            Toast.makeText(activity, "Map diagnostics not ready", Toast.LENGTH_SHORT).show();
            return;
        }
        if (tracking) {
            new AlertDialog.Builder(activity)
                    .setTitle("Stop Exploration first")
                    .setMessage("The automatic camera test requires paused tracking.")
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle("Automatic camera benchmark")
                // Android AlertDialog cannot combine setMessage() and setItems().
                // Put the short explanation in each selectable plan instead.
                .setItems(new String[]{
                        "Quick: V4, 4 modes · warmed pan",
                        "Stress: V4, 4 modes · new territory",
                        "Full: all 16 modes · warmed pan"},
                        (dialog, selected) -> {
                            CameraPosition original = map.getCameraPosition();
                            if (original == null || original.target == null) return;
                            // Stress and Full are intentionally separate: the 16-mode
                            // matrix does not pretend to stress new territory.
                            session = new Session(selected == 2,
                                    selected == 1
                                            ? AutoBenchmarkPath.Scenario.NEW_TERRITORY
                                            : AutoBenchmarkPath.Scenario.WARM_PAN,
                                    original);
                            finished = null;
                            if (session.mode().textureView
                                    != (view.getRenderView() instanceof android.view.TextureView))
                                activity.recreate();
                            else startStep();
                        })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    public void showStopDialog() {
        new AlertDialog.Builder(activity)
                .setTitle("Automatic benchmark")
                .setMessage("Stop the test and restore your normal map settings?")
                .setPositiveButton("Stop", (dialog, which) -> stop())
                .setNegativeButton("Continue", null).show();
    }

    private void startStep() {
        Session run = session;
        if (run == null || stageStarted || !resumed || map == null
                || map.getStyle() == null || fog.getWidth() < 1) return;
        if (run.mode().textureView
                != (view.getRenderView() instanceof android.view.TextureView)) {
            activity.recreate();
            return;
        }
        stageStarted = true;
        clearTasks();
        // Ignore accidental touch gestures; camera motion must be reproducible.
        map.getUiSettings().setAllGesturesEnabled(false);
        AutoBenchmarkPlan.Mode mode = run.mode();
        fog.setRendererV4(false);
        fog.setRendererV3(false);
        fog.setRendererV2(false);
        if (mode.renderer == 1) fog.setRendererV2(true);
        if (mode.renderer == 2) fog.setRendererV3(true);
        if (mode.renderer == 3) fog.setRendererV4(true);
        fog.setMapFrameSyncEnabled(mode.mapFrameSync);
        fog.setFogEnabled(true);
        if (mode.renderer >= 2) refreshCorridors.run();
        if (metrics != null) {
            metrics.setFogEnabled(true);
            metrics.reset();
        }
        CameraPosition centered = camera(run.original, run.original.target.getLongitude());
        map.setCameraPosition(centered);
        fog.requestTilesNow();
        Toast.makeText(activity,
                "Benchmark " + (run.step + 1) + "/" + run.modes.size()
                        + " · " + (run.scenario == AutoBenchmarkPath.Scenario.NEW_TERRITORY
                                ? "Stress" : "Warm") + ": " + mode.label(),
                Toast.LENGTH_SHORT).show();
        // Warm-up is outside the measurement; each mode gets the same sweep.
        post(() -> {
            if (session != run || !stageStarted || !resumed) return;
            map.setCameraPosition(centered);
            if (metrics != null) metrics.reset();
            sweep(run, 0);
        }, WARMUP_MS);
    }

    private void sweep(Session run, int step) {
        if (session != run || !stageStarted || !resumed || map == null) return;
        if (step == AutoBenchmarkPath.steps(run.scenario)) {
            saveStep(run);
            return;
        }
        // A new-territory step moves to a different geographic tile region;
        // warm pan intentionally revisits two nearby positions.
        AutoBenchmarkPath.Offset offset =
                AutoBenchmarkPath.waypoint(run.scenario, step);
        map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                        cameraAtOffset(run.original, offset)), 700);
        post(() -> sweep(run, step + 1), 1000L);
    }

    private void saveStep(Session run) {
        if (session != run || !stageStarted) return;
        AutoBenchmarkPlan.Mode mode = run.mode();
        FogPerformanceDiagnostics.BenchmarkSummary summary = metrics == null
                ? null : metrics.benchmarkSummary();
        String result = metrics == null
                ? "Diagnostics unavailable" : metrics.report();
        String label = mode.label();
        run.reports.add("\n=== " + (run.step + 1) + "/" + run.modes.size()
                + ": " + label + " ===\n" + result);
        if (summary != null) {
            double longFrameRate = summary.windowSamples == 0
                    ? Double.NaN
                    : 100.0 * summary.windowAbove16 / summary.windowSamples;
            String workload = run.scenario == AutoBenchmarkPath.Scenario.NEW_TERRITORY
                    && summary.tileBuilds < 100 ? "LOW TILE WORKLOAD" : "measured";
            run.summaryRows.add(String.format(Locale.US,
                    "%s | %d | %.2f | %.2f | %.1f%% | %d | %d/%d/%d | %.1f | %s",
                    label, summary.windowSamples, summary.windowP95Ms,
                    summary.windowP99Ms, longFrameRate,
                    summary.missingLookups, summary.tileSuccess,
                    summary.tileCancelled, summary.tileNoResult,
                    summary.queueP95Ms, workload));
        }
        stageStarted = false;
        clearTasks();
        run.step++;
        if (run.step == run.modes.size()) {
            StringBuilder report = new StringBuilder();
            report.append("RoamGlyph automatic benchmark\nDevice: ")
                    .append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
                    .append("; Android API ").append(Build.VERSION.SDK_INT).append("\n");
            report.append("Run: ").append(run.runId)
                    .append("; script version: ").append(AutoBenchmarkPath.SCRIPT_VERSION)
                    .append("; app: ").append(readVersionName()).append("\n");
            report.append("Scenario: ").append(run.scenario)
                    .append("; ")
                    .append(AutoBenchmarkPath.steps(run.scenario))
                    .append(" scripted camera pans at z17.5, 6s unmeasured warm-up per mode.\n")
                    .append("Camera positions are derived in memory. No GPS tracks, ")
                    .append("coordinates or visited history in this report.\n")
                    .append("Window FrameMetrics are NOT the map GPU FPS. ")
                    .append("Stress is not proven unless substantial new tiles build.\n");
            report.append("\n=== COMPARISON SUMMARY ===\n");
            report.append("Mode | window n | p95 ms | p99 ms | >16.7ms | ")
                    .append("miss lookups | built/cancelled/no-result | queue p95 ms | load\n");
            for (String row : run.summaryRows) report.append(row).append('\n');
            report.append("\n=== RAW DIAGNOSTICS ===\n");
            for (String part : run.reports) report.append(part);
            session = null;
            finished = new Finished(run.original, report.toString());
            // MapLibre persists UiSettings gesture flags through saved state.
            // Undo the transient benchmark restriction BEFORE recreate().
            if (map != null) map.getUiSettings().setAllGesturesEnabled(true);
            activity.recreate(); // Return to the user's real saved preferences.
        } else if (mode.textureView != run.mode().textureView) {
            activity.recreate();
        } else {
            startStep();
        }
    }

    private String readVersionName() {
        try {
            return activity.getPackageManager().getPackageInfo(
                    activity.getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            return "unavailable";
        }
    }

    private void stop() {
        Session run = session;
        if (run == null) return;
        clearTasks();
        stageStarted = false;
        session = null;
        finished = new Finished(run.original, null);
        if (map != null) map.getUiSettings().setAllGesturesEnabled(true);
        activity.recreate();
    }

    private void showFinished() {
        Finished completion = finished;
        if (completion == null || !resumed || map == null || map.getStyle() == null)
            return;
        if (completion.report == null) {
            finished = null;
            Toast.makeText(activity, "Benchmark stopped; settings restored",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (completionShowing) return;
        completionShowing = true;
        // Keep the report across Activity recreation until the user chooses
        // Share or Close. Rotation must never destroy the only full report.
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Benchmark completed")
                .setMessage("All modes checked. Your original renderer and compositor "
                        + "settings are restored. Share a single privacy-safe report.")
                .setPositiveButton("Share report", (d, which) -> {
                    if (finished == completion) finished = null;
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_TEXT, completion.report);
                    activity.startActivity(Intent.createChooser(send, "Share benchmark"));
                })
                .setNegativeButton("Close", (d, which) -> {
                    if (finished == completion) finished = null;
                })
                .setCancelable(false)
                .create();
        dialog.setOnDismissListener(d -> completionShowing = false);
        dialog.show();
    }
}
