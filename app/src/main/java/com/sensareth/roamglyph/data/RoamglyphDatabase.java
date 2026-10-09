package com.sensareth.roamglyph.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(
        entities = {
                VisitedCellEntity.class,
                SessionEntity.class,
                GpsPointEntity.class
        },
        version = 1,
        exportSchema = false
)
public abstract class RoamglyphDatabase extends RoomDatabase {
    private static final String DATABASE_NAME = "roamglyph.db";

    private static volatile RoamglyphDatabase instance;

    public abstract ExplorationDao explorationDao();

    public static RoamglyphDatabase get(Context context) {
        RoamglyphDatabase local = instance;
        if (local != null) return local;

        synchronized (RoamglyphDatabase.class) {
            local = instance;
            if (local == null) {
                local = Room.databaseBuilder(
                        context.getApplicationContext(),
                        RoamglyphDatabase.class,
                        DATABASE_NAME
                ).build();
                instance = local;
            }
        }

        return local;
    }
}
