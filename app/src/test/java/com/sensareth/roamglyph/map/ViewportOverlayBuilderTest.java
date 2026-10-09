package com.sensareth.roamglyph.map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ViewportOverlayBuilderTest {
    @Test
    public void disablesFogWhenZoomedOut() {
        assertEquals(-1, ViewportOverlayBuilder.resolutionForZoom(14.0));
    }

    @Test
    public void usesAdaptiveH3Resolution() {
        assertEquals(10, ViewportOverlayBuilder.resolutionForZoom(14.5));
        assertEquals(10, ViewportOverlayBuilder.resolutionForZoom(16.49));
        assertEquals(11, ViewportOverlayBuilder.resolutionForZoom(16.5));
        assertEquals(12, ViewportOverlayBuilder.resolutionForZoom(18.0));
        assertEquals(13, ViewportOverlayBuilder.resolutionForZoom(19.0));
        assertEquals(13, ViewportOverlayBuilder.resolutionForZoom(22.0));
    }
}
