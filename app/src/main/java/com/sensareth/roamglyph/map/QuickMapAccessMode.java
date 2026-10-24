package com.sensareth.roamglyph.map;

/** Presentation-only preference: independent of GPS, fog tiles and history. */
public final class QuickMapAccessMode {
    public static final int OFF = 0;
    public static final int BASIC = 1;
    public static final int FULL = 2;

    public static final String[] LABELS = {
            "Off — hide map shortcuts",
            "Basic — quick rendering only",
            "Full — rendering, share and reset"
    };

    private QuickMapAccessMode() { }

    public static int normalize(int saved) {
        return saved == OFF || saved == BASIC || saved == FULL ? saved : FULL;
    }

    public static boolean showRender(int mode) {
        return normalize(mode) != OFF;
    }

    public static boolean showDiagnostics(int mode) {
        return normalize(mode) == FULL;
    }

    public static String label(int mode) {
        return new String[]{"Off", "Basic", "Full"}[normalize(mode)];
    }
}
