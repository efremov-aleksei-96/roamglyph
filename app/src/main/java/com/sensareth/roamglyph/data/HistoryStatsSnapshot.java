package com.sensareth.roamglyph.data;

import androidx.annotation.NonNull;

import java.util.List;

public final class HistoryStatsSnapshot {
    public final int visitedCells;
    public final int discoveries;
    public final int sessions;
    public final long gpsPoints;
    public final double distanceM;
    public final long acceptedPoints;
    public final long trackedDurationMs;
    @NonNull public final List<SessionEntity> recentSessions;

    public HistoryStatsSnapshot(
            int visitedCells,
            int discoveries,
            int sessions,
            long gpsPoints,
            double distanceM,
            long acceptedPoints,
            long trackedDurationMs,
            @NonNull List<SessionEntity> recentSessions
    ) {
        this.visitedCells = visitedCells;
        this.discoveries = discoveries;
        this.sessions = sessions;
        this.gpsPoints = gpsPoints;
        this.distanceM = distanceM;
        this.acceptedPoints = acceptedPoints;
        this.trackedDurationMs = trackedDurationMs;
        this.recentSessions = recentSessions;
    }
}
