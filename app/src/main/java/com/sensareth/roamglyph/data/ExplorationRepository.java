package com.sensareth.roamglyph.data;

import android.content.Context;
import android.location.Location;

import com.sensareth.roamglyph.VisitedStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.uber.h3core.H3Core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ExplorationRepository {
    private static final int H3_RESOLUTION = 13;

    private final RoamglyphDatabase database;
    private final ExplorationDao dao;

    public ExplorationRepository(Context context) {
        database = RoamglyphDatabase.get(context);
        dao = database.explorationDao();
    }

    public Set<String> loadVisitedCellIds() {
        return new HashSet<>(dao.loadVisitedCellIds());
    }

    public List<VisitedCellEntity> loadVisitedCells() {
        return dao.loadVisitedCells();
    }

    public List<SessionEntity> loadSessions() {
        return dao.loadSessions();
    }

    public List<GpsPointEntity> loadGpsPoints() {
        return dao.loadGpsPoints();
    }

    public int countVisitedCells() {
        return dao.countVisitedCells();
    }

    public long countGpsPoints() {
        return dao.countGpsPoints();
    }

    public int countSessions() {
        return dao.countSessions();
    }

    public List<VisitedCellEntity> loadVisitedCellsPage(int limit, int offset) {
        return dao.loadVisitedCellsPage(limit, offset);
    }

    public List<SessionEntity> loadSessionsPage(int limit, int offset) {
        return dao.loadSessionsPage(limit, offset);
    }

    public List<GpsPointEntity> loadGpsPointsPage(int limit, int offset) {
        return dao.loadGpsPointsPage(limit, offset);
    }

    public int importVisitedCells(List<VisitedCellEntity> cells) {
        if (cells.isEmpty()) return 0;
        long[] rows = dao.insertVisitedCells(cells);
        return countInserted(rows);
    }

    public int importSessions(List<SessionEntity> sessions) {
        if (sessions.isEmpty()) return 0;
        long[] rows = dao.insertSessions(sessions);
        return countInserted(rows);
    }

    public long importGpsPoints(List<GpsPointEntity> points) {
        if (points.isEmpty()) return 0L;
        long[] rows = dao.insertGpsPoints(points);
        return countInserted(rows);
    }

    private static int countInserted(long[] rows) {
        int inserted = 0;
        for (long row : rows) {
            if (row != -1L) inserted++;
        }
        return inserted;
    }

    public int importLegacyCells(Set<String> cells) {
        return importCells(cells, null, "legacy-v1");
    }

    public int importCells(
            Set<String> cells,
            @Nullable Long firstSeenAtMs,
            @NonNull String source
    ) {
        if (cells.isEmpty()) return 0;

        List<VisitedCellEntity> entities = new ArrayList<>(cells.size());
        for (String cell : cells) {
            entities.add(new VisitedCellEntity(cell, firstSeenAtMs, source));
        }

        return countInserted(dao.insertVisitedCells(entities));
    }

    public int migrateLegacyCellsIfNeeded(@NonNull VisitedStore state) {
        synchronized (ExplorationRepository.class) {
            if (state.isLegacyCellMigrationComplete()) {
                int count = dao.countVisitedCells();
                state.setVisitedCountCache(count);
                return 0;
            }

            Set<String> legacy = state.loadLegacyCells();
            int inserted = importLegacyCells(legacy);
            int count = dao.countVisitedCells();

            state.setVisitedCountCache(count);
            state.markLegacyCellMigrationComplete();
            return inserted;
        }
    }

    @NonNull
    public String ensureSession(@Nullable String requestedSessionId, long nowMs) {
        if (requestedSessionId != null && !requestedSessionId.isBlank()) {
            SessionEntity existing = dao.getSession(requestedSessionId);
            if (existing != null && existing.endedAtMs == null) {
                return requestedSessionId;
            }
        }

        String id = UUID.randomUUID().toString();
        SessionEntity session = new SessionEntity(
                id,
                nowMs,
                null,
                0.0,
                0L,
                0L,
                "local"
        );
        dao.insertSession(session);
        return id;
    }

    public void endSession(@Nullable String sessionId, long endedAtMs) {
        if (sessionId == null || sessionId.isBlank()) return;
        dao.endSession(sessionId, endedAtMs);
    }

    public TrackingResult recordLocation(
            @NonNull String sessionId,
            @NonNull Location location,
            @NonNull H3Core h3
    ) {
        long timestampMs = location.getTime() > 0L
                ? location.getTime()
                : System.currentTimeMillis();
        float accuracyM = location.hasAccuracy() ? location.getAccuracy() : 0f;

        GpsPointEntity previous = dao.getLatestAcceptedPoint(sessionId);
        String rejectionReason = GpsAcceptancePolicy.rejectionReason(
                timestampMs,
                location.getLatitude(),
                location.getLongitude(),
                accuracyM,
                previous
        );

        boolean accepted = rejectionReason == null;
        String centerCell = accepted
                ? h3.latLngToCellAddress(
                        location.getLatitude(),
                        location.getLongitude(),
                        H3_RESOLUTION
                )
                : null;

        GpsPointEntity point = new GpsPointEntity(
                UUID.randomUUID().toString(),
                sessionId,
                timestampMs,
                location.getLatitude(),
                location.getLongitude(),
                accuracyM,
                location.hasSpeed() ? location.getSpeed() : null,
                location.hasAltitude() ? location.getAltitude() : null,
                location.getProvider(),
                accepted,
                centerCell,
                rejectionReason
        );

        if (!accepted) {
            dao.insertGpsPoint(point);
            return new TrackingResult(
                    false,
                    rejectionReason,
                    dao.countVisitedCells(),
                    0,
                    0.0,
                    java.util.Collections.emptyList()
            );
        }

        Set<String> revealed = new HashSet<>(h3.gridDisk(centerCell, 1));
        revealed.add(centerCell);

        List<VisitedCellEntity> cellEntities = new ArrayList<>(revealed.size());
        for (String cell : revealed) {
            cellEntities.add(new VisitedCellEntity(cell, timestampMs, "gps"));
        }

        double distanceDeltaM = 0.0;
        if (previous != null) {
            distanceDeltaM = GpsAcceptancePolicy.distanceMeters(
                    previous.latitude,
                    previous.longitude,
                    location.getLatitude(),
                    location.getLongitude()
            );
        }

        final int[] newCellsHolder = new int[]{0};
        final List<String> newCellIds = new ArrayList<>();
        final double finalDistanceDeltaM = distanceDeltaM;

        database.runInTransaction(() -> {
            dao.insertGpsPoint(point);
            long[] rows = dao.insertVisitedCells(cellEntities);
            int inserted = 0;
            for (int i = 0; i < rows.length; i++) {
                if (rows[i] != -1L) {
                    inserted++;
                    newCellIds.add(cellEntities.get(i).h3);
                }
            }
            newCellsHolder[0] = inserted;
            dao.incrementSessionStats(
                    sessionId,
                    finalDistanceDeltaM,
                    1L,
                    inserted
            );
        });

        return new TrackingResult(
                true,
                null,
                dao.countVisitedCells(),
                newCellsHolder[0],
                distanceDeltaM,
                newCellIds
        );
    }
}
