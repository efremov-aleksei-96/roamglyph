package com.sensareth.roamglyph.map;

/** Presentation labels and priority matching the renderer actually used by Fog. */
public final class FogRendererChoice {
    public static final String[] LABELS = {
            "V1 — Legacy",
            "V2 — Experimental SDF",
            "V3 — Smooth GPS corridors",
            "V4 — Organic contour"
    };

    private FogRendererChoice() { }

    public static int current(boolean v2, boolean v3, boolean v4) {
        if (v4) return 3;
        if (v3) return 2;
        if (v2) return 1;
        return 0;
    }

    public static String label(int choice) {
        if (choice < 0 || choice >= LABELS.length) return LABELS[0];
        return LABELS[choice];
    }
}
