package com.sensareth.roamglyph.map;

import org.junit.Test;
import static org.junit.Assert.*;

public class AutoBenchmarkPathTest {
    @Test public void warmPresetPreservesTwentyAlternatingWaypoints() {
        assertEquals(20, AutoBenchmarkPath.steps(AutoBenchmarkPath.Scenario.WARM_PAN));
        assertEquals(100.0, AutoBenchmarkPath.waypoint(
                AutoBenchmarkPath.Scenario.WARM_PAN, 0).eastMeters, 0.001);
        assertEquals(-100.0, AutoBenchmarkPath.waypoint(
                AutoBenchmarkPath.Scenario.WARM_PAN, 1).eastMeters, 0.001);
    }

    @Test public void stressCrossesThreeRingsAndTwentyFourDistinctTargets() {
        assertEquals(24, AutoBenchmarkPath.steps(AutoBenchmarkPath.Scenario.NEW_TERRITORY));
        assertTrue(AutoBenchmarkPath.hasDistinctStressTargets());
        for (int i = 0; i < 24; i++) {
            AutoBenchmarkPath.Offset p = AutoBenchmarkPath.waypoint(
                    AutoBenchmarkPath.Scenario.NEW_TERRITORY, i);
            double radius = Math.hypot(p.eastMeters, p.northMeters);
            assertEquals(700.0 * (i / 8 + 1), radius, 0.001);
        }
    }

    @Test(expected=IllegalArgumentException.class)
    public void rejectsOutOfBoundsStep() {
        AutoBenchmarkPath.waypoint(AutoBenchmarkPath.Scenario.NEW_TERRITORY, 24);
    }
}
