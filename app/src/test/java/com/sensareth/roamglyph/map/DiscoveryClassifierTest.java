package com.sensareth.roamglyph.map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DiscoveryClassifierTest {
    @Test
    public void acceptsLandmarksAndArt() {
        DiscoveryClassifier.Classification monument =
                DiscoveryClassifier.classify("attraction", "monument", "Test", 1);
        DiscoveryClassifier.Classification mural =
                DiscoveryClassifier.classify("art_gallery", "mural", "Wall", 10);

        assertEquals("landmark", monument.category);
        assertEquals("art", mural.category);
        assertTrue(monument.baseScore > mural.baseScore - 20);
    }

    @Test
    public void rejectsOrdinaryCommercialPois() {
        assertNull(DiscoveryClassifier.classify("shop", "convenience", "Store", 1));
        assertNull(DiscoveryClassifier.classify("cafe", "cafe", "Cafe", 1));
        assertNull(DiscoveryClassifier.classify("atm", "atm", null, 1));
    }

    @Test
    public void unnamedFountainCanStillBeInteresting() {
        DiscoveryClassifier.Classification fountain =
                DiscoveryClassifier.classify("fountain", "fountain", null, 20);
        assertEquals("fountain", fountain.category);
        assertEquals(
                "Fountain",
                DiscoveryClassifier.displayName(null, fountain.category, "fountain")
        );
    }

    @Test
    public void acceptsArchitectureHistoryAndInformation() {
        assertEquals(
                "architecture",
                DiscoveryClassifier.classify(
                        "town_hall",
                        "townhall",
                        "City Hall",
                        5
                ).category
        );
        assertEquals(
                "history",
                DiscoveryClassifier.classify(
                        "cemetery",
                        "cemetery",
                        "Old Cemetery",
                        8
                ).category
        );
        assertEquals(
                "information",
                DiscoveryClassifier.classify(
                        "information",
                        "guidepost",
                        null,
                        12
                ).category
        );
    }

    @Test
    public void acceptsArtsCentreAndCinema() {
        assertEquals(
                "culture",
                DiscoveryClassifier.classify(
                        "arts_centre",
                        "arts_centre",
                        "Arts Centre",
                        6
                ).category
        );
        assertEquals(
                "culture",
                DiscoveryClassifier.classify(
                        "cinema",
                        "cinema",
                        "Cinema",
                        6
                ).category
        );
    }

    @Test
    public void genericParkRequiresAName() {
        assertNull(DiscoveryClassifier.classify("park", "park", null, 1));
        assertEquals(
                "nature",
                DiscoveryClassifier.classify("park", "park", "Central Park", 1).category
        );
    }
}
