package com.sensareth.roamglyph.map;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class GpsCorridorJoinPolicyTest {
    @Test public void closeAcceptedFixesInOneSessionMakeSmoothTrack() {
        assertTrue(GpsCorridorJoinPolicy.shouldConnect(
                "ride-1", 1000L, 40.1872, 44.5152,
                "ride-1", 3000L, 40.18723, 44.51525));
    }

    @Test public void neverConnectsDifferentJourneys() {
        assertFalse(GpsCorridorJoinPolicy.shouldConnect(
                "ride-1", 1000L, 40.1872, 44.5152,
                "ride-2", 3000L, 40.18723, 44.51525));
    }

    @Test public void trackingOutageDoesNotCreateInventedExploredCorridor() {
        assertFalse(GpsCorridorJoinPolicy.shouldConnect(
                "ride-1", 1000L, 40.1872, 44.5152,
                "ride-1", 50_000L, 40.18723, 44.51525));
    }

    @Test public void longGpsJumpDoesNotRevealIntermediateStreet() {
        assertFalse(GpsCorridorJoinPolicy.shouldConnect(
                "ride-1", 1000L, 40.1872, 44.5152,
                "ride-1", 3000L, 40.20, 44.52));
    }

    @Test public void tileHaloCoversAllowedJoinsAtHigherLatitude() {
        double northSouth = GpsCorridorJoinPolicy.tileHaloLatitudeDegrees();
        double eastWest40 = GpsCorridorJoinPolicy.tileHaloLongitudeDegrees(40.1872);
        double eastWest60 = GpsCorridorJoinPolicy.tileHaloLongitudeDegrees(60.0);
        assertTrue("Halo must exceed 140m segment and 11m radius",
                northSouth * 111_000.0 > 160.0);
        assertTrue(eastWest40 > northSouth);
        assertTrue(eastWest60 > eastWest40);
    }

    @Test public void outOfOrderOrInvalidFixesNeverJoin() {
        assertFalse(GpsCorridorJoinPolicy.shouldConnect(
                "ride-1", 3000L, 40.1872, 44.5152,
                "ride-1", 1000L, 40.1872, 44.5152));
        assertFalse(GpsCorridorJoinPolicy.shouldConnect(
                "ride-1", 3000L, Double.NaN, 44.5152,
                "ride-1", 5000L, 40.1872, 44.5152));
    }
}
