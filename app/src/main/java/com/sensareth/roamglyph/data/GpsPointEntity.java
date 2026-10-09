package com.sensareth.roamglyph.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "gps_points",
        foreignKeys = @ForeignKey(
                entity = SessionEntity.class,
                parentColumns = "session_id",
                childColumns = "session_id",
                onDelete = ForeignKey.CASCADE
        ),
        indices = {
                @Index("session_id"),
                @Index(value = {"session_id", "timestamp_ms"})
        }
)
public final class GpsPointEntity {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "point_id")
    public String pointId;

    @NonNull
    @ColumnInfo(name = "session_id")
    public String sessionId;

    @ColumnInfo(name = "timestamp_ms")
    public long timestampMs;

    @ColumnInfo(name = "latitude")
    public double latitude;

    @ColumnInfo(name = "longitude")
    public double longitude;

    @ColumnInfo(name = "accuracy_m")
    public float accuracyM;

    @Nullable
    @ColumnInfo(name = "speed_mps")
    public Float speedMps;

    @Nullable
    @ColumnInfo(name = "altitude_m")
    public Double altitudeM;

    @Nullable
    @ColumnInfo(name = "provider")
    public String provider;

    @ColumnInfo(name = "accepted_for_exploration")
    public boolean acceptedForExploration;

    @Nullable
    @ColumnInfo(name = "h3")
    public String h3;

    @Nullable
    @ColumnInfo(name = "rejection_reason")
    public String rejectionReason;

    public GpsPointEntity(
            @NonNull String pointId,
            @NonNull String sessionId,
            long timestampMs,
            double latitude,
            double longitude,
            float accuracyM,
            @Nullable Float speedMps,
            @Nullable Double altitudeM,
            @Nullable String provider,
            boolean acceptedForExploration,
            @Nullable String h3,
            @Nullable String rejectionReason
    ) {
        this.pointId = pointId;
        this.sessionId = sessionId;
        this.timestampMs = timestampMs;
        this.latitude = latitude;
        this.longitude = longitude;
        this.accuracyM = accuracyM;
        this.speedMps = speedMps;
        this.altitudeM = altitudeM;
        this.provider = provider;
        this.acceptedForExploration = acceptedForExploration;
        this.h3 = h3;
        this.rejectionReason = rejectionReason;
    }
}
