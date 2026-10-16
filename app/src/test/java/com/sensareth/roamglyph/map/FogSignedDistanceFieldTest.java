package com.sensareth.roamglyph.map;

import org.junit.Test;

import java.util.Arrays;
import java.util.concurrent.CancellationException;

import static org.junit.Assert.*;

public final class FogSignedDistanceFieldTest {
    @Test public void neverRevealsOutsideExactH3Stencil() {
        int size = 50;
        int[] pixels = new int[size * size];
        for (int y = 10; y < 40; y++) {
            for (int x = 10; x < 40; x++) {
                int alpha = x == 10 || y == 10 ? 90 : 255;
                pixels[y * size + x] = (alpha << 24) | 0xFFFFFF;
            }
        }
        int[] original = pixels.clone();
        FogSignedDistanceField.renderInPlace(pixels, size, size, 5, () -> false);
        for (int i = 0; i < pixels.length; i++) {
            assertTrue("V2 cannot expose pixels not in exact source H3",
                    (pixels[i] >>> 24) <= (original[i] >>> 24));
        }
        assertEquals(0, pixels[5 * size + 5] >>> 24);
        assertEquals(255, pixels[25 * size + 25] >>> 24);
        assertTrue("Feather must fade inward at the H3 boundary",
                (pixels[11 * size + 25] >>> 24) < (pixels[25 * size + 25] >>> 24));
    }

    @Test public void thinExploredCorridorsRemainVisible() {
        int[] pixels = new int[64 * 64];
        for (int x = 5; x < 59; x++) {
            pixels[32 * 64 + x] = 0xFFFFFFFF;
        }
        FogSignedDistanceField.renderInPlace(pixels, 64, 64, 4, () -> false);
        assertTrue("A one-pixel overview route must retain some visibility",
                (pixels[32 * 64 + 32] >>> 24) >= 60);
        assertEquals(0, pixels[33 * 64 + 32] >>> 24);
    }

    @Test public void emptyAndFullMaskStayStable() {
        int[] empty = new int[16 * 16];
        FogSignedDistanceField.renderInPlace(empty, 16, 16, 3, () -> false);
        for (int alpha : empty) assertEquals(0, alpha >>> 24);

        int[] full = new int[16 * 16];
        Arrays.fill(full, 0xFFFFFFFF);
        FogSignedDistanceField.renderInPlace(full, 16, 16, 3, () -> false);
        for (int alpha : full) assertEquals(255, alpha >>> 24);
    }

    @Test(expected = CancellationException.class)
    public void supersededTileStopsWithoutPublishing() {
        FogSignedDistanceField.renderInPlace(
                new int[100 * 100], 100, 100, 4, () -> true);
    }

    @Test public void paddedNeighbourhoodMatchesDirectReferenceNearTileSeam() {
        final int tile = 32, pad = 18, size = tile + pad * 2;
        // One city corridor crosses the boundary between two geographic tiles.
        int[] direct = makeWorldMask(-pad, -pad, tile * 2 + pad * 2, size);
        FogSignedDistanceField.renderInPlace(
                direct, tile * 2 + pad * 2, size, 4, () -> false);
        for (int tileX = 0; tileX <= 1; tileX++) {
            int[] part = makeWorldMask(tileX * tile - pad, -pad,
                    size, size);
            FogSignedDistanceField.renderInPlace(part, size, size, 4, () -> false);
            for (int y = 0; y < tile; y++) {
                for (int x = 0; x < tile; x++) {
                    int expected = direct[(y + pad) * (tile * 2 + pad * 2)
                            + (tileX * tile + x + pad)] >>> 24;
                    int actual = part[(y + pad) * size + (x + pad)] >>> 24;
                    assertEquals("World anchored SDF must meet across tile edges",
                            expected, actual);
                }
            }
        }
    }

    private static int[] makeWorldMask(int worldX, int worldY, int w, int h) {
        int[] result = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int gx = worldX + x;
                int gy = worldY + y;
                // Long winding explored ribbon, surrounded by unknown pixels.
                double centerY = 16 + 6 * Math.sin(gx / 10.0);
                if (gx >= 3 && gx <= 58 && Math.abs(gy - centerY) <= 5.5) {
                    result[y * w + x] = 0xFFFFFFFF;
                }
            }
        }
        return result;
    }
}
