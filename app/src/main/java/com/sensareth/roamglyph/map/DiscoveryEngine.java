package com.sensareth.roamglyph.map;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.sensareth.roamglyph.data.DiscoveryEntity;
import com.sensareth.roamglyph.data.ExplorationRepository;
import com.sensareth.roamglyph.data.VisitedCellEntity;
import com.uber.h3core.H3Core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DiscoveryEngine {
    public static final int MAX_SOURCE_CANDIDATES = 500;
    public static final int MAX_HINTS = 24;
    public static final int MAX_DISCOVERED_MARKERS = 250;
    public static final double MIN_HINT_ZOOM = 15.0;
    public static final double MAX_HINT_DISTANCE_M = 1200.0;
    private static final int H3_RESOLUTION = 13;

    private DiscoveryEngine() {
    }

    @NonNull
    public static Result process(
            @NonNull ExplorationRepository repository,
            @NonNull H3Core h3,
            @NonNull List<PoiDiscoveryCandidate> inputCandidates,
            double south,
            double north,
            double west,
            double east,
            double zoom,
            boolean hasCurrentLocation,
            double currentLatitude,
            double currentLongitude
    ) {
        LinkedHashMap<String, CandidateWithCell> candidates = new LinkedHashMap<>();

        for (PoiDiscoveryCandidate candidate : inputCandidates) {
            if (candidates.size() >= MAX_SOURCE_CANDIDATES) break;
            if (!inside(candidate.latitude, candidate.longitude, south, north, west, east)) {
                continue;
            }

            try {
                String cell;
                synchronized (h3) {
                    cell = h3.latLngToCellAddress(
                            candidate.latitude,
                            candidate.longitude,
                            H3_RESOLUTION
                    );
                }
                candidates.putIfAbsent(
                        candidate.discoveryId,
                        new CandidateWithCell(candidate, cell)
                );
            } catch (RuntimeException ignored) {
                // Invalid source geometry is skipped.
            }
        }

        List<String> ids = new ArrayList<>(candidates.keySet());
        List<String> cellIds = new ArrayList<>();
        Set<String> uniqueCellIds = new HashSet<>();
        for (CandidateWithCell candidate : candidates.values()) {
            if (uniqueCellIds.add(candidate.h3)) {
                cellIds.add(candidate.h3);
            }
        }

        Set<String> existingDiscoveryIds = repository.findDiscoveryIds(ids);
        Map<String, Long> visitedTimestamps = new HashMap<>();
        for (VisitedCellEntity cell : repository.findVisitedCells(cellIds)) {
            visitedTimestamps.put(cell.h3, cell.firstSeenAtMs);
        }

        List<DiscoveryEntity> newlyDiscovered = new ArrayList<>();
        List<PoiDiscoveryCandidate> hintCandidates = new ArrayList<>();

        for (CandidateWithCell item : candidates.values()) {
            PoiDiscoveryCandidate candidate = item.candidate;

            if (existingDiscoveryIds.contains(candidate.discoveryId)) {
                continue;
            }

            if (visitedTimestamps.containsKey(item.h3)) {
                newlyDiscovered.add(new DiscoveryEntity(
                        candidate.discoveryId,
                        candidate.name,
                        candidate.category,
                        candidate.subclass,
                        candidate.latitude,
                        candidate.longitude,
                        item.h3,
                        visitedTimestamps.get(item.h3),
                        "openmaptiles-poi"
                ));
            } else if (zoom >= MIN_HINT_ZOOM) {
                if (!hasCurrentLocation
                        || distanceMeters(
                                currentLatitude,
                                currentLongitude,
                                candidate.latitude,
                                candidate.longitude
                        ) <= MAX_HINT_DISTANCE_M) {
                    hintCandidates.add(candidate);
                }
            }
        }

        int inserted = repository.importDiscoveries(newlyDiscovered);

        List<DiscoveryEntity> discovered = repository.loadDiscoveriesInBounds(
                south,
                north,
                west,
                east,
                MAX_DISCOVERED_MARKERS
        );

        Comparator<PoiDiscoveryCandidate> hintOrder = (a, b) -> {
            int scoreCompare = Integer.compare(b.score, a.score);
            if (scoreCompare != 0) return scoreCompare;

            if (hasCurrentLocation) {
                double da = distanceMeters(
                        currentLatitude,
                        currentLongitude,
                        a.latitude,
                        a.longitude
                );
                double db = distanceMeters(
                        currentLatitude,
                        currentLongitude,
                        b.latitude,
                        b.longitude
                );
                int distanceCompare = Double.compare(da, db);
                if (distanceCompare != 0) return distanceCompare;
            }

            return a.discoveryId.compareTo(b.discoveryId);
        };
        hintCandidates.sort(hintOrder);

        if (hintCandidates.size() > MAX_HINTS) {
            hintCandidates = new ArrayList<>(hintCandidates.subList(0, MAX_HINTS));
        }

        return new Result(discovered, hintCandidates, inserted);
    }

    private static boolean inside(
            double latitude,
            double longitude,
            double south,
            double north,
            double west,
            double east
    ) {
        return latitude >= south
                && latitude <= north
                && longitude >= west
                && longitude <= east;
    }

    public static double distanceMeters(
            double lat1,
            double lon1,
            double lat2,
            double lon2
    ) {
        double earthRadiusM = 6_371_008.8;
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLambda = Math.toRadians(lon2 - lon1);

        double a = Math.sin(dPhi / 2.0) * Math.sin(dPhi / 2.0)
                + Math.cos(phi1) * Math.cos(phi2)
                * Math.sin(dLambda / 2.0) * Math.sin(dLambda / 2.0);
        return earthRadiusM * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
    }

    private static final class CandidateWithCell {
        @NonNull final PoiDiscoveryCandidate candidate;
        @NonNull final String h3;

        CandidateWithCell(
                @NonNull PoiDiscoveryCandidate candidate,
                @NonNull String h3
        ) {
            this.candidate = candidate;
            this.h3 = h3;
        }
    }

    public static final class Result {
        @NonNull public final List<DiscoveryEntity> discovered;
        @NonNull public final List<PoiDiscoveryCandidate> hints;
        public final int newlyDiscovered;

        Result(
                @NonNull List<DiscoveryEntity> discovered,
                @NonNull List<PoiDiscoveryCandidate> hints,
                int newlyDiscovered
        ) {
            this.discovered = discovered;
            this.hints = hints;
            this.newlyDiscovered = newlyDiscovered;
        }
    }
}
