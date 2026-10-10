package com.sensareth.roamglyph.map;

/**
 * Converts a rasterized exact H3 mask into a continuous inward feather.
 * A visited cutout is never more opaque than its source coverage; pixels
 * outside the source polygon (rawAlpha == 0) can never become uncovered.
 */
public final class FogFeatherProfile {
    private FogFeatherProfile() {}

    public static int cutoutAlpha(int rawAlpha, int blurredAlpha,
                                  int distanceSteps, int featherRadiusPx) {
        if (rawAlpha <= 0 || distanceSteps <= 0) return 0;
        // Chamfer distances use three steps per orthogonal raster pixel.
        float distancePx = distanceSteps / 3f;
        float t = Math.min(1f, Math.max(0f,
                (distancePx - 0.5f) / Math.max(1, featherRadiusPx)));
        // Continuous gradient, zero at the explored cell boundary. Unlike
        // a partially transparent hard clip this does not outline hexes.
        float feather = t * t * (3f - 2f * t);
        // Low-pass the coverage to suppress isolated H3 sawteeth. Narrow
        // explored corridors retain a low-contrast hint, not extra territory.
        float shape = Math.max(0.48f,
                Math.min(1f, blurredAlpha / 255f));
        int alpha = Math.round(255f * feather * shape);
        return Math.max(0, Math.min(rawAlpha, alpha));
    }
}
