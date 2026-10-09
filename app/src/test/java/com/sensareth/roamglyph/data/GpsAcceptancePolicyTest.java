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
