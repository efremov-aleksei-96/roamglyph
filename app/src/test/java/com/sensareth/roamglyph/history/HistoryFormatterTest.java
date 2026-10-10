package com.sensareth.roamglyph.history;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;

public class HistoryFormatterTest {
    @Test
    public void formatsShortAndLongDistances() {
        assertEquals("850 m", HistoryFormatter.distance(850.4, Locale.US));
        assertEquals("12.3 km", HistoryFormatter.distance(12_345.0, Locale.US));
    }

    @Test
    public void formatsDurationAcrossDays() {
        assertEquals("0m", HistoryFormatter.duration(1_000L));
        assertEquals("1h 05m", HistoryFormatter.duration(65L * 60_000L));
        assertEquals("1d 2h 03m", HistoryFormatter.duration((26L * 60L + 3L) * 60_000L));
    }

    @Test
    public void formatsExploredArea() {
        assertEquals("4,387 m²", HistoryFormatter.area(100, Locale.US));
        assertEquals("4.4 ha", HistoryFormatter.area(1000, Locale.US));
        assertEquals("1.10 km²", HistoryFormatter.area(25_075, Locale.US));
    }
}
