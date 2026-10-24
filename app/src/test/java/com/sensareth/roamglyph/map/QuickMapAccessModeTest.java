package com.sensareth.roamglyph.map;

import org.junit.Test;
import static org.junit.Assert.*;

public class QuickMapAccessModeTest {
    @Test public void offBasicFullHaveDistinctCapabilities() {
        assertFalse(QuickMapAccessMode.showRender(QuickMapAccessMode.OFF));
        assertFalse(QuickMapAccessMode.showDiagnostics(QuickMapAccessMode.OFF));
        assertTrue(QuickMapAccessMode.showRender(QuickMapAccessMode.BASIC));
        assertFalse(QuickMapAccessMode.showDiagnostics(QuickMapAccessMode.BASIC));
        assertTrue(QuickMapAccessMode.showRender(QuickMapAccessMode.FULL));
        assertTrue(QuickMapAccessMode.showDiagnostics(QuickMapAccessMode.FULL));
    }

    @Test public void defaultsAndCorruptionFallbackToFull() {
        assertEquals(QuickMapAccessMode.FULL, QuickMapAccessMode.normalize(-1));
        assertEquals(QuickMapAccessMode.FULL, QuickMapAccessMode.normalize(42));
        assertEquals("Basic", QuickMapAccessMode.label(1));
        assertEquals("Full", QuickMapAccessMode.label(-1));
    }
}
