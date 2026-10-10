package com.sensareth.roamglyph.data;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GpsAcceptancePolicyTest {
    @Test
    public void rejectsPoorAccuracy() {
        assertEquals(
                "accuracy",
                GpsAcceptancePolicy.rejectionReason(
                        1_000L,
                        40.18,
                        44.51,
                        80f,
                        null
                )
        );
    }

    @Test
    public void rejectsMissingOrInvalidAccuracy() {
        assertEquals("accuracy", GpsAcceptancePolicy.rejectionReason(
                1_000L, 40.18, 44.51, 0f, null
        ));
        assertEquals("accuracy", GpsAcceptancePolicy.rejectionReason(
                1_000L, 40.18, 44.51, -1f, null
        ));
        assertEquals("accuracy", GpsAcceptancePolicy.rejectionReason(
                1_000L, 40.18, 44.51, Float.NaN, null
        ));
        assertEquals("accuracy", GpsAcceptancePolicy.rejectionReason(
                1_000L, 40.18, 44.51, Float.POSITIVE_INFINITY, null
        ));
    }

    @Test
    public void rejectsStaleAndDuplicateFixes() {
        GpsPointEntity previous = new GpsPointEntity(
                "p1", "s1", 10_000L, 40.1800, 44.5100, 8f,
                null, null, "gps", true, "cell", null
        );
        assertEquals("stale", GpsAcceptancePolicy.rejectionReason(
                10_000L, 40.1801, 44.5100, 8f, previous
        ));
        assertEquals("stale", GpsAcceptancePolicy.rejectionReason(
                9_999L, 40.1801, 44.5100, 8f, previous
        ));
    }

    @Test
    public void acceptsNormalCyclingMovement() {
        GpsPointEntity previous = new GpsPointEntity(
                "p1",
                "s1",
                1_000L,
                40.1800,
                44.5100,
                8f,
                null,
                null,
                "gps",
                true,
                "cell",
                null
        );

        assertNull(
                GpsAcceptancePolicy.rejectionReason(
                        11_000L,
                        40.1810,
                        44.5100,
                        8f,
                        previous
                )
        );
    }

    @Test
    public void rejectsLargeTeleport() {
        GpsPointEntity previous = new GpsPointEntity(
                "p1",
                "s1",
                1_000L,
                40.1800,
                44.5100,
                5f,
                null,
                null,
                "gps",
                true,
                "cell",
                null
        );

        assertEquals(
                "jump",
                GpsAcceptancePolicy.rejectionReason(
                        6_000L,
                        40.2500,
                        44.5100,
                        5f,
                        previous
                )
        );
    }

    @Test
    public void distanceCalculationIsReasonable() {
        double distance = GpsAcceptancePolicy.distanceMeters(
                40.1800,
                44.5100,
                40.1810,
                44.5100
        );
        assertTrue(distance > 100.0);
        assertTrue(distance < 120.0);
    }
}
