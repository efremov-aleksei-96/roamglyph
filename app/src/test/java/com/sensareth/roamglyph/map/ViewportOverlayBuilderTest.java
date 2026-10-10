package com.sensareth.roamglyph.map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ViewportOverlayBuilderTest {
    @Test
    public void zoomNeverChangesActualExploredCellResolution() {
        for (int zoom = 0; zoom <= 24; zoom++) {
            assertEquals(13, ViewportOverlayBuilder.resolutionForZoom(zoom));
        }
        assertEquals(13, ViewportOverlayBuilder.resolutionForZoom(-10));
        assertEquals(13, ViewportOverlayBuilder.resolutionForZoom(100));
    }

    @Test
    public void onlySourceResolutionIsExact() {
        assertTrue(ViewportOverlayBuilder.isExactCoverage(13));
        for (int resolution = 0; resolution < 13; resolution++) {
            assertFalse(ViewportOverlayBuilder.isExactCoverage(resolution));
        }
    }

    @Test
    public void overBudgetGeometryFailsDarkInsteadOfInflatingCells() {
        assertTrue(ViewportOverlayBuilder.withinGeometryBudget(0));
        assertTrue(ViewportOverlayBuilder.withinGeometryBudget(20000));
        assertFalse(ViewportOverlayBuilder.withinGeometryBudget(20001));
        assertFalse(ViewportOverlayBuilder.withinGeometryBudget(-1));
    }

    @Test
    public void failDarkResultNeverContainsFakeVisitedPolygons() {
        assertEquals(0, ViewportOverlayBuilder.Result.dark().polygons.size());
        assertEquals(0, ViewportOverlayBuilder.Result.dark().exactCellCount);
    }
}
