package com.sensareth.roamglyph.map;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.FrameMetrics;
import android.view.Window;

/**
 * Debug-only, local frame timing: no networking or telemetry. A background
 * HandlerThread receives Window frame metrics for fog-on/off comparisons.
 *
 * An on-device Pixel gesture test is required: a green AOSP startup smoke
 * test cannot prove that the old camera panning micro-stutters are gone.
 */
public final class FogFrameMetrics {
    private static final String TAG = "RoamGlyphFrames";
    private static final int REPORT_EVERY = 240;

    private final Window window;
    private final HandlerThread thread = new HandlerThread("roamglyph-frame-metrics");
    private Handler handler;
    private boolean started;
    private volatile boolean fogEnabled;
    private int total;
    private int slowerThan16ms;
    private int slowerThan33ms;
    private double summedMs;
    private double worstMs;

    private final Window.OnFrameMetricsAvailableListener listener = (ignored, frame, dropped) -> {
        long nanos = frame.getMetric(FrameMetrics.TOTAL_DURATION);
        if (nanos <= 0) return;
        double ms = nanos / 1_000_000.0;
        total++;
        summedMs += ms;
        worstMs = Math.max(worstMs, ms);
        if (ms > 16.67) slowerThan16ms++;
        if (ms > 33.33) slowerThan33ms++;
        if (total >= REPORT_EVERY) {
            Log.d(TAG, String.format(java.util.Locale.US,
                    "fog=%s frames=%d avg=%.2fms worst=%.2fms >16.7ms=%d >33.3ms=%d",
                    fogEnabled ? "on" : "off", total,
                    summedMs / total, worstMs, slowerThan16ms, slowerThan33ms));
            total = 0;
            slowerThan16ms = 0;
            slowerThan33ms = 0;
            summedMs = 0;
            worstMs = 0;
        }
    };

    public FogFrameMetrics(Window window, boolean fogEnabled) {
        this.window = window;
        this.fogEnabled = fogEnabled;
    }

    public void start() {
        if (started) return;
        started = true;
        thread.start();
        handler = new Handler(thread.getLooper());
        window.addOnFrameMetricsAvailableListener(listener, handler);
    }

    public void setFogEnabled(boolean enabled) {
        fogEnabled = enabled;
    }

    public void stop() {
        if (!started) return;
        window.removeOnFrameMetricsAvailableListener(listener);
        thread.quitSafely();
        started = false;
    }
}
