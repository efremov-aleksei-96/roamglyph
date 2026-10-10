package com.sensareth.roamglyph.map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ViewportOverlayBuilderTest {
    @Test
    public void keepsFineCellsUntilTheyAreAboutOnePixel() {
        assertEquals(13, ViewportOverlayBuilder.resolutionForZoom(17.0));
        assertEquals(13, ViewportOverlayBuilder.resolutionForZoom(15.0));
        assertEquals(13, ViewportOverlayBuilder.resolutionForZoom(14.0));
        assertEquals(12, ViewportOverlayBuilder.resolutionForZoom(13.0));
        assertEquals(10, ViewportOverlayBuilder.resolutionForZoom(11.0));
    }

    @Test
    public void doesNotDisableFogAtWorldScale() {
        assertTrue(ViewportOverlayBuilder.resolutionForZoom(2.0) >= 0);
        assertTrue(ViewportOverlayBuilder.resolutionForZoom(0.0) >= 0);
        assertEquals(13, ViewportOverlayBuilder.resolutionForZoom(22.0));
    }

    @Test
    public void zoomSelectionIsLatitudeAwareAndMonotonic() {
        int equator = ViewportOverlayBuilder.resolutionForZoom(12.0, 0.0);
        int yerevan = ViewportOverlayBuilder.resolutionForZoom(12.0, 40.0);
        assertTrue(yerevan >= equator);
        int previous = 0;
        for (int zoom = 0; zoom <= 22; zoom++) {
            int resolution = ViewportOverlayBuilder.resolutionForZoom(zoom, 40.0);
            assertTrue(resolution >= previous);
            assertTrue(resolution <= 13);
            previous = resolution;
        }
    }

    @Test
    public void initialFogCoversWorldBeforeAsyncRender() {
        assertEquals(1, ViewportOverlayBuilder.initialFog().features().size());
    }
}
