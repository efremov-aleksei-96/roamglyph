package com.sensareth.roamglyph.map;

/**
 * Turns a spatially smoothed, supersampled coverage field into a graceful,
 * inward-only fog fade. This is NOT an outline stroke.
 *
 * The source H3 alpha is an absolute upper bound on the returned cutout;
 * pixels outside the genuinely visited area cannot be exposed.
 */
public final class FogFeatherProfile {
    private FogFeatherProfile() {}

    private static float smoothStep(float value) {
        float t = Math.max(0f, Math.min(1f, value));
        return t * t * (3f - 2f * t);
    }

    public static int cutoutAlpha(int rawAlpha, int smoothedAlpha,
                                  int distanceSteps, int featherRadiusPx) {
        if (rawAlpha <= 0 || distanceSteps <= 0) return 0;
        float distance = distanceSteps / 3f;
        float feather = Math.max(1f, featherRadiusPx);
        float occupancy = Math.max(0f, Math.min(1f, smoothedAlpha / 255f));

        // This is the actual SILHOUETTE. Averaged H3 tips typically have
        // much lower neighbourhood occupancy than a continuous visited
        // corridor. Keep their outline effectively dark, and let the smooth
        // field -- not a clipped polygon edge -- determine visibility.
        float silhouette = smoothStep((occupancy - 0.32f) / 0.50f);
        float broadGradient = smoothStep((distance - 0.4f) / (0.90f * feather));
        float mainReveal = silhouette * broadGradient;

        // An isolated visited cell or a 1-cell trail may be too thin to
        // have a large-area occupancy core. Preserve its *center*, not the
        // geometric vertices: a short inward ramp and local density gate
        // retain a readable narrow route without resurrecting H3 teeth.
        float compact = smoothStep((occupancy - 0.09f) / 0.36f);
        float inside = smoothStep((distance - 0.55f)
                / Math.min(4f, Math.max(1f, 0.35f * feather)));
        // The narrow-route channel must switch off in solid interiors;
        // otherwise it creates a 72%-clear plateau that makes the gradient
        // look like a sharp boundary even after contour smoothing.
        float narrowOnly = 1f - smoothStep((occupancy - 0.48f) / 0.37f);
        float narrowReveal = 0.72f * compact * inside * narrowOnly;

        // A one-screen-pixel route remains visible at city zoom, where
        // even the supersampled mask is only 1-2 pixels across. At street
        // zoom this rescue vanishes, avoiding jagged H3 perimeter detail.
        float microscopic = 0.22f * smoothStep((10f - feather) / 7f)
                * smoothStep((distance - 0.25f) / 0.65f);
        float reveal = Math.max(mainReveal,
                Math.max(narrowReveal, microscopic));

        // Never increase rasterized H3 source opacity. AA edge samples and
        // the final vector clip enforce geographic conservation on the GPU.
        return Math.max(0, Math.min(rawAlpha, Math.round(255f * reveal)));
    }
}
