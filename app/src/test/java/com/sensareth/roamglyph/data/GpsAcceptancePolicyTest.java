package com.sensareth.roamglyph.data;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
    public void rejectsOldOrDuplicateMonotonicFixes() {
        long now = 120_000_000_000L;
        long recentFix = now - 5_000_000_000L;
        assertFalse(GpsAcceptancePolicy.isStaleFix(recentFix, now, 0L));
        assertFalse(GpsAcceptancePolicy.isStaleFix(
                now - GpsAcceptancePolicy.MAX_FIX_AGE_NS, now, 0L
        ));
        assertTrue(GpsAcceptancePolicy.isStaleFix(
                now - GpsAcceptancePolicy.MAX_FIX_AGE_NS - 1L, now, 0L
        ));
        assertTrue(GpsAcceptancePolicy.isStaleFix(recentFix, now, recentFix));
        assertTrue(GpsAcceptancePolicy.isStaleFix(recentFix, now, recentFix + 1L));
        assertTrue(GpsAcceptancePolicy.isStaleFix(now + 1L, now, 0L));
        assertTrue(GpsAcceptancePolicy.isStaleFix(0L, now, 0L));
    }

    @Test
    public void wallClockRollbackDoesNotBlockCoverage() {
        GpsPointEntity previous = new GpsPointEntity(
                "p1", "s1", 10_000L, 40.1800, 44.5100, 8f,
                null, null, "gps", true, "cell", null
        );
        assertNull(GpsAcceptancePolicy.rejectionReason(
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
