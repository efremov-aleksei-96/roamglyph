package com.sensareth.roamglyph.map;

import java.util.Locale;

/** Pure metric map-ruler math. No GPS, stored coordinates or map-network calls. */
public final class MapScaleMath {
    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private MapScaleMath() { }

    /**
     * Great-circle distance between two screen-projected map coordinates.
     * Coordinates are used in memory only and never exported or persisted.
     */
    public static double distanceMeters(double lat1, double lon1,
                                        double lat2, double lon2) {
        if (!Double.isFinite(lat1) || !Double.isFinite(lat2)
                || !Double.isFinite(lon1) || !Double.isFinite(lon2)
                || Math.abs(lat1) > 90.0 || Math.abs(lat2) > 90.0) {
            return Double.NaN;
        }
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double halfLat = (p2 - p1) / 2.0;
        double halfLon = Math.toRadians(lon2 - lon1) / 2.0;
        double a = Math.sin(halfLat) * Math.sin(halfLat)
                + Math.cos(p1) * Math.cos(p2)
                * Math.sin(halfLon) * Math.sin(halfLon);
        return 2.0 * EARTH_RADIUS_METERS
                * Math.asin(Math.sqrt(Math.max(0.0, Math.min(1.0, a))));
    }

    /** Largest 1–2–5 × 10^n scale segment not exceeding the available width. */
    public static double niceDistanceMeters(double availableMeters) {
        if (!Double.isFinite(availableMeters) || availableMeters <= 0) {
            return Double.NaN;
        }
        double unit = Math.pow(10.0, Math.floor(Math.log10(availableMeters)));
        double ratio = availableMeters / unit;
        return (ratio >= 5.0 ? 5.0 : ratio >= 2.0 ? 2.0 : 1.0) * unit;
    }

    /**
     * Find the bar length along a potentially pitched (non-linear) screen
     * projection. Samples actual ground distance from its fixed top anchor.
     * Uses only 9 projection evaluations; called on a throttled UI refresh.
     */
    public static float projectedLengthForDistance(double meters,
            float maximumPx, java.util.function.DoubleUnaryOperator distanceAtPixel) {
        if (!Double.isFinite(meters) || meters <= 0
                || !Float.isFinite(maximumPx) || maximumPx <= 0)
            return Float.NaN;
        double lo = 0.0;
        double hi = maximumPx;
        for (int i = 0; i < 9; i++) {
            double mid = (lo + hi) * .5;
            double observed = distanceAtPixel.applyAsDouble(mid);
            if (!Double.isFinite(observed) || observed < 0) return Float.NaN;
            if (observed < meters) lo = mid;
            else hi = mid;
        }
        return (float) ((lo + hi) * .5);
    }

    public static String label(double meters) {
        if (!Double.isFinite(meters) || meters <= 0) return "—";
        if (meters >= 1000.0) {
            double km = meters / 1000.0;
            return km == Math.rint(km)
                    ? String.format(Locale.US, "%.0f km", km)
                    : String.format(Locale.US, "%.1f km", km);
        }
        if (meters >= 1.0) {
            return meters == Math.rint(meters)
                    ? String.format(Locale.US, "%.0f m", meters)
                    : String.format(Locale.US, "%.1f m", meters);
        }
        return String.format(Locale.US, "%.0f cm", meters * 100.0);
    }
}
