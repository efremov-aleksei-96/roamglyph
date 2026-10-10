package com.sensareth.roamglyph.map;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class DiscoveryEngineTest {
    @Test
    public void distanceMetersIsReasonable() {
        double distance = DiscoveryEngine.distanceMeters(
                40.1800,
                44.5100,
                40.1810,
                44.5100
        );
        assertTrue(distance > 100.0);
        assertTrue(distance < 120.0);
    }
}
