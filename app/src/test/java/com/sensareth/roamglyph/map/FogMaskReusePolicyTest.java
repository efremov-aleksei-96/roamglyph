package com.sensareth.roamglyph.map;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FogMaskReusePolicyTest {
    @Test
    public void allowsPanningAndShrinkingWithoutExpandingVisitedPixels() {
        assertTrue(FogMaskReusePolicy.mayReuse(16, 16, 0, 0, 8));
        assertTrue(FogMaskReusePolicy.mayReuse(16, 9, 0, 0, 8));
        assertTrue(FogMaskReusePolicy.mayReuse(9, 9.049, 0, 0, 0.2));
    }

    @Test
    public void rejectsMagnificationOfSubpixelCoverage() {
        assertFalse(FogMaskReusePolicy.mayReuse(9, 9.051, 0, 0, 0.2));
        assertFalse(FogMaskReusePolicy.mayReuse(9, 17, 0, 0, 0.2));
    }

    @Test
    public void restrictsZoomMoreStrictlyForLargeCloseUpCells() {
        assertTrue(FogMaskReusePolicy.safeZoomDelta(100) < 0.008);
        assertTrue(FogMaskReusePolicy.safeZoomDelta(0.2) >= 0.049);
        assertTrue(FogMaskReusePolicy.mayReuse(20, 20.005, 0, 0, 100));
        assertFalse(FogMaskReusePolicy.mayReuse(20, 20.02, 0, 0, 100));
    }

    @Test
    public void rejectsTiltChangesAndUntrustedSourceMetadata() {
        assertFalse(FogMaskReusePolicy.mayReuse(16, 16, 0, 5, 8));
        assertFalse(FogMaskReusePolicy.mayReuse(Double.NaN, 16, 0, 0, 8));
        assertFalse(FogMaskReusePolicy.mayReuse(16, 16, 0, 0, Double.NaN));
        assertFalse(FogMaskReusePolicy.mayReuse(16, 16, 0, 0, 0));
    }
}
