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
    public void isolatedHexProtrusionsRemainAlmostDark() {
        // Low-pass neighbourhood coverage is small at the tips of a
        // sawtooth, even though the source pixel is technically visited.
        int tip = FogFeatherProfile.cutoutAlpha(255, 45, 12, 12);
        int solid = FogFeatherProfile.cutoutAlpha(255, 255, 12, 12);
        assertTrue("H3 corners should be strongly de-emphasized", tip < solid / 2);
    }

    @Test
    public void broadInteriorIsClearWhileBorderFadesContinuously() {
        int edge = FogFeatherProfile.cutoutAlpha(255, 255, 3, 24);
        int halfway = FogFeatherProfile.cutoutAlpha(255, 255, 36, 24);
        int interior = FogFeatherProfile.cutoutAlpha(255, 255, 180, 24);
        assertTrue(edge < halfway);
        assertTrue(halfway < interior);
        assertEquals(255, interior);
    }

    @Test
    public void oneCellWideCorridorKeepsVisibleCenterDuringBroadFade() {
        int middle = FogFeatherProfile.cutoutAlpha(255, 105, 30, 38);
        int edge = FogFeatherProfile.cutoutAlpha(255, 105, 3, 38);
        assertTrue("Narrow trail should not vanish at typical street zoom",
                middle >= 20);
        assertTrue("Outer H3 tooth should remain darker", edge < middle / 3);
    }

    @Test
    public void partiallyCoveredEdgeCannotGainOpacityFromBlur() {
        assertEquals(0, FogFeatherProfile.cutoutAlpha(0, 255, 255, 24));
        assertTrue(FogFeatherProfile.cutoutAlpha(31, 255, 255, 24) <= 31);
    }

    @Test
    public void narrowExploredAreaRemainsSubtlyVisible() {
        int result = FogFeatherProfile.cutoutAlpha(255, 40, 6, 2);
        assertTrue(result > 0);
        assertTrue(result < 255);
    }
}
