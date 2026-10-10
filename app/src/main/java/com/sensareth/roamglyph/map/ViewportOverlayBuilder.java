package com.sensareth.roamglyph.map;

import androidx.annotation.NonNull;

import com.uber.h3core.H3Core;
import com.uber.h3core.util.LatLng;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Converts only authoritative res-13 H3 cells into merged geographic polygons.
 * Zoom MUST NOT select parent cells: a single partially explored parent would
 * falsely reveal as much as seven times the user's actual coverage.
 *
 * Expensive native polygon work runs on the existing serialized overlay worker.
 * If the viewport exceeds the limit, the renderer fails dark rather than
 * inflating exploration.
 */
public final class ViewportOverlayBuilder {
    public static final int SOURCE_RESOLUTION = 13;
    public static final int MAX_RENDERED_CELLS = 20_000;
    private static final double VIEWPORT_PADDING = 0.6;

    private ViewportOverlayBuilder() {
    }

    public static int resolutionForZoom(double zoom) {
        // Canonical stored H3 accuracy is invariant under camera zoom.
        return SOURCE_RESOLUTION;
    }

    public static boolean isExactCoverage(int resolution) {
        return resolution == SOURCE_RESOLUTION;
    }

    @NonNull
    public static Result build(
            @NonNull H3Core h3,
            @NonNull ExplorationCoverageIndex coverage,
            double north,
            double east,
            double south,
            double west,
            double zoom
    ) {
        if (!Double.isFinite(zoom) || !validBounds(north, east, south, west)) {
            return Result.dark();
        }

        double latPadding = (north - south) * VIEWPORT_PADDING;
        double lngPadding = (east - west) * VIEWPORT_PADDING;
        Set<String> cells = coverage.cellsInBounds(
                h3,
                SOURCE_RESOLUTION,
                Math.min(85.0, north + latPadding),
                Math.min(180.0, east + lngPadding),
                Math.max(-85.0, south - latPadding),
                Math.max(-180.0, west - lngPadding),
                MAX_RENDERED_CELLS
        );

        if (!withinGeometryBudget(cells.size())) return Result.dark();
        if (cells.isEmpty()) return new Result(Collections.emptyList(), 0);

        try {
            // Merge boundaries only. This operation is topologically exact:
            // all input cells retain their native res-13 footprints.
            List<List<List<LatLng>>> polygons =
                    h3.cellAddressesToMultiPolygon(cells, true);
            return new Result(polygons, cells.size());
        } catch (RuntimeException error) {
            return Result.dark();
        }
    }

    public static boolean withinGeometryBudget(int exactCellCount) {
        return exactCellCount >= 0 && exactCellCount <= MAX_RENDERED_CELLS;
    }

    private static boolean validBounds(
            double north, double east, double south, double west
    ) {
        return Double.isFinite(north) && Double.isFinite(east)
                && Double.isFinite(south) && Double.isFinite(west)
                && north > south && north <= 85.0 && south >= -85.0
                && east > west && east <= 180.0 && west >= -180.0
                && east - west <= 180.0;
    }

    public static final class Result {
        @NonNull public final List<List<List<LatLng>>> polygons;
        public final int exactCellCount;

        Result(@NonNull List<List<List<LatLng>>> polygons, int exactCellCount) {
            this.polygons = polygons;
            this.exactCellCount = exactCellCount;
        }

        public static Result dark() {
            return new Result(Collections.emptyList(), 0);
        }
    }
}
