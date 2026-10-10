package com.sensareth.roamglyph.map;

/**
 * Allows bitmap reuse only while image magnification cannot expand an H3
 * footprint by more than half of one physical display pixel. Source cells
 * are never enlarged by changing their H3 parent resolution.
 */
public final class FogMaskReusePolicy {
    public static final double MAX_ZOOM_IN_DELTA = 0.05;
    public static final double MAX_TILT_DELTA = 1.0;

    private FogMaskReusePolicy() {
    }

    public static double safeZoomDelta(double snapshotCellDiameterPx) {
        if (!Double.isFinite(snapshotCellDiameterPx)
                || snapshotCellDiameterPx <= 0.0) return 0.0;
        double safePixelDiameter = Math.max(1.0, snapshotCellDiameterPx);
        double deltaForHalfPixel = Math.log1p(0.5 / safePixelDiameter)
                / Math.log(2.0);
        return Math.min(MAX_ZOOM_IN_DELTA, deltaForHalfPixel);
    }

    public static boolean mayReuse(
            double snapshotZoom, double currentZoom,
            double snapshotTilt, double currentTilt,
            double snapshotCellDiameterPx
    ) {
        return Double.isFinite(snapshotZoom) && Double.isFinite(currentZoom)
                && Double.isFinite(snapshotTilt) && Double.isFinite(currentTilt)
                && Double.isFinite(snapshotCellDiameterPx)
                && snapshotCellDiameterPx > 0.0
                && currentZoom <= snapshotZoom + safeZoomDelta(snapshotCellDiameterPx)
                && Math.abs(currentTilt - snapshotTilt) <= MAX_TILT_DELTA;
    }
}
