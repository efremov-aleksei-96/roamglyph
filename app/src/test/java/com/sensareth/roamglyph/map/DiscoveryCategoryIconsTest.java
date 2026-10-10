package com.sensareth.roamglyph.map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class DiscoveryCategoryIconsTest {
    @Test
    public void assignsDistinctLocalMapIconsToImportantCategories() {
        assertEquals("🏛️", DiscoveryCategoryIcons.iconFor("culture", "museum"));
        assertEquals("🌄", DiscoveryCategoryIcons.iconFor("viewpoint", null));
        assertEquals("⛲", DiscoveryCategoryIcons.iconFor("fountain", "fountain"));
        assertEquals("🎨", DiscoveryCategoryIcons.iconFor("art", "sculpture"));
        assertEquals("🌳", DiscoveryCategoryIcons.iconFor("nature", "garden"));
        assertNotEquals(DiscoveryCategoryIcons.iconFor("art", null),
                DiscoveryCategoryIcons.iconFor("nature", null));
    }

    @Test
    public void keepsFallbackSymbolForUnknownLocalPois() {
        assertEquals("📍", DiscoveryCategoryIcons.iconFor(null, null));
        assertEquals("📍", DiscoveryCategoryIcons.iconFor("unrecognized_category", ""));
    }

    @Test
    public void categoryMatchingIsCaseInsensitiveAndSubclassFirst() {
        assertEquals("🏰", DiscoveryCategoryIcons.iconFor("LANDMARK", "Castle"));
        assertEquals("🌄", DiscoveryCategoryIcons.iconFor("landmark", "VIEWPOINT"));
    }
}
