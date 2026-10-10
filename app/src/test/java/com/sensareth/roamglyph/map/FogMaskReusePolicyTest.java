package com.sensareth.roamglyph.map;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FogMaskReusePolicyTest {
    @Test
    public void allowsPanningAndShrinkingWithoutExpandingVisitedPixels() {
        assertTrue(FogMaskReusePolicy.mayReuse(16.0, 16.0, 0.0, 0.0));
        assertTrue(FogMaskReusePolicy.mayReuse(16.0, 9.0, 0.0, 0.0));
        assertTrue(FogMaskReusePolicy.mayReuse(16.0, 16.049, 0.0, 0.0));
    }

    @Test
    public void rejectsZoomInThatMagnifiesScreenQuantizedCoverage() {
        assertFalse(FogMaskReusePolicy.mayReuse(9.0, 9.051, 0.0, 0.0));
        assertFalse(FogMaskReusePolicy.mayReuse(9.0, 17.0, 0.0, 0.0));
    }

    @Test
    public void rejectsLargeCameraTiltChangesAndInvalidMetadata() {
        assertFalse(FogMaskReusePolicy.mayReuse(16.0, 16.0, 0.0, 5.0));
        assertFalse(FogMaskReusePolicy.mayReuse(Double.NaN, 16.0, 0.0, 0.0));
        assertFalse(FogMaskReusePolicy.mayReuse(16.0, Double.POSITIVE_INFINITY, 0.0, 0.0));
    }
}
