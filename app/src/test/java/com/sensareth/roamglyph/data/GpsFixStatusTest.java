package com.sensareth.roamglyph.data;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class GpsFixStatusTest {
    @Test public void activeTrackingExplainsAccuracyThresholdWithoutRecordingChanges() {
        assertEquals(" · low accuracy (fix not recorded)",
                GpsFixStatus.suffix(true, 68f, "accuracy"));
        assertEquals(" · low accuracy (fix not recorded)",
                GpsFixStatus.suffix(true, 35.1f, null));
        assertEquals("", GpsFixStatus.suffix(true, 35f, null));
        assertEquals("", GpsFixStatus.suffix(true, 6f, null));
    }

    @Test public void unavailableAccuracyNeverLooksAccepted() {
        assertEquals(" · low accuracy (fix not recorded)",
                GpsFixStatus.suffix(true, 0f, null));
        assertEquals(" · low accuracy (fix not recorded)",
                GpsFixStatus.suffix(true, Float.NaN, null));
        assertEquals(" · low accuracy (fix not recorded)",
                GpsFixStatus.suffix(true, Float.POSITIVE_INFINITY, null));
    }

    @Test public void indicatesRejectionButNeverSaysEntireExplorationIsStopped() {
        assertEquals(" · stale fix ignored",
                GpsFixStatus.suffix(true, 5f, "stale"));
        assertEquals(" · GPS jump ignored",
                GpsFixStatus.suffix(true, 5f, "jump"));
        assertEquals(" · fix ignored",
                GpsFixStatus.suffix(true, 5f, "ignored"));
        assertEquals("", GpsFixStatus.suffix(false, 68f, "accuracy"));
    }
}
