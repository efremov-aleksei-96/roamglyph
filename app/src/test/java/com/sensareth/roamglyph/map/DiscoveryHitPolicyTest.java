package com.sensareth.roamglyph.map;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DiscoveryHitPolicyTest {
    @Test
    public void newlyEncounteredPoiIsSelectableWithinVisibleMarker() {
        assertTrue(DiscoveryHitPolicy.shouldReplace(80f, false,
                225f, false, false, 225f));
        assertFalse(DiscoveryHitPolicy.shouldReplace(226f, true,
                225f, false, false, 225f));
    }

    @Test
    public void displayedDiscoveredMarkerWinsWhenHintSharesLocation() {
        // Whichever candidate MapLibre returns first, the green marker wins.
        assertTrue(DiscoveryHitPolicy.shouldReplace(40f, true,
                40f, false, true, 225f));
        assertFalse(DiscoveryHitPolicy.shouldReplace(40f, false,
                40f, true, true, 225f));
        assertFalse(DiscoveryHitPolicy.shouldReplace(40f, true,
                40f, true, true, 225f));
    }

    @Test
    public void nearerPlaceWinsOverStatePriority() {
        assertTrue(DiscoveryHitPolicy.shouldReplace(30f, false,
                40f, true, true, 225f));
        assertFalse(DiscoveryHitPolicy.shouldReplace(50f, true,
                40f, false, true, 225f));
    }

    @Test
    public void invalidDistancesCannotSelectAFeature() {
        assertFalse(DiscoveryHitPolicy.shouldReplace(Float.NaN, true,
                40f, false, true, 225f));
        assertFalse(DiscoveryHitPolicy.shouldReplace(-1f, true,
                40f, false, true, 225f));
    }
}
