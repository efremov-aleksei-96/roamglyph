package com.sensareth.roamglyph.map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class FogGpsBlendTest {
    private static int alpha(int[] pixels, int width, int x, int y) {
        return pixels[y * width + x] >>> 24;
    }

    @Test public void smoothRouteWinsNearGpsButDistantVisitedIslandStaysVisible() {
        int width = 120, height = 36, count = width * height;
        int[] exact = new int[count];
        int[] h3 = new int[count];
        int[] gps = new int[count];
        int[] rawGps = new int[count];

        for (int y = 2; y < height - 2; y++) {
            for (int x = 2; x < width - 2; x++) {
                int i = y * width + x;
                exact[i] = 0xFFFFFFFF;
                h3[i] = 0xDCFFFFFF; // H3-only fallback, alpha=220
                if (x >= 25 && x < 34) {
                    rawGps[i] = 0xFFFFFFFF;
                    gps[i] = 0xFFFFFFFF;
                }
            }
        }
        FogGpsBlend.blend(exact, h3, gps, rawGps, width, height,
                8f, () -> false);
        assertEquals("Continuous GPS center stays clear",
                255, alpha(gps, width, 29, 16));
        assertEquals("Nearby H3 teeth should fade, not form jagged edge",
                0, alpha(gps, width, 35, 16));
        assertEquals("Unrelated explored H3 region remains visible",
                220, alpha(gps, width, 100, 16));
        assertEquals("Never clear outside source H3",
                0, alpha(gps, width, 0, 16));
    }

    @Test public void noOutwardRevealEvenIfGpsMaskWasCorrupt() {
        int[] exact = {0, 0x88FFFFFF};
        int[] h3 = {0, 0x55FFFFFF};
        int[] gps = {0xFFFFFFFF, 0xFFFFFFFF};
        int[] raw = {0xFFFFFFFF, 0xFFFFFFFF};
        FogGpsBlend.blend(exact, h3, gps, raw, 2, 1,
                6f, () -> false);
        assertEquals(0, gps[0] >>> 24);
        assertTrue((gps[1] >>> 24) <= 0x88);
    }

    @Test(expected = IllegalArgumentException.class)
    public void mismatchedMasksRejected() {
        FogGpsBlend.blend(new int[4], new int[4],
                new int[3], new int[4], 2, 2,
                8f, () -> false);
    }
}
