package com.sensareth.roamglyph.map;

import java.util.HashSet;
import java.util.Set;

/** Location-independent camera offsets; contains no GPS or stored geography. */
public final class AutoBenchmarkPath {
    public enum Scenario { WARM_PAN, NEW_TERRITORY }
    public static final int WARM_STEPS = 20;
    public static final int STRESS_STEPS = 24;
    public static final int SCRIPT_VERSION = 2;

    private AutoBenchmarkPath() { }

    public static final class Offset {
        public final double northMeters;
        public final double eastMeters;
        Offset(double northMeters, double eastMeters) {
            this.northMeters = northMeters;
            this.eastMeters = eastMeters;
        }
    }

    public static int steps(Scenario scenario) {
        return scenario == Scenario.NEW_TERRITORY ? STRESS_STEPS : WARM_STEPS;
    }

    /** Short repeated camera movements: reference scenario from dev.42. */
    public static Offset waypoint(Scenario scenario, int step) {
        if (step < 0 || step >= steps(scenario))
            throw new IllegalArgumentException("step outside benchmark script");
        if (scenario == Scenario.WARM_PAN)
            return new Offset(0, (step & 1) == 0 ? 100 : -100);

        // Visit 24 distinct sites: 3 rings from 700m to 2100m, 8 headings
        // each. The coprime angular permutation forces large consecutive
        // crossings instead of repeatedly hitting the same cached tiles.
        int ring = step / 8;
        int heading = ((step % 8) * 3 + ring) % 8;
        double radius = (ring + 1) * 700.0;
        double angle = heading * Math.PI / 4.0;
        return new Offset(radius * Math.cos(angle),
                          radius * Math.sin(angle));
    }

    /**
     * Checks the script, not actual cache misses. Run-time diagnostics must
     * measure tile builds to confirm that stress reached uncached content.
     */
    public static boolean hasDistinctStressTargets() {
        Set<String> keys = new HashSet<>();
        for (int step = 0; step < STRESS_STEPS; step++) {
            Offset p = waypoint(Scenario.NEW_TERRITORY, step);
            keys.add(Math.round(p.northMeters) + ":" + Math.round(p.eastMeters));
        }
        return keys.size() == STRESS_STEPS;
    }
}
