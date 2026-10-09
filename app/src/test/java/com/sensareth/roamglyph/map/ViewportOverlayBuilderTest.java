package com.sensareth.roamglyph.map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ViewportOverlayBuilderTest {
    @Test
    public void disablesFogOnlyAtWorldScale() {
        assertEquals(-1, ViewportOverlayBuilder.resolutionForZoom(2.49));
        assertEquals(3, ViewportOverlayBuilder.resolutionForZoom(2.5));
    }

    @Test
    public void preservesExactCellsAtStreetZoom() {
        assertEquals(13, ViewportOverlayBuilder.resolutionForZoom(17.0));
        assertEquals(13, ViewportOverlayBuilder.resolutionForZoom(17.5));
        assertEquals(13, ViewportOverlayBuilder.resolutionForZoom(22.0));
    }

    @Test
    public void progressivelyAggregatesWhenZoomedOut() {
        assertEquals(12, ViewportOverlayBuilder.resolutionForZoom(15.5));
        assertEquals(11, ViewportOverlayBuilder.resolutionForZoom(14.0));
        assertEquals(10, ViewportOverlayBuilder.resolutionForZoom(12.5));
        assertEquals(9, ViewportOverlayBuilder.resolutionForZoom(11.0));
        assertEquals(8, ViewportOverlayBuilder.resolutionForZoom(9.5));
        assertEquals(7, ViewportOverlayBuilder.resolutionForZoom(8.0));
        assertEquals(6, ViewportOverlayBuilder.resolutionForZoom(6.5));
        assertEquals(5, ViewportOverlayBuilder.resolutionForZoom(5.0));
        assertEquals(4, ViewportOverlayBuilder.resolutionForZoom(3.5));
    }
}
