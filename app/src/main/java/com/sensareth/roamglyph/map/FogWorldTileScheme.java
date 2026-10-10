package com.sensareth.roamglyph.map;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Stable geographic raster grid. Tile identities and pixel coordinates
 * never depend on the transient Android screen viewport.
 */
public final class FogWorldTileScheme {
    public static final int TILE_PX = 384;

    /**
     * Two simultaneous visible zoom generations must fit a 64MiB cache.
     * Reserve 35% of the budget for one full visible set, leaving a
     * 30% allowance for halo tiles, intermediates and LRU hysteresis.
     */
    public static float rasterScaleForVisibleTiles(int count) {
        int tiles = Math.max(1, count);
        double approximateBaseBytes = (double) TILE_PX * TILE_PX * 4.0 * 1.34;
        double maxGenerationBytes = 64.0 * 1024 * 1024 * 0.35;
        double scale = Math.sqrt(maxGenerationBytes /
                (tiles * approximateBaseBytes));
        return (float) Math.max(0.20, Math.min(1.0, scale));
    }

    private static final double MAX_LAT = 85.05112878;

    private FogWorldTileScheme() {}

    public static final class Key {
        public final int z;
        public final int x;
        public final int y;

        public Key(int z, int x, int y) {
            this.z = z;
            this.x = x;
            this.y = y;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key value = (Key) other;
            return z == value.z && x == value.x && y == value.y;
        }

        @Override public int hashCode() { return Objects.hash(z, x, y); }

        @Override public String toString() { return z + "/" + x + "/" + y; }
    }

    public static int zoomLevel(double zoom) {
        if (!Double.isFinite(zoom)) return 0;
        return Math.max(0, Math.min(20, (int) Math.round(zoom)));
    }

    public static double longitudeX(double longitude, int zoom) {
        return (longitude + 180.0) / 360.0 * TILE_PX * (1 << zoom);
    }

    public static double latitudeY(double latitude, int zoom) {
        double lat = Math.max(-MAX_LAT, Math.min(MAX_LAT, latitude));
        double sin = Math.sin(Math.toRadians(lat));
        return (0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI))
                * TILE_PX * (1 << zoom);
    }

    public static double longitudeForX(double x, int zoom) {
        return x / (TILE_PX * (double) (1 << zoom)) * 360.0 - 180.0;
    }

    public static double latitudeForY(double y, int zoom) {
        double mercator = Math.PI *
                (1 - 2 * y / (TILE_PX * (double) (1 << zoom)));
        return Math.toDegrees(Math.atan(Math.sinh(mercator)));
    }

    public static double west(Key key) {
        return longitudeForX((double) key.x * TILE_PX, key.z);
    }

    public static double east(Key key) {
        return longitudeForX(((double) key.x + 1) * TILE_PX, key.z);
    }

    public static double north(Key key) {
        return latitudeForY((double) key.y * TILE_PX, key.z);
    }

    public static double south(Key key) {
        return latitudeForY(((double) key.y + 1) * TILE_PX, key.z);
    }

    public static float localX(double lng, Key key) {
        return (float) (longitudeX(lng, key.z) - (double) key.x * TILE_PX);
    }

    public static float localY(double lat, Key key) {
        return (float) (latitudeY(lat, key.z) - (double) key.y * TILE_PX);
    }

    /**
     * Return nearest tiles first: visible tiles, then one-tile prefetch halo.
     * Reject the longitude seam rather than mapping the wrong hemisphere.
     */
    public static List<Key> covering(double north, double east,
                                     double south, double west, int zoom,
                                     int halo, int maxTiles) {
        List<Key> result = new ArrayList<>();
        if (!Double.isFinite(north) || !Double.isFinite(east)
                || !Double.isFinite(south) || !Double.isFinite(west)
                || north <= south || east <= west || east - west > 180
                || maxTiles <= 0) return result;
        int z = Math.max(0, Math.min(20, zoom));
        int last = (1 << z) - 1;
        int left = Math.max(0, (int) Math.floor(longitudeX(west, z) / TILE_PX) - halo);
        int right = Math.min(last, (int) Math.floor(longitudeX(east, z) / TILE_PX) + halo);
        int top = Math.max(0, (int) Math.floor(latitudeY(north, z) / TILE_PX) - halo);
        int bottom = Math.min(last, (int) Math.floor(latitudeY(south, z) / TILE_PX) + halo);
        if (right < left || bottom < top) return result;
        double cx = (left + right) * 0.5;
        double cy = (top + bottom) * 0.5;
        // Limit generation before sorting so pathological zoom requests
        // cannot allocate millions of candidate tile keys.
        if ((long) (right - left + 1) * (bottom - top + 1) > 600) {
            left = Math.max(left, (int) Math.floor(cx - 12));
            right = Math.min(right, (int) Math.ceil(cx + 12));
            top = Math.max(top, (int) Math.floor(cy - 12));
            bottom = Math.min(bottom, (int) Math.ceil(cy + 12));
        }
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) result.add(new Key(z, x, y));
        }
        result.sort(Comparator.comparingDouble(
                k -> Math.pow(k.x - cx, 2) + Math.pow(k.y - cy, 2)));
        if (result.size() > maxTiles) {
            return new ArrayList<>(result.subList(0, maxTiles));
        }
        return result;
    }
}
