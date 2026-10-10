package com.sensareth.roamglyph.map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FogMipLevelTest {
    @Test public void unchangedAndZoomInKeepExactSourceResolution() {
        assertEquals(0, FogMipLevel.forZoomDelta(0, 11));
        assertEquals(0, FogMipLevel.forZoomDelta(-5, 11));
    }

    @Test public void zoomOutPicksPrefilteredResolution() {
        assertEquals(0, FogMipLevel.forZoomDelta(0.25, 11));
        assertEquals(1, FogMipLevel.forZoomDelta(0.75, 11));
        assertEquals(3, FogMipLevel.forZoomDelta(3.3, 11));
    }

    @Test public void retainedHalfResolutionIsNotMinifiedTwice() {
        assertEquals(0, FogMipLevel.forZoomDelta(1.0, 0.5f, 8));
        assertEquals(1, FogMipLevel.forZoomDelta(2.0, 0.5f, 8));
        assertEquals(0, FogMipLevel.forZoomDelta(2.0, 0.25f, 8));
        assertEquals(0, FogMipLevel.forZoomDelta(0.0, 0.25f, 8));
    }

    @Test public void reducedResolutionMipAlwaysMonotone() {
        int previous = 0;
        for (double delta = 0; delta <= 5.0; delta += .1) {
            int now = FogMipLevel.forZoomDelta(delta, 0.5f, 8);
            assertTrue(now >= previous);
            previous = now;
        }
    }

    @Test public void clampsUnboundedCameraDelta() {
        assertEquals(10, FogMipLevel.forZoomDelta(100, 11));
        assertEquals(0, FogMipLevel.forZoomDelta(Double.NaN, 11));
        assertEquals(0, FogMipLevel.forZoomDelta(5, 0));
    }

    @Test public void levelDoesNotDecreaseAsYouZoomOut() {
        int previous = 0;
        for (double delta = 0; delta < 12; delta += 0.15) {
            int level = FogMipLevel.forZoomDelta(delta, 10);
            assertTrue(level >= previous);
            previous = level;
        }
    }
}
