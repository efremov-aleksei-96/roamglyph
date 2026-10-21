package com.sensareth.roamglyph.map;

/**
 * Conservative interpretation of MapLibre's paired render-start/render-finish
 * callbacks. Neither event proves physical frame presentation or GPU timing.
 *
 * If the camera moved during rendering, the finished frame can be stale:
 * refuse to acknowledge it and keep the fallback redraw armed.
 */
public final class FogMapFrameGate {
    private FogMapFrameGate() { }

    public static boolean isCurrentCameraFrame(long lastCameraMotionNs,
                                                long handledCameraMotionNs,
                                                long frameStartedNs,
                                                long frameFinishedNs) {
        return lastCameraMotionNs > handledCameraMotionNs
                && lastCameraMotionNs > 0L
                && frameStartedNs >= lastCameraMotionNs
                && frameFinishedNs >= frameStartedNs;
    }
}
