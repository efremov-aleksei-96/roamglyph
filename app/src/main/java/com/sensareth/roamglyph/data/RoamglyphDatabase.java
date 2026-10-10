package com.sensareth.roamglyph.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(
        entities = {
                VisitedCellEntity.class,
                SessionEntity.class,
                GpsPointEntity.class,
                DiscoveryEntity.class
        },
        version = 3,
        exportSchema = false
)
public abstract class RoamglyphDatabase extends RoomDatabase {
    private static final String DATABASE_NAME = "roamglyph.db";

    private static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS discoveries (" +
                    "discovery_id TEXT NOT NULL, " +
                    "name TEXT NOT NULL, " +
                    "category TEXT NOT NULL, " +
                    "subclass TEXT, " +
                    "latitude REAL NOT NULL, " +
                    "longitude REAL NOT NULL, " +
                    "h3 TEXT NOT NULL, " +
                    "discovered_at_ms INTEGER, " +
                    "source TEXT NOT NULL, " +
                    "PRIMARY KEY(discovery_id))"
            );
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_discoveries_category " +
                    "ON discoveries(category)"
            );
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_discoveries_h3 " +
                    "ON discoveries(h3)"
            );
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_discoveries_latitude " +
                    "ON discoveries(latitude)"
            );
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_discoveries_longitude " +
                    "ON discoveries(longitude)"
            );
        }
    };

    // Adds only a read-performance index. No history/GPS tables are rebuilt
    // or deleted; every existing point and H3 visit stays untouched.
    private static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                    "index_gps_points_accepted_for_exploration_latitude_longitude " +
                    "ON gps_points(accepted_for_exploration, latitude, longitude)"
            );
        }
    };

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
                        )
                        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                        .build();
                instance = local;
            }
        }

        return local;
    }
}
