package com.sensareth.roamglyph.map;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FogContourSmootherTest {
    private FogContourSmoother.Vertex v(float x, float y) {
        return new FogContourSmoother.Vertex(x, y);
    }

    @Test
    public void removesSmallSawtoothBumpsButPreservesMajorCorners() {
        List<FogContourSmoother.Vertex> trail = Arrays.asList(
                v(0, 0), v(10, 0.5f), v(20, -0.5f), v(30, 0),
                v(40, 0.6f), v(50, 0), v(50, 20), v(0, 20));
        List<FogContourSmoother.Vertex> result =
                FogContourSmoother.simplifyClosed(trail, 2.0f);
        assertTrue("Remove small H3-style zigzags", result.size() < trail.size());
        assertTrue("Preserve polygon topology", result.size() >= 3);
        assertEquals(0f, result.get(0).x, 0f);
    }

    @Test
    public void anIsolatedCellNeverBecomesAnInvisibleLine() {
        List<FogContourSmoother.Vertex> hexagon = Arrays.asList(
                v(0, 10), v(8, 5), v(8, -5), v(0, -10), v(-8, -5), v(-8, 5));
        List<FogContourSmoother.Vertex> result =
                FogContourSmoother.simplifyClosed(hexagon, 100f);
        assertTrue("Maintain non-degenerate silhouette", result.size() >= 3);
    }

    @Test
    public void zeroToleranceLeavesGeometryUntouched() {
        List<FogContourSmoother.Vertex> ring =
                Arrays.asList(v(0, 0), v(10, 0), v(10, 10), v(0, 10));
        assertEquals(ring, FogContourSmoother.simplifyClosed(ring, 0));
    }

    @Test
    public void smallClosedPolygonRemainsClosedGeometry() {
        List<FogContourSmoother.Vertex> triangle =
                Arrays.asList(v(0, 0), v(8, 0), v(4, 6));
        assertEquals(3, FogContourSmoother.simplifyClosed(triangle, 8).size());
    }
}
