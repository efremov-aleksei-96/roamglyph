package com.sensareth.roamglyph.map;

import androidx.annotation.NonNull;

import com.uber.h3core.H3Core;
import com.uber.h3core.util.LatLng;

import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.Point;
import org.maplibre.geojson.Polygon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * World-covering inverted fog masks: the map is NEVER temporarily exposed
 * during a pan, since every fog source always contains the global dark shape.
 * Only already explored H3 cells are subtracted as transparent holes.
 */
public final class ViewportOverlayBuilder {
    public static final int MAX_RENDERED_CELLS = 8_000;
    private static final int SOURCE_RESOLUTION = 13;
    private static final int MIN_RENDER_RESOLUTION = 0;
    private static final double VIEWPORT_PADDING_FRACTION = 0.35;

    private ViewportOverlayBuilder() {
    }

    /**
     * Keep authoritative res-13 detail until a cell is approximately one
     * physical map pixel across. Only then step to the smallest H3 parent
     * that remains visible. This differs from zoom-band aggregation, which
     * produced visibly oversized hexagons.
     */
    public static int resolutionForZoom(double zoom) {
        return resolutionForZoom(zoom, 40.0);
    }

    public static int resolutionForZoom(double zoom, double latitude) {
        if (!Double.isFinite(zoom) || !Double.isFinite(latitude)) return SOURCE_RESOLUTION;
        double lat = Math.max(-85.0, Math.min(85.0, latitude));
        double metresPerPixel = 156543.03392 * Math.cos(Math.toRadians(lat))
                / Math.pow(2.0, Math.max(0.0, zoom));
        double diameter = 8.2; // approximate res-13 cell diameter
        int resolution = SOURCE_RESOLUTION;
        while (resolution > MIN_RENDER_RESOLUTION && diameter < metresPerPixel) {
            resolution--;
            diameter *= Math.sqrt(7.0);
        }
        return resolution;
    }

    /** Full-world dark mask, including while no Room history has loaded yet. */
    @NonNull
    public static FeatureCollection initialFog() {
        return globalMask();
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
        if (!validBounds(north, east, south, west)) return Result.dark();

        double latitude = (north + south) / 2.0;
        int resolution = resolutionForZoom(zoom, latitude);
        Bounds padded = paddedBounds(north, east, south, west);
        Set<String> visibleExplored;
        Set<String> band1;
        Set<String> band2;

        // All THREE masks must remain bounded. Checking only the visited set
        // is not enough: sparse cells can generate up to 7 new neighbours each
        // on expansion, then many more in the outer band.
        //
        // Coarsening is a performance-only escape hatch and never touches Room.
        while (true) {
            visibleExplored = coverage.cellsInBounds(
                    h3, resolution,
                    padded.north, padded.east, padded.south, padded.west,
                    MAX_RENDERED_CELLS
            );
            if (visibleExplored.size() > MAX_RENDERED_CELLS) {
                if (resolution == MIN_RENDER_RESOLUTION) return Result.dark();
                resolution--;
                continue;
            }

            band1 = expandBounded(h3, visibleExplored, MAX_RENDERED_CELLS);
            if (band1.size() > MAX_RENDERED_CELLS) {
                if (resolution == MIN_RENDER_RESOLUTION) return Result.dark();
                resolution--;
                continue;
            }

            band2 = expandBounded(h3, band1, MAX_RENDERED_CELLS);
            if (band2.size() > MAX_RENDERED_CELLS) {
                if (resolution == MIN_RENDER_RESOLUTION) return Result.dark();
                resolution--;
                continue;
            }
            break;
        }

        if (visibleExplored.isEmpty()) {
            FeatureCollection global = globalMask();
            return new Result(global, global, global, resolution, 0);
        }

        // Three bounded, nested masks form a gentle gradient rather than
        // visible tile borders.
        return new Result(
                invertedMask(h3, visibleExplored),
                invertedMask(h3, band1),
                invertedMask(h3, band2),
                resolution,
                visibleExplored.size()
        );
    }

    @NonNull
    private static Set<String> expandBounded(
            H3Core h3, Set<String> cells, int maxResults
    ) {
        Set<String> expanded = new HashSet<>(cells);
        for (String cell : cells) {
            if (expanded.size() > maxResults) break;
            try {
                expanded.addAll(h3.gridDisk(cell, 1));
            } catch (RuntimeException ignored) {
                // Pentagons or malformed legacy cells cannot remove global fog.
            }
        }
        return expanded;
    }

    /**
     * GeoJSON outer ring covers the world. A union of H3 polygons becomes
     * interior holes, avoiding per-hexagon drawn borders. Any islands inside
     * explored loops are emitted as separate dark polygons.
     */
    @NonNull
    private static FeatureCollection invertedMask(H3Core h3, Set<String> revealed) {
        if (revealed.isEmpty()) return globalMask();

        List<List<Point>> worldWithHoles = new ArrayList<>();
        worldWithHoles.add(worldRing());

        List<Feature> islands = new ArrayList<>();
        try {
            List<List<List<LatLng>>> merged =
                    h3.cellAddressesToMultiPolygon(revealed, true);
            for (List<List<LatLng>> polygon : merged) {
                if (polygon.isEmpty()) continue;
                List<Point> exterior = ring(polygon.get(0));
                if (exterior == null) continue;
                // H3 GeoJSON exterior rings are counterclockwise; invert the
                // winding when using them as holes in our dark world polygon.
                Collections.reverse(exterior);
                worldWithHoles.add(exterior);

                // H3 interior rings have hole winding; reverse them back to
                // exterior winding when rendering unexplored islands.
                for (int i = 1; i < polygon.size(); i++) {
                    List<Point> island = ring(polygon.get(i));
                    if (island != null) {
                        Collections.reverse(island);
                        islands.add(Feature.fromGeometry(
                                Polygon.fromLngLats(Collections.singletonList(island))
                        ));
                    }
                }
            }
        } catch (RuntimeException error) {
            // Never reveal unknown geography when a geometry conversion fails.
            return globalMask();
        }

        List<Feature> features = new ArrayList<>(1 + islands.size());
        features.add(Feature.fromGeometry(Polygon.fromLngLats(worldWithHoles)));
        features.addAll(islands);
        return FeatureCollection.fromFeatures(features.toArray(new Feature[0]));
    }

    private static List<Point> ring(List<LatLng> vertices) {
        if (vertices.size() < 3) return null;
        List<Point> points = new ArrayList<>(vertices.size() + 1);
        for (LatLng vertex : vertices) {
            // Defer polar/dateline cases rather than constructing invalid holes.
            if (!Double.isFinite(vertex.lat) || !Double.isFinite(vertex.lng)
                    || Math.abs(vertex.lat) >= 85.0
                    || Math.abs(vertex.lng) >= 179.9) return null;
            points.add(Point.fromLngLat(vertex.lng, vertex.lat));
        }
        if (points.isEmpty()) return null;
        points.add(points.get(0));
        return points;
    }

    private static FeatureCollection globalMask() {
        Feature world = Feature.fromGeometry(
                Polygon.fromLngLats(Collections.singletonList(worldRing()))
        );
        return FeatureCollection.fromFeature(world);
    }

    private static List<Point> worldRing() {
        List<Point> points = new ArrayList<>(5);
        points.add(Point.fromLngLat(-179.999, -85.0));
        points.add(Point.fromLngLat(179.999, -85.0));
        points.add(Point.fromLngLat(179.999, 85.0));
        points.add(Point.fromLngLat(-179.999, 85.0));
        points.add(points.get(0));
        return points;
    }

    private static Bounds paddedBounds(
            double north, double east, double south, double west
    ) {
        double latitudePad = (north - south) * VIEWPORT_PADDING_FRACTION;
        double longitudePad = (east - west) * VIEWPORT_PADDING_FRACTION;
        return new Bounds(
                Math.min(85.0, north + latitudePad),
                Math.min(179.9, east + longitudePad),
                Math.max(-85.0, south - latitudePad),
                Math.max(-179.9, west - longitudePad)
        );
    }

    private static boolean validBounds(
            double north, double east, double south, double west
    ) {
        return Double.isFinite(north)
                && Double.isFinite(east)
                && Double.isFinite(south)
                && Double.isFinite(west)
                && north > south
                && north <= 85.0
                && south >= -85.0
                && east > west
                && east <= 180.0
                && west >= -180.0
                && east - west <= 180.0;
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
        @NonNull public final FeatureCollection fog;
        @NonNull public final FeatureCollection fogMid;
        @NonNull public final FeatureCollection fogFar;
        public final int resolution;
        public final int candidateCells;

        Result(
                @NonNull FeatureCollection fog,
                @NonNull FeatureCollection fogMid,
                @NonNull FeatureCollection fogFar,
                int resolution,
                int candidateCells
        ) {
            this.fog = fog;
            this.fogMid = fogMid;
            this.fogFar = fogFar;
            this.resolution = resolution;
            this.candidateCells = candidateCells;
        }

        static Result dark() {
            FeatureCollection world = globalMask();
            return new Result(world, world, world, -1, 0);
        }
    }
}
