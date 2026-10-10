package com.sensareth.roamglyph.map;

import org.junit.Test;

import java.util.concurrent.CancellationException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Pure-JVM image-shape regressions: no Android emulator needed. */
public class FogSilhouetteFieldTest {
    private static int[] blank(int width, int height) {
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) pixels[i] = 0x00FFFFFF;
        return pixels;
    }

    private static void rect(int[] pixels, int width,
                             int left, int top, int right, int bottom) {
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) pixels[y * width + x] = 0xFFFFFFFF;
        }
    }

    private static int alpha(int[] pixels, int width, int x, int y) {
        return pixels[y * width + x] >>> 24;
    }

    @Test(expected = CancellationException.class)
    public void supersededViewportComputationCanCancelBeforeCompletion() {
        int width = 144, height = 96;
        int[] image = blank(width, height);
        rect(image, width, 8, 8, 130, 82);
        FogSilhouetteField.renderInPlace(
                image, width, height, 7, 18, () -> true);
    }

    @Test
    public void neverRevealsPixelsOutsideTheExactVisitedStencil() {
        int width = 104, height = 72;
        int[] image = blank(width, height);
        rect(image, width, 10, 11, 65, 48);
        // A tiny isolated hex-like extrusion must not be enlarged.
        rect(image, width, 65, 27, 76, 32);
        int[] original = image.clone();

        FogSilhouetteField.renderInPlace(image, width, height, 7, 16);
        for (int i = 0; i < image.length; i++) {
            int oldAlpha = original[i] >>> 24;
            int newAlpha = image[i] >>> 24;
            assertTrue("A fog pixel must not reveal unvisited map", newAlpha <= oldAlpha);
        }
        assertEquals(0, alpha(image, width, 76, 30));
        assertEquals(0, alpha(image, width, 80, 45));
    }

    @Test
    public void broadExploredRegionHasVisibleInwardFadeInsteadOfSolidHexEdge() {
        int width = 108, height = 82;
        int[] image = blank(width, height);
        rect(image, width, 12, 12, 96, 72);
        FogSilhouetteField.renderInPlace(image, width, height, 5, 18);

        int border = alpha(image, width, 12, 40);
        int shoulder = alpha(image, width, 19, 40);
        int interior = alpha(image, width, 55, 40);
        assertTrue("Outer exact H3 boundary should be nearly invisible", border < 35);
        assertTrue("Gradient should climb visibly inward", shoulder > border + 15);
        assertTrue("Visited core must remain clear", interior > 225);
        assertTrue("Transition must not jump directly to full clear", shoulder < 245);
    }

    @Test
    public void narrowConnectedRouteHasAReadableCenterWithoutOutsideBleed() {
        int width = 64, height = 88;
        int[] image = blank(width, height);
        rect(image, width, 28, 8, 35, 80);
        FogSilhouetteField.renderInPlace(image, width, height, 3, 9);

        int center = alpha(image, width, 31, 48);
        int border = alpha(image, width, 28, 48);
        assertTrue("Thin genuine path must remain discernible", center >= 35);
        assertTrue("Route center should be clearer than its edges", center > border);
        assertEquals("No artificial route expansion", 0,
                alpha(image, width, 27, 48));
    }

    @Test
    public void hexLikeSawtoothTipIsFainterThanContinuousVisitedInterior() {
        int width = 96, height = 80;
        int[] image = blank(width, height);
        rect(image, width, 12, 12, 63, 68);
        rect(image, width, 63, 38, 75, 42);
        FogSilhouetteField.renderInPlace(image, width, height, 7, 17);

        int tip = alpha(image, width, 72, 40);
        int core = alpha(image, width, 44, 40);
        assertTrue("Hexagonal protrusion should merge into dark fog",
                tip < core / 3);
        assertTrue("Broad explored area must stay clear", core >= 210);
    }
}
