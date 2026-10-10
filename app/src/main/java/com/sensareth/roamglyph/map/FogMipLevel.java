package com.sensareth.roamglyph.map;

/** Pick a pre-area-filtered alpha mip level for a continuously zoomed map. */
public final class FogMipLevel {
    private FogMipLevel() {}

    /**
     * The first retained pyramid bitmap may already be area-filtered below
     * 1x. Account for that scale so the zoom delta does not downsample twice.
     */
    public static int forZoomDelta(double zoomOut, float retainedScale,
                                   int levelCount) {
        if (!Float.isFinite(retainedScale) || retainedScale <= 0f) return 0;
        return forZoomDelta(zoomOut
                + Math.log(retainedScale) / Math.log(2.0), levelCount);
    }

    public static int forZoomDelta(double zoomOut, int levelCount) {
        if (levelCount <= 1 || !Double.isFinite(zoomOut)) return 0;
        // Each mip level is an approximate 2x2 coverage average.
        // Half-step switching minimizes bilinear-only minification.
        int level = (int) Math.floor(zoomOut + 0.5);
        return Math.max(0, Math.min(levelCount - 1, level));
    }
}
