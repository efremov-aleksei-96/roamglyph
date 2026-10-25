package com.sensareth.roamglyph.map;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Deterministic ordering for the on-device, opt-in camera-only A/B benchmark.
 * Modes never modify the persisted tracking, GPS or exploration settings.
 */
public final class AutoBenchmarkPlan {
    private AutoBenchmarkPlan() { }

    public static final class Mode {
        public final int renderer; // 0=V1, 1=V2, 2=V3, 3=V4
        public final boolean textureView;
        public final boolean mapFrameSync;

        public Mode(int renderer, boolean textureView, boolean mapFrameSync) {
            if (renderer < 0 || renderer > 3)
                throw new IllegalArgumentException("renderer");
            this.renderer = renderer;
            this.textureView = textureView;
            this.mapFrameSync = mapFrameSync;
        }

        public String label() {
            return "V" + (renderer + 1) + " / "
                    + (textureView ? "TextureView" : "GLSurfaceView")
                    + " / " + (mapFrameSync ? "Map frame" : "Standard");
        }
    }

    /**
     * Adjacent stress trial order, balanced per compositor/sync mode:
     * OFF/ON, ON/OFF, OFF/ON, ON/OFF.
     */
    public static boolean pruningEnabledAt(int step, boolean paired) {
        if (step < 0) throw new IllegalArgumentException("benchmark step");
        if (!paired) return true;
        int pair = step / 2;
        return ((step & 1) == 1) == ((pair & 1) == 0);
    }

    /** Group by compositor to minimize costly MapView recreation. */
    public static List<Mode> modes(boolean allRenderers) {
        ArrayList<Mode> result = new ArrayList<>();
        for (boolean texture : new boolean[]{false, true}) {
            for (boolean sync : new boolean[]{false, true}) {
                if (allRenderers) {
                    for (int renderer = 0; renderer < 4; renderer++)
                        result.add(new Mode(renderer, texture, sync));
                } else {
                    result.add(new Mode(3, texture, sync));
                }
            }
        }
        return Collections.unmodifiableList(result);
    }
}
