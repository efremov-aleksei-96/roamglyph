package com.sensareth.roamglyph.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "sessions")
public final class SessionEntity {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "session_id")
    public String sessionId;

    @ColumnInfo(name = "started_at_ms")
    public long startedAtMs;

    @Nullable
    @ColumnInfo(name = "ended_at_ms")
    public Long endedAtMs;

    @ColumnInfo(name = "distance_m")
    public double distanceM;

    @ColumnInfo(name = "accepted_points")
    public long acceptedPoints;

    @ColumnInfo(name = "new_cells")
    public long newCells;

    @NonNull
    @ColumnInfo(name = "source")
    public String source;

    public SessionEntity(
            @NonNull String sessionId,
            long startedAtMs,
            @Nullable Long endedAtMs,
            double distanceM,
            long acceptedPoints,
            long newCells,
            @NonNull String source
    ) {
        this.sessionId = sessionId;
        this.startedAtMs = startedAtMs;
        this.endedAtMs = endedAtMs;
        this.distanceM = distanceM;
        this.acceptedPoints = acceptedPoints;
        this.newCells = newCells;
        this.source = source;
    }
}
