package com.sensareth.roamglyph.map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class FogRendererChoiceTest {
    @Test public void everyRendererHasOneSingleChoiceIndex() {
        assertEquals(0, FogRendererChoice.current(false, false, false));
        assertEquals(1, FogRendererChoice.current(true, false, false));
        assertEquals(2, FogRendererChoice.current(false, true, false));
        assertEquals(3, FogRendererChoice.current(false, false, true));
    }

    @Test public void mirrorsCurrentRendererPriorityAndHasStableLabels() {
        assertEquals(3, FogRendererChoice.current(true, true, true));
        assertEquals(2, FogRendererChoice.current(true, true, false));
        assertEquals("V4 — Organic contour", FogRendererChoice.label(3));
        assertEquals("V1 — Legacy", FogRendererChoice.label(0));
        assertEquals(4, FogRendererChoice.LABELS.length);
    }
}
