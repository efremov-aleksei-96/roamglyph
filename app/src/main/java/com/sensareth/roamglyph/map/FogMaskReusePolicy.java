package com.sensareth.roamglyph.map;

/**
 * A raster mask may be translated, rotated, or reduced in scale, but
 * significantly magnifying a screen-quantized visited mask can create false
 * explored pixels outside the true H3 footprint. Fail dark until a new
 * exact-resolution mask is built at the requested zoom.
 */
public final class FogMaskReusePolicy {
    public static final double MAX_ZOOM_IN_DELTA = 0.05;
    public static final double MAX_TILT_DELTA = 1.0;

    private FogMaskReusePolicy() {
    }

    public static boolean mayReuse(
            double snapshotZoom, double currentZoom,
            double snapshotTilt, double currentTilt
    ) {
        return Double.isFinite(snapshotZoom) && Double.isFinite(currentZoom)
                && Double.isFinite(snapshotTilt) && Double.isFinite(currentTilt)
                && currentZoom <= snapshotZoom + MAX_ZOOM_IN_DELTA
                && Math.abs(currentTilt - snapshotTilt) <= MAX_TILT_DELTA;
    }
}
