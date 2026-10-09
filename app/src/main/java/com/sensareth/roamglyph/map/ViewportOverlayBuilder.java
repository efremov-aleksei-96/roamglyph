package com.sensareth.roamglyph.map;

import androidx.annotation.NonNull;

import com.uber.h3core.H3Core;
import com.uber.h3core.util.LatLng;

import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.Point;
import org.maplibre.geojson.Polygon;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ViewportOverlayBuilder {
    public static final double MIN_FOG_ZOOM = 14.5;
    public static final int MAX_VIEWPORT_CELLS = 8_000;
    private static final int SOURCE_RESOLUTION = 13;
    private static final int MIN_RENDER_RESOLUTION = 8;

    private ViewportOverlayBuilder() {
    }

    public static int resolutionForZoom(double zoom) {
        if (zoom >= 19.0) return 13;
        if (zoom >= 18.0) return 12;
        if (zoom >= 16.5) return 11;
        if (zoom >= MIN_FOG_ZOOM) return 10;
        return -1;
    }

    @NonNull
    public static Result build(
            @NonNull H3Core h3,
            @NonNull Set<String> visitedResolution13,
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

        List<LatLng> viewport = new ArrayList<>(4);
        viewport.add(new LatLng(north, west));
        viewport.add(new LatLng(south, west));
        viewport.add(new LatLng(south, east));
        viewport.add(new LatLng(north, east));

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

        Set<String> exploredAtResolution = new HashSet<>();
        if (resolution == SOURCE_RESOLUTION) {
            exploredAtResolution.addAll(visitedResolution13);
        } else {
            for (String cell : visitedResolution13) {
                try {
                    exploredAtResolution.add(
                            h3.cellToParentAddress(cell, resolution)
                    );
                } catch (RuntimeException ignored) {
                    // Malformed legacy/imported cells should not break map rendering.
                }
            }
        }

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
