package com.sensareth.roamglyph.map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FogFeatherProfileTest {
    @Test
    public void unvisitedPixelAlwaysRemainsFullyFogged() {
        assertEquals(0, FogFeatherProfile.cutoutAlpha(0, 255, 255, 24));
    }

    @Test
    public void exteriorOrZeroDistanceNeverUncoversMap() {
        assertEquals(0, FogFeatherProfile.cutoutAlpha(255, 255, 0, 24));
        assertEquals(0, FogFeatherProfile.cutoutAlpha(255, 0, 0, 24));
    }

    @Test
    public void cannotIncreaseOriginalCoverageAlpha() {
        for (int source = 0; source <= 255; source++) {
            for (int d = 0; d <= 255; d += 17) {
                int result = FogFeatherProfile.cutoutAlpha(source, 255, d, 24);
                assertTrue(result >= 0);
                assertTrue("Feather must not reveal unvisited pixels",
                        result <= source);
            }
        }
    }

    @Test
    public void gradientProgressesSmoothlyInward() {
        int previous = 0;
        for (int d = 0; d <= 255; d += 3) {
            int result = FogFeatherProfile.cutoutAlpha(255, 255, d, 24);
            assertTrue("No opaque rings or alpha reversals", result >= previous);
            previous = result;
        }
        assertEquals(255, previous);
    }

    @Test
    public void onePixelWideRouteSurvivesWideZoomFeather() {
        // One sample inside an exact H3 cell: 3 chamfer steps. At wide
        // zoom the pixel-aware feather is one raster pixel, not 20+ dp.
        int result = FogFeatherProfile.cutoutAlpha(255, 40, 3, 1);
        assertTrue(result > 0);
        assertTrue(result < 255);
    }

    @Test
    public void narrowExploredAreaRemainsSubtlyVisible() {
        int result = FogFeatherProfile.cutoutAlpha(255, 40, 6, 2);
        assertTrue(result > 0);
        assertTrue(result < 255);
    }
}
