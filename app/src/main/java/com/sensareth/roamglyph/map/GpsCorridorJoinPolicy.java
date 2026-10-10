package com.sensareth.roamglyph.map;

/**
 * Conservative GPS path stitching: never fabricate a smooth corridor across
 * unrelated sessions, long tracking gaps, or large location jumps.
 */
public final class GpsCorridorJoinPolicy {
    private static final long MAX_GAP_MS = 25_000L;
    private static final double MAX_DISTANCE_M = 140.0;

    private GpsCorridorJoinPolicy() {}

    public static boolean shouldConnect(String previousSession, long previousMs,
                                        double previousLat, double previousLng,
                                        String currentSession, long currentMs,
                                        double currentLat, double currentLng) {
        if (previousSession == null || !previousSession.equals(currentSession)
                || currentMs <= previousMs || currentMs - previousMs > MAX_GAP_MS
                || !Double.isFinite(previousLat) || !Double.isFinite(previousLng)
                || !Double.isFinite(currentLat) || !Double.isFinite(currentLng)) return false;
        double dLat = Math.toRadians(currentLat - previousLat);
        double dLng = Math.toRadians(currentLng - previousLng);
        double sinLat = Math.sin(dLat * 0.5);
        double sinLng = Math.sin(dLng * 0.5);
        double a = sinLat * sinLat + Math.cos(Math.toRadians(previousLat))
                * Math.cos(Math.toRadians(currentLat)) * sinLng * sinLng;
        double distance = 2.0 * 6371000.0 * Math.asin(Math.sqrt(
                Math.min(1.0, Math.max(0.0, a))));
        return distance <= MAX_DISTANCE_M;
    }
}
