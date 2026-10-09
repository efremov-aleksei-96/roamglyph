package com.sensareth.roamglyph.map;

import androidx.annotation.NonNull;

import com.uber.h3core.H3Core;
import com.uber.h3core.util.LatLng;

import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.Point;
import org.maplibre.geojson.Polygon;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class ViewportOverlayBuilder {
    public static final int MAX_VIEWPORT_CELLS = 8_000;
    private static final int SOURCE_RESOLUTION = 13;
    private static final int MIN_RENDER_RESOLUTION = 3;
    private static final double VIEWPORT_PADDING_FRACTION = 0.18;

    private ViewportOverlayBuilder() {
    }

    /**
     * Uses exact res-13 cells at normal street-level zoom and progressively coarser
     * parents only when zooming out. This preserves honest reveal geometry close up
     * while keeping the overlay bounded at city/region scales.
     */
    public static int resolutionForZoom(double zoom) {
        if (zoom >= 17.0) return 13;
        if (zoom >= 15.5) return 12;
        if (zoom >= 14.0) return 11;
        if (zoom >= 12.5) return 10;
        if (zoom >= 11.0) return 9;
        if (zoom >= 9.5) return 8;
        if (zoom >= 8.0) return 7;
        if (zoom >= 6.5) return 6;
        if (zoom >= 5.0) return 5;
        if (zoom >= 3.5) return 4;
        if (zoom >= 2.5) return 3;
        return -1;
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
        int resolution = resolutionForZoom(zoom);
        if (resolution < 0 || !validBounds(north, east, south, west)) {
            return Result.empty();
        }

        Bounds padded = paddedBounds(north, east, south, west);
        List<LatLng> viewport = new ArrayList<>(4);
        viewport.add(new LatLng(padded.north, padded.west));
        viewport.add(new LatLng(padded.south, padded.west));
        viewport.add(new LatLng(padded.south, padded.east));
        viewport.add(new LatLng(padded.north, padded.east));

        List<String> candidateCells;
        while (true) {
            candidateCells = h3.polygonToCellAddresses(
                    viewport,
                    null,
                    resolution
            );

            if (candidateCells.size() <= MAX_VIEWPORT_CELLS
                    || resolution <= MIN_RENDER_RESOLUTION) {
                break;
            }

            resolution--;
        }

        if (candidateCells.size() > MAX_VIEWPORT_CELLS) {
            return Result.empty();
        }

        Set<String> exploredAtResolution =
                coverage.cellsAtResolution(h3, resolution);

        List<Feature> explored = new ArrayList<>();
        List<Feature> fog = new ArrayList<>();

        for (String cell : candidateCells) {
            Feature feature = featureForCell(h3, cell);
            if (feature == null) continue;

            if (exploredAtResolution.contains(cell)) {
                explored.add(feature);
            } else {
                fog.add(feature);
            }
        }

        return new Result(
                FeatureCollection.fromFeatures(explored.toArray(new Feature[0])),
                FeatureCollection.fromFeatures(fog.toArray(new Feature[0])),
                resolution,
                candidateCells.size()
        );
    }

    private static Bounds paddedBounds(
            double north,
            double east,
            double south,
            double west
    ) {
        double latitudeSpan = north - south;
        double longitudeSpan = east - west;

        double latitudePadding = latitudeSpan * VIEWPORT_PADDING_FRACTION;
        double longitudePadding = longitudeSpan * VIEWPORT_PADDING_FRACTION;

        return new Bounds(
                Math.min(89.999999, north + latitudePadding),
                Math.min(180.0, east + longitudePadding),
                Math.max(-89.999999, south - latitudePadding),
                Math.max(-180.0, west - longitudePadding)
        );
    }

    private static boolean validBounds(
            double north,
            double east,
            double south,
            double west
    ) {
        return Double.isFinite(north)
                && Double.isFinite(east)
                && Double.isFinite(south)
                && Double.isFinite(west)
                && north > south
                && north <= 90.0
                && south >= -90.0
                && east > west
                && east <= 180.0
                && west >= -180.0
                && east - west <= 180.0;
    }

    private static Feature featureForCell(H3Core h3, String cell) {
        try {
            List<LatLng> boundary = h3.cellToBoundary(cell);
            List<Point> ring = new ArrayList<>(boundary.size() + 1);

            for (LatLng coordinate : boundary) {
                ring.add(Point.fromLngLat(coordinate.lng, coordinate.lat));
            }

            if (ring.isEmpty()) return null;
            ring.add(ring.get(0));

            List<List<Point>> rings = new ArrayList<>(1);
            rings.add(ring);
            return Feature.fromGeometry(Polygon.fromLngLats(rings));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static final class Bounds {
        final double north;
        final double east;
        final double south;
        final double west;

        Bounds(double north, double east, double south, double west) {
            this.north = north;
            this.east = east;
            this.south = south;
            this.west = west;
        }
    }

    public static final class Result {
        @NonNull public final FeatureCollection explored;
        @NonNull public final FeatureCollection fog;
        public final int resolution;
        public final int candidateCells;

        Result(
                @NonNull FeatureCollection explored,
                @NonNull FeatureCollection fog,
                int resolution,
                int candidateCells
        ) {
            this.explored = explored;
            this.fog = fog;
            this.resolution = resolution;
            this.candidateCells = candidateCells;
        }

        static Result empty() {
            FeatureCollection empty =
                    FeatureCollection.fromFeatures(new Feature[]{});
            return new Result(empty, empty, -1, 0);
        }
    }
}
