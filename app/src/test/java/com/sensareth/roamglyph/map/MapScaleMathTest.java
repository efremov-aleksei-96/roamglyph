package com.sensareth.roamglyph.map;

import org.junit.Test;
import static org.junit.Assert.*;

public class MapScaleMathTest {
    @Test public void greatCircleUsesMetersWithoutLocationPermission() {
        assertEquals(0.0, MapScaleMath.distanceMeters(40.0, 44.0, 40.0, 44.0), .001);
        assertEquals(111195.0, MapScaleMath.distanceMeters(0, 0, 0, 1), 80.0);
        assertEquals(111195.0, MapScaleMath.distanceMeters(0, 179.5, 0, -179.5), 80.0);
        assertTrue(Double.isNaN(MapScaleMath.distanceMeters(95.0, 0, 0, 0)));
    }

    @Test public void niceScaleNeverExceedsReferenceDistance() {
        for (double reference : new double[]{0.2, 1.2, 9.7, 28, 109, 550, 2500, 200000}) {
            double chosen = MapScaleMath.niceDistanceMeters(reference);
            assertTrue(chosen > 0.0);
            assertTrue(chosen <= reference);
            assertTrue(chosen >= reference / 5.0);
        }
        assertEquals(50, MapScaleMath.niceDistanceMeters(78), 0.001);
        assertEquals(200, MapScaleMath.niceDistanceMeters(499), 0.001);
        assertEquals(500, MapScaleMath.niceDistanceMeters(710), 0.001);
        assertTrue(Double.isNaN(MapScaleMath.niceDistanceMeters(0)));
    }

    @Test public void rulerLabelsUseCompactMetricUnits() {
        assertEquals("50 m", MapScaleMath.label(50));
        assertEquals("2 km", MapScaleMath.label(2000));
        assertEquals("500 m", MapScaleMath.label(500));
        assertEquals("50 cm", MapScaleMath.label(.5));
        assertEquals("—", MapScaleMath.label(Double.NaN));
    }
}
