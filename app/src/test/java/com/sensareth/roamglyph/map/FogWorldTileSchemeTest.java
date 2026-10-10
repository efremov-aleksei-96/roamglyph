package com.sensareth.roamglyph.map;

import org.junit.Test;
import java.util.List;

import static org.junit.Assert.*;

public final class FogWorldTileSchemeTest {
    @Test public void coordinatesRoundTripInYerevanAtStreetAndCityZoom() {
        for (int zoom : new int[]{9, 13, 16, 19, 20}) {
            double lon = 44.5152, lat = 40.1872;
            double x = FogWorldTileScheme.longitudeX(lon, zoom);
            double y = FogWorldTileScheme.latitudeY(lat, zoom);
            assertEquals(lon, FogWorldTileScheme.longitudeForX(x, zoom), 1e-9);
            assertEquals(lat, FogWorldTileScheme.latitudeForY(y, zoom), 1e-9);
        }
    }

    @Test public void geographicTilesKeepSameIdentityWhileViewportMoves() {
        List<FogWorldTileScheme.Key> a = FogWorldTileScheme.covering(
                40.193, 44.521, 40.182, 44.510, 16, 1, 34);
        List<FogWorldTileScheme.Key> b = FogWorldTileScheme.covering(
                40.192, 44.522, 40.181, 44.511, 16, 1, 34);
        assertFalse(a.isEmpty());
        assertFalse(b.isEmpty());
        assertTrue("Panning must reuse world-anchored tiles", a.stream().anyMatch(b::contains));
    }

    @Test public void neighboursJoinWithoutGapsAndDoNotDependOnTheScreen() {
        FogWorldTileScheme.Key k = FogWorldTileScheme.covering(
                40.193, 44.521, 40.182, 44.510, 17, 0, 34).get(0);
        FogWorldTileScheme.Key east = new FogWorldTileScheme.Key(k.z, k.x + 1, k.y);
        FogWorldTileScheme.Key south = new FogWorldTileScheme.Key(k.z, k.x, k.y + 1);
        assertEquals(FogWorldTileScheme.east(k), FogWorldTileScheme.west(east), 1e-10);
        assertEquals(FogWorldTileScheme.south(k), FogWorldTileScheme.north(south), 1e-10);
        assertEquals(0, FogWorldTileScheme.localX(FogWorldTileScheme.west(k), k), 0.005);
        assertEquals(FogWorldTileScheme.TILE_PX,
                FogWorldTileScheme.localX(FogWorldTileScheme.east(k), k), 0.005);
    }

    @Test public void tilesAtAllZoomLevelsHaveBoundedRequestCount() {
        for (int z = 1; z <= 20; z++) {
            List<FogWorldTileScheme.Key> keys = FogWorldTileScheme.covering(
                    40.23, 44.55, 40.13, 44.45, z, 1, 34);
            assertTrue(keys.size() <= 34);
        }
    }

    @Test public void giantViewportFitsTwoVisibleZoomGenerationsInCache() {
        for (int visible : new int[]{1, 16, 32, 64, 128, 256, 384, 512}) {
            float scale = FogWorldTileScheme.rasterScaleForVisibleTiles(visible);
            assertTrue(scale >= 0.20f && scale <= 1.0f);
            // Conservative upper bound for the complete mip pyramid,
            // two visible zoom generations and a 30% remaining cache reserve.
            double bytes = visible * 2.0
                    * Math.ceil(FogWorldTileScheme.TILE_PX * scale)
                    * Math.ceil(FogWorldTileScheme.TILE_PX * scale)
                    * 4.0 * 1.34;
            assertTrue("Visible zoom handoff must remain below 64MiB at " + visible,
                    bytes < 64.0 * 1024 * 1024 * 0.78);
        }
    }

    @Test public void hugeViewportDowngradesTileZoomInsteadOfDroppingVisibleRoads() {
        double north = 40.25, east = 44.60, south = 40.12, west = 44.40;
        int z = FogWorldTileScheme.budgetedZoom(
                north, east, south, west, 20, 96);
        assertTrue("Huge view must use bigger geographic tiles", z < 20);
        List<FogWorldTileScheme.Key> allVisible =
                FogWorldTileScheme.covering(north, east, south, west, z, 0, 96);
        List<FogWorldTileScheme.Key> uncapped =
                FogWorldTileScheme.covering(north, east, south, west, z, 0, 512);
        assertEquals("Every visible tile must fit, not center-crop",
                uncapped.size(), allVisible.size());
        assertTrue(allVisible.size() <= 96);
    }

    @Test public void normalPhoneViewKeepsGeographicTileZoom() {
        double north = 40.189, east = 44.518, south = 40.186, west = 44.513;
        int zoom = FogWorldTileScheme.budgetedZoom(
                north, east, south, west, 17, 96);
        assertEquals("Do not degrade ordinary map detail", 17, zoom);
    }

    @Test public void oldZoomEdgeTileIsNotDiscardedByCappedEnumeration() {
        double north = 40.30, east = 44.80, south = 39.90, west = 44.30;
        int z = 20;
        FogWorldTileScheme.Key edge = new FogWorldTileScheme.Key(z,
                (int) Math.floor(FogWorldTileScheme.longitudeX(44.31, z)
                        / FogWorldTileScheme.TILE_PX),
                (int) Math.floor(FogWorldTileScheme.latitudeY(40.29, z)
                        / FogWorldTileScheme.TILE_PX));
        assertTrue("An old cached tile near the screen corner is visible",
                FogWorldTileScheme.intersectsBounds(
                        edge, north, east, south, west));
        assertFalse("The previous center-limited key enumeration missed it",
                FogWorldTileScheme.covering(north, east, south, west,
                        z, 0, 512).contains(edge));
        assertFalse(FogWorldTileScheme.intersectsBounds(
                new FogWorldTileScheme.Key(z, 0, 0),
                north, east, south, west));
    }

    @Test public void unsupportedAntimeridianViewFailsDark() {
        assertTrue(FogWorldTileScheme.covering(
                40.2, -179.8, 40.1, 179.8, 16, 1, 34).isEmpty());
    }
}
