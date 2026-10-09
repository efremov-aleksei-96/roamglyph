package com.sensareth.roamglyph.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "discoveries",
        indices = {
                @Index("category"),
                @Index("h3")
        }
)
public final class DiscoveryEntity {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "discovery_id")
    public String discoveryId;

    @NonNull
    @ColumnInfo(name = "name")
    public String name;

    @NonNull
    @ColumnInfo(name = "category")
    public String category;

    @Nullable
    @ColumnInfo(name = "subclass")
    public String subclass;

    @ColumnInfo(name = "latitude")
    public double latitude;

    @ColumnInfo(name = "longitude")
    public double longitude;

    @NonNull
    @ColumnInfo(name = "h3")
    public String h3;

    @Nullable
    @ColumnInfo(name = "discovered_at_ms")
    public Long discoveredAtMs;

    @NonNull
    @ColumnInfo(name = "source")
    public String source;

    public DiscoveryEntity(
            @NonNull String discoveryId,
            @NonNull String name,
            @NonNull String category,
            @Nullable String subclass,
            double latitude,
            double longitude,
            @NonNull String h3,
            @Nullable Long discoveredAtMs,
            @NonNull String source
    ) {
        this.discoveryId = discoveryId;
        this.name = name;
        this.category = category;
        this.subclass = subclass;
        this.latitude = latitude;
        this.longitude = longitude;
        this.h3 = h3;
        this.discoveredAtMs = discoveredAtMs;
        this.source = source;
    }
}
