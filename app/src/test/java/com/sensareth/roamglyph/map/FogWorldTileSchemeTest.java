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

    @Test public void unsupportedAntimeridianViewFailsDark() {
        assertTrue(FogWorldTileScheme.covering(
                40.2, -179.8, 40.1, 179.8, 16, 1, 34).isEmpty());
    }
}
