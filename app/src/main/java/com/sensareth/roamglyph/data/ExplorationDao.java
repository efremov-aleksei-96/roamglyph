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

    @Query("SELECT * FROM sessions ORDER BY started_at_ms DESC, session_id DESC LIMIT :limit")
    List<SessionEntity> loadRecentSessions(int limit);

    @Query("SELECT COALESCE(SUM(distance_m), 0.0) FROM sessions")
    double sumSessionDistanceMeters();

    @Query("SELECT COALESCE(SUM(accepted_points), 0) FROM sessions")
    long sumAcceptedPoints();

    @Query(
            "SELECT COALESCE(SUM(" +
            "CASE WHEN COALESCE(ended_at_ms, :nowMs) >= started_at_ms " +
            "THEN COALESCE(ended_at_ms, :nowMs) - started_at_ms ELSE 0 END" +
            "), 0) FROM sessions"
    )
    long sumTrackedDurationMs(long nowMs);

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

    // Read-only spatial query: no schema/index migration required. The extra
    // limit slot detects truncation; partial GPS corridors fail back to H3.
    @Query("SELECT * FROM gps_points "
            + "WHERE accepted_for_exploration = 1 "
            + "AND latitude BETWEEN :south AND :north "
            + "AND longitude BETWEEN :west AND :east "
            + "ORDER BY session_id ASC, timestamp_ms ASC, point_id ASC "
            + "LIMIT :limit")
    List<GpsPointEntity> loadAcceptedGpsPointsInBounds(
            double south, double north, double west, double east, int limit);

    @Query("SELECT * FROM gps_points ORDER BY timestamp_ms ASC, point_id ASC LIMIT :limit OFFSET :offset")
    List<GpsPointEntity> loadGpsPointsPage(int limit, int offset);

    @Query("SELECT COUNT(*) FROM gps_points")
    long countGpsPoints();

    @Query(
            "SELECT COUNT(*) FROM gps_points " +
            "WHERE session_id = :sessionId " +
            "AND accepted_for_exploration = 1 " +
            "AND timestamp_ms <= :throughTimestampMs"
    )
    long countAcceptedGpsPointsForSession(
            @NonNull String sessionId,
            long throughTimestampMs
    );

    @Query(
            "SELECT * FROM gps_points " +
            "WHERE session_id = :sessionId " +
            "AND accepted_for_exploration = 1 " +
            "AND timestamp_ms <= :throughTimestampMs " +
            "ORDER BY timestamp_ms ASC, point_id ASC " +
            "LIMIT :limit OFFSET :offset"
    )
    List<GpsPointEntity> loadAcceptedGpsPointsForSessionPage(
            @NonNull String sessionId,
            long throughTimestampMs,
            int limit,
            int offset
    );

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

    @Query("SELECT * FROM visited_cells WHERE h3 IN (:cellIds)")
    List<VisitedCellEntity> findVisitedCells(List<String> cellIds);

    @Query("SELECT discovery_id FROM discoveries WHERE discovery_id IN (:discoveryIds)")
    List<String> findDiscoveryIds(List<String> discoveryIds);

    @Query(
            "SELECT * FROM discoveries " +
            "WHERE latitude BETWEEN :south AND :north " +
            "AND longitude BETWEEN :west AND :east " +
            "ORDER BY discovered_at_ms DESC, discovery_id ASC " +
            "LIMIT :limit"
    )
    List<DiscoveryEntity> loadDiscoveriesInBounds(
            double south,
            double north,
            double west,
            double east,
            int limit
    );
}
