package com.sensareth.roamglyph.map;

/** Picks the POI nearest a tapped emoji, preferring visible discovered pins on ties. */
public final class DiscoveryHitPolicy {
    private DiscoveryHitPolicy() {
    }

    public static boolean shouldReplace(
            float candidateDistanceSquared,
            boolean candidateDiscovered,
            float previousDistanceSquared,
            boolean previousDiscovered,
            boolean hasPrevious,
            float maximumDistanceSquared
    ) {
        if (!Float.isFinite(candidateDistanceSquared)
                || candidateDistanceSquared < 0f
                || candidateDistanceSquared > maximumDistanceSquared) {
            return false;
        }
        if (!hasPrevious) return true;
        if (candidateDistanceSquared < previousDistanceSquared) return true;
        return candidateDistanceSquared == previousDistanceSquared
                && candidateDiscovered && !previousDiscovered;
    }
}
