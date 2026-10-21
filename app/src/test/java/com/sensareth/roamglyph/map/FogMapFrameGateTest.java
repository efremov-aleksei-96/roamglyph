package com.sensareth.roamglyph.map;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FogMapFrameGateTest {
    @Test public void rejectsOldFrameFinishingAfterNewCameraMove() {
        // A starts at t=100, camera B moves at t=150, A finishes at t=200.
        assertFalse(FogMapFrameGate.isCurrentCameraFrame(150, 120, 100, 200));
    }

    @Test public void acceptsFreshFrameStartedAfterLatestCameraMove() {
        assertTrue(FogMapFrameGate.isCurrentCameraFrame(150, 120, 160, 200));
    }

    @Test public void rejectsMissingUnpairedOrReversedFrameEvents() {
        assertFalse(FogMapFrameGate.isCurrentCameraFrame(150, 120, -1, 200));
        assertFalse(FogMapFrameGate.isCurrentCameraFrame(150, 120, 180, 175));
    }

    @Test public void rejectsAlreadyHandledOrNonmovingCamera() {
        assertFalse(FogMapFrameGate.isCurrentCameraFrame(150, 150, 160, 200));
        assertFalse(FogMapFrameGate.isCurrentCameraFrame(0, 0, 160, 200));
    }
}
