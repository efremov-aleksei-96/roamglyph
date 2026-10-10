package com.sensareth.roamglyph.map;

/**
 * Smooth, exact-coverage-bounded inward fog feather.
 *
 * The low-pass shape weight suppresses the conspicuous H3 sawtooth outline,
 * while distance-based fading keeps the outermost hexagon edge visually dark.
 * Raw mask alpha is a strict upper bound: unvisited pixels are NEVER revealed.
 */
public final class FogFeatherProfile {
    private static final float THIN_TRAIL_VISIBILITY = 0.18f;

    private FogFeatherProfile() {}

    private static float smoothStep(float value) {
        float x = Math.max(0f, Math.min(1f, value));
        return x * x * (3f - 2f * x);
    }

    public static int cutoutAlpha(int rawAlpha, int blurredAlpha,
                                  int distanceSteps, int featherRadiusPx) {
        if (rawAlpha <= 0 || distanceSteps <= 0) return 0;

        // Chamfer distance is in thirds of raster pixels, not map meters.
        float distancePx = distanceSteps / 3f;
        float feather = smoothStep(
                (distancePx - 0.5f) / Math.max(1, featherRadiusPx));

        // A broad low-pass mask removes bumps smaller than one H3 cell:
        // protruding hex vertices contain little neighbourhood coverage.
        // Suppress their transparency instead of imposing the old 48% floor,
        // which made even low-coverage teeth distinctly visible.
        float coverage = Math.max(0f, Math.min(1f, blurredAlpha / 255f));
        float smoothedCore = smoothStep((coverage - 0.38f) / 0.55f);

        // Sparse one-pixel H3 trails need a faint interior even when the
        // blurred neighbourhood consists mostly of unexplored territory.
        // Keep this tiny floor INSIDE the exact H3 cutout only.
        float reveal = feather * Math.max(
                THIN_TRAIL_VISIBILITY, smoothedCore);
        int alpha = Math.round(255f * reveal);
        return Math.max(0, Math.min(rawAlpha, alpha));
    }
}
