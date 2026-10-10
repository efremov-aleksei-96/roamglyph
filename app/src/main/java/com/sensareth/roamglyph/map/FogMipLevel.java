package com.sensareth.roamglyph.map;

/** Pick a pre-area-filtered alpha mip level for a continuously zoomed map. */
public final class FogMipLevel {
    private FogMipLevel() {}

    public static int forZoomDelta(double zoomOut, int levelCount) {
        if (levelCount <= 1 || !Double.isFinite(zoomOut)) return 0;
        // Each mip level is an approximate 2x2 coverage average.
        // Half-step switching minimizes bilinear-only minification.
        int level = (int) Math.floor(zoomOut + 0.5);
        return Math.max(0, Math.min(levelCount - 1, level));
    }
}
