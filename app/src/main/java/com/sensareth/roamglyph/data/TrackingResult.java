package com.sensareth.roamglyph.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TrackingResult {
    public final boolean accepted;
    @Nullable public final String rejectionReason;
    public final int totalVisitedCells;
    public final int newCells;
    public final double distanceDeltaM;
    @NonNull public final List<String> newCellIds;

    public TrackingResult(
            boolean accepted,
            @Nullable String rejectionReason,
            int totalVisitedCells,
            int newCells,
            double distanceDeltaM,
            @NonNull List<String> newCellIds
    ) {
        this.accepted = accepted;
        this.rejectionReason = rejectionReason;
        this.totalVisitedCells = totalVisitedCells;
        this.newCells = newCells;
        this.distanceDeltaM = distanceDeltaM;
        this.newCellIds = Collections.unmodifiableList(new ArrayList<>(newCellIds));
    }
}
