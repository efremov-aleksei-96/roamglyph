package com.sensareth.roamglyph.data;

import androidx.annotation.Nullable;

public final class GpsAcceptancePolicy {
    public static final float MAX_ACCEPTED_ACCURACY_M = 35f;
    public static final double MAX_PLAUSIBLE_SPEED_MPS = 55.0;
    public static final long MAX_FIX_AGE_NS = 30_000_000_000L;

    private GpsAcceptancePolicy() {
    }

    // Location.getElapsedRealtimeNanos() is monotonic within a boot; unlike
    // Location.getTime(), it is unaffected by wall-clock corrections.
    public static boolean isStaleFix(
            long fixElapsedNanos,
            long nowElapsedNanos,
            long lastAcceptedElapsedNanos
    ) {
        return fixElapsedNanos <= 0L
                || fixElapsedNanos > nowElapsedNanos
                || nowElapsedNanos - fixElapsedNanos > MAX_FIX_AGE_NS
                || (lastAcceptedElapsedNanos > 0L
                    && fixElapsedNanos <= lastAcceptedElapsedNanos);
    }

    @Nullable
    public static String rejectionReason(
            long timestampMs,
            double latitude,
            double longitude,
            float accuracyM,
            @Nullable GpsPointEntity previousAccepted
    ) {
        if (!Double.isFinite(latitude)
                || !Double.isFinite(longitude)
                || latitude < -90.0
                || latitude > 90.0
                || longitude < -180.0
                || longitude > 180.0) {
            return "invalid_coordinate";
        }

        // A location without a usable horizontal accuracy estimate must not reveal
        // new H3 cells. Android returns 0 when Location.hasAccuracy() is false.
        if (!Float.isFinite(accuracyM)
                || accuracyM <= 0f
                || accuracyM > MAX_ACCEPTED_ACCURACY_M) {
            return "accuracy";
        }

        if (previousAccepted == null) return null;

        // Wall-clock timestamps may jump backwards after clock correction.
        // Monotonic age/ordering is validated separately with isStaleFix.
        long elapsedMs = timestampMs - previousAccepted.timestampMs;
        if (elapsedMs <= 0L) return null;

        double distanceM = distanceMeters(
                previousAccepted.latitude,
                previousAccepted.longitude,
                latitude,
                longitude
        );
        double elapsedSeconds = elapsedMs / 1000.0;
        double derivedSpeedMps = distanceM / elapsedSeconds;
        double uncertaintyM = Math.max(
                100.0,
                3.0 * (Math.max(0f, accuracyM) + Math.max(0f, previousAccepted.accuracyM))
        );

        if (distanceM > uncertaintyM && derivedSpeedMps > MAX_PLAUSIBLE_SPEED_MPS) {
            return "jump";
        }

        return null;
    }

    public static double distanceMeters(
            double lat1,
            double lon1,
            double lat2,
            double lon2
    ) {
        double earthRadiusM = 6_371_008.8;
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double deltaPhi = Math.toRadians(lat2 - lat1);
        double deltaLambda = Math.toRadians(lon2 - lon1);

        double a = Math.sin(deltaPhi / 2.0) * Math.sin(deltaPhi / 2.0)
                + Math.cos(phi1) * Math.cos(phi2)
                * Math.sin(deltaLambda / 2.0) * Math.sin(deltaLambda / 2.0);
        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
        return earthRadiusM * c;
    }
}
