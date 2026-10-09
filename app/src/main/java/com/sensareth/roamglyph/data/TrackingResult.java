package com.sensareth.roamglyph.data;

import androidx.annotation.Nullable;

public final class TrackingResult {
    public final boolean accepted;
    @Nullable public final String rejectionReason;
    public final int totalVisitedCells;
    public final int newCells;
    public final double distanceDeltaM;

    public TrackingResult(
            boolean accepted,
            @Nullable String rejectionReason,
            int totalVisitedCells,
            int newCells,
            double distanceDeltaM
    ) {
        this.accepted = accepted;
        this.rejectionReason = rejectionReason;
        this.totalVisitedCells = totalVisitedCells;
        this.newCells = newCells;
        this.distanceDeltaM = distanceDeltaM;
    }
}
