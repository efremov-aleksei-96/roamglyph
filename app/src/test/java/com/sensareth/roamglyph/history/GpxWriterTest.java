package com.sensareth.roamglyph.history;

import com.sensareth.roamglyph.data.GpsPointEntity;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GpxWriterTest {
    @Test
    public void writesValidTrackAndEscapesName() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try (GpxWriter writer = new GpxWriter(output, "Ride & <test>", 1_000L)) {
            writer.append(point("p1", 1_000L, 40.18, 44.51, true));
            writer.append(point("p2", 31_000L, 40.181, 44.511, true));

            GpsPointEntity rejected = point(
                    "bad",
                    40_000L,
                    41.0,
                    45.0,
                    false
            );
            writer.append(rejected);

            assertEquals(2L, writer.getPointsWritten());
            assertEquals(1, writer.getSegmentsWritten());
        }

        String xml = output.toString(StandardCharsets.UTF_8);
        assertTrue(xml.contains("<gpx version=\"1.1\""));
        assertTrue(xml.contains("<name>Ride &amp; &lt;test&gt;</name>"));
        assertTrue(xml.contains("lat=\"40.1800000\" lon=\"44.5100000\""));
        assertFalse(xml.contains("lat=\"41.0000000\""));
        assertTrue(xml.endsWith("</gpx>\n"));
    }

    @Test
    public void splitsLongGpsGapsIntoSegments() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try (GpxWriter writer = new GpxWriter(output, "Ride", 1_000L)) {
            writer.append(point("p1", 1_000L, 40.18, 44.51, true));
            writer.append(point(
                    "p2",
                    1_000L + GpxWriter.SEGMENT_GAP_MS + 1L,
                    40.19,
                    44.52,
                    true
            ));
            assertEquals(2, writer.getSegmentsWritten());
        }

        String xml = output.toString(StandardCharsets.UTF_8);
        assertEquals(2, occurrences(xml, "<trkseg>"));
    }

    private static GpsPointEntity point(
            String id,
            long timestamp,
            double lat,
            double lng,
            boolean accepted
    ) {
        return new GpsPointEntity(
                id,
                "session",
                timestamp,
                lat,
                lng,
                5f,
                null,
                null,
                "gps",
                accepted,
                accepted ? "cell" : null,
                accepted ? null : "accuracy"
        );
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
