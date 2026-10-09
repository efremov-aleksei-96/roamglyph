package com.sensareth.roamglyph.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "visited_cells")
public final class VisitedCellEntity {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "h3")
    public String h3;

    @Nullable
    @ColumnInfo(name = "first_seen_at_ms")
    public Long firstSeenAtMs;

    @NonNull
    @ColumnInfo(name = "source")
    public String source;

    public VisitedCellEntity(
            @NonNull String h3,
            @Nullable Long firstSeenAtMs,
            @NonNull String source
    ) {
        this.h3 = h3;
        this.firstSeenAtMs = firstSeenAtMs;
        this.source = source;
    }
}
