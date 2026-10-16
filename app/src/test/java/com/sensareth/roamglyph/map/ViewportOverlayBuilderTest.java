package com.sensareth.roamglyph.map;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    public void denseUserHistoryRemainsWithinExactGeometryBudget() {
        assertTrue(ViewportOverlayBuilder.withinGeometryBudget(0));
        assertTrue(ViewportOverlayBuilder.withinGeometryBudget(20_000));
        assertTrue("29,831 explored cells must not disappear on city zoom",
                ViewportOverlayBuilder.withinGeometryBudget(29_831));
        assertTrue(ViewportOverlayBuilder.withinGeometryBudget(120_000));
        assertFalse(ViewportOverlayBuilder.withinGeometryBudget(120_001));
        assertFalse(ViewportOverlayBuilder.withinGeometryBudget(-1));
    }

    @Test
    public void denseOverviewBatchesHaveNoLostOrDuplicatedCells() {
        Set<String> exact = new HashSet<>();
        for (int i = 0; i < 29_831; i++) {
            exact.add(String.format("8b%013x", i));
        }
        List<Set<String>> batches = ViewportOverlayBuilder.spatialBatches(exact);
        assertTrue(batches.size() > 1);
        Set<String> seen = new HashSet<>();
        int count = 0;
        for (Set<String> batch : batches) {
            assertTrue(batch.size() > 0);
            assertTrue(batch.size() <= ViewportOverlayBuilder.MAX_MERGE_BATCH);
            for (String cell : batch) assertTrue("Duplicate H3 cell", seen.add(cell));
            count += batch.size();
        }
        assertEquals(exact.size(), count);
        assertEquals(exact, seen);
    }

    @Test
    public void wideZoomOverBudgetKeepsPreviouslyLoadedExactPaths() {
        ViewportOverlayBuilder.Result result = ViewportOverlayBuilder.Result.overBudget();
        assertTrue(result.preservePreviousGeometry);
        assertTrue(result.polygons.isEmpty());
        assertFalse(ViewportOverlayBuilder.Result.dark().preservePreviousGeometry);
    }

    @Test
    public void failDarkResultNeverContainsFakeVisitedPolygons() {
        assertEquals(0, ViewportOverlayBuilder.Result.dark().polygons.size());
        assertEquals(0, ViewportOverlayBuilder.Result.dark().exactCellCount);
    }
}
