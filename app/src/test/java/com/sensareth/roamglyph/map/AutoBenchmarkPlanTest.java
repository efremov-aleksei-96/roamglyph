package com.sensareth.roamglyph.map;

import org.junit.Test;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

public class AutoBenchmarkPlanTest {
    @Test public void quickHasExactlyFourCombinationsAndV4Only() {
        List<AutoBenchmarkPlan.Mode> modes = AutoBenchmarkPlan.modes(false);
        assertEquals(4, modes.size());
        Set<String> unique = new HashSet<>();
        for (AutoBenchmarkPlan.Mode m : modes) {
            assertEquals(3, m.renderer);
            unique.add(m.label());
        }
        assertEquals(4, unique.size());
    }

    @Test public void fullHasAllSixteenCombinationsGroupedByCompositor() {
        List<AutoBenchmarkPlan.Mode> modes = AutoBenchmarkPlan.modes(true);
        assertEquals(16, modes.size());
        Set<String> unique = new HashSet<>();
        for (int i = 0; i < modes.size(); i++) {
            AutoBenchmarkPlan.Mode m = modes.get(i);
            assertEquals(i >= 8, m.textureView);
            unique.add(m.label());
        }
        assertEquals(16, unique.size());
    }

    @Test public void alternatesEightPruningCasesWithBalancedOrder() {
        boolean[] expected = {false, true, true, false, false, true, true, false};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], AutoBenchmarkPlan.pruningEnabledAt(i, true));
            assertTrue(AutoBenchmarkPlan.pruningEnabledAt(i, false));
        }
    }

    @Test(expected=IllegalArgumentException.class)
    public void rejectsNegativeBenchmarkStep() {
        AutoBenchmarkPlan.pruningEnabledAt(-1, true);
    }

    @Test(expected=IllegalArgumentException.class)
    public void rejectsInvalidRenderer() {
        new AutoBenchmarkPlan.Mode(4, false, false);
    }
}
