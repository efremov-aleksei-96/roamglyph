package com.sensareth.roamglyph.data;

import androidx.annotation.NonNull;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface ExplorationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertVisitedCell(VisitedCellEntity cell);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long[] insertVisitedCells(List<VisitedCellEntity> cells);

    @Query("SELECT h3 FROM visited_cells")
    List<String> loadVisitedCellIds();

    @Query("SELECT * FROM visited_cells ORDER BY h3")
    List<VisitedCellEntity> loadVisitedCells();

    @Query("SELECT * FROM visited_cells ORDER BY h3 LIMIT :limit OFFSET :offset")
    List<VisitedCellEntity> loadVisitedCellsPage(int limit, int offset);

    @Query("SELECT COUNT(*) FROM visited_cells")
    int countVisitedCells();

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertSession(SessionEntity session);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long[] insertSessions(List<SessionEntity> sessions);

    @Query("SELECT * FROM sessions WHERE session_id = :sessionId LIMIT 1")
    SessionEntity getSession(@NonNull String sessionId);

    @Query("SELECT * FROM sessions ORDER BY started_at_ms ASC, session_id ASC")
    List<SessionEntity> loadSessions();

    @Query("SELECT * FROM sessions ORDER BY started_at_ms ASC, session_id ASC LIMIT :limit OFFSET :offset")
    List<SessionEntity> loadSessionsPage(int limit, int offset);

    @Query("SELECT COUNT(*) FROM sessions")
    int countSessions();

    @Query("UPDATE sessions SET ended_at_ms = :endedAtMs WHERE session_id = :sessionId AND ended_at_ms IS NULL")
    int endSession(@NonNull String sessionId, long endedAtMs);

    @Query(
            "UPDATE sessions SET " +
            "distance_m = distance_m + :distanceDeltaM, " +
            "accepted_points = accepted_points + :acceptedPointDelta, " +
            "new_cells = new_cells + :newCellDelta " +
            "WHERE session_id = :sessionId"
    )
    int incrementSessionStats(
            @NonNull String sessionId,
            double distanceDeltaM,
            long acceptedPointDelta,
            long newCellDelta
    );

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertGpsPoint(GpsPointEntity point);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long[] insertGpsPoints(List<GpsPointEntity> points);

    @Query(
            "SELECT * FROM gps_points " +
            "WHERE session_id = :sessionId AND accepted_for_exploration = 1 " +
            "ORDER BY timestamp_ms DESC, rowid DESC LIMIT 1"
    )
    GpsPointEntity getLatestAcceptedPoint(@NonNull String sessionId);

    @Query("SELECT * FROM gps_points ORDER BY timestamp_ms ASC, point_id ASC")
    List<GpsPointEntity> loadGpsPoints();

    @Query("SELECT * FROM gps_points ORDER BY timestamp_ms ASC, point_id ASC LIMIT :limit OFFSET :offset")
    List<GpsPointEntity> loadGpsPointsPage(int limit, int offset);

    @Query("SELECT COUNT(*) FROM gps_points")
    long countGpsPoints();

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertDiscovery(DiscoveryEntity discovery);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long[] insertDiscoveries(List<DiscoveryEntity> discoveries);

    @Query("SELECT * FROM discoveries ORDER BY discovery_id ASC")
    List<DiscoveryEntity> loadDiscoveries();

    @Query("SELECT * FROM discoveries ORDER BY discovery_id ASC LIMIT :limit OFFSET :offset")
    List<DiscoveryEntity> loadDiscoveriesPage(int limit, int offset);

    @Query("SELECT discovery_id FROM discoveries")
    List<String> loadDiscoveryIds();

    @Query("SELECT COUNT(*) FROM discoveries")
    int countDiscoveries();
}
