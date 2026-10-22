package com.sensareth.roamglyph.data;

import androidx.annotation.Nullable;

/**
 * Presentation-only status of the latest GPS observation. The acceptance
 * policy in ExplorationRepository remains the sole authority for recording
 * new GPS points and H3 coverage; this class never decides acceptance.
 */
public final class GpsFixStatus {
    private GpsFixStatus() { }

    /**
     * A displayed raw fix can be less accurate than the previous accepted fix.
     * Always distinguish active tracking from saving this particular sample.
     */
    public static String suffix(boolean tracking, float accuracyMeters,
                                @Nullable String rejectionReason) {
        if (!tracking) return "";
        if (!Float.isFinite(accuracyMeters) || accuracyMeters <= 0f
                || accuracyMeters > GpsAcceptancePolicy.MAX_ACCEPTED_ACCURACY_M) {
            return " · low accuracy (fix not recorded)";
        }
        if ("stale".equals(rejectionReason)) return " · stale fix ignored";
        if ("jump".equals(rejectionReason)) return " · GPS jump ignored";
        if (rejectionReason != null) return " · fix ignored";
        return "";
    }
}
