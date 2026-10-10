package com.sensareth.roamglyph.history;

import androidx.annotation.NonNull;

import com.sensareth.roamglyph.data.GpsPointEntity;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;

public final class GpxWriter implements Closeable {
    public static final long SEGMENT_GAP_MS = 120_000L;

    private final BufferedWriter writer;
    private boolean segmentOpen;
    private boolean finished;
    private long previousTimestampMs = Long.MIN_VALUE;
    private long pointsWritten;
    private int segmentsWritten;

    public GpxWriter(
            @NonNull OutputStream output,
            @NonNull String trackName,
            long startedAtMs
    ) throws IOException {
        writer = new BufferedWriter(
                new OutputStreamWriter(output, StandardCharsets.UTF_8),
                64 * 1024
        );

        writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        writer.write(
                "<gpx version=\"1.1\" creator=\"Roamglyph\" " +
                "xmlns=\"http://www.topografix.com/GPX/1/1\" " +
                "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
                "xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 " +
                "http://www.topografix.com/GPX/1/1/gpx.xsd\">\n"
        );
        writer.write("  <metadata><time>");
        writer.write(Instant.ofEpochMilli(Math.max(0L, startedAtMs)).toString());
        writer.write("</time></metadata>\n");
        writer.write("  <trk><name>");
        writer.write(escapeXml(trackName));
        writer.write("</name>\n");
    }

    public void append(@NonNull GpsPointEntity point) throws IOException {
        if (finished) throw new IllegalStateException("GPX writer already finished");
        if (!point.acceptedForExploration) return;
        if (!Double.isFinite(point.latitude)
                || !Double.isFinite(point.longitude)
                || point.latitude < -90.0
                || point.latitude > 90.0
                || point.longitude < -180.0
                || point.longitude > 180.0) {
            return;
        }

        boolean gap = previousTimestampMs != Long.MIN_VALUE
                && point.timestampMs > previousTimestampMs
                && point.timestampMs - previousTimestampMs > SEGMENT_GAP_MS;

        if (!segmentOpen || gap) {
            if (segmentOpen) {
                writer.write("    </trkseg>\n");
            }
            writer.write("    <trkseg>\n");
            segmentOpen = true;
            segmentsWritten++;
        }

        writer.write(String.format(
                Locale.ROOT,
                "      <trkpt lat=\"%.7f\" lon=\"%.7f\">",
                point.latitude,
                point.longitude
        ));

        if (point.altitudeM != null && Double.isFinite(point.altitudeM)) {
            writer.write(String.format(
                    Locale.ROOT,
                    "<ele>%.2f</ele>",
                    point.altitudeM
            ));
        }

        writer.write("<time>");
        writer.write(Instant.ofEpochMilli(Math.max(0L, point.timestampMs)).toString());
        writer.write("</time></trkpt>\n");

        previousTimestampMs = point.timestampMs;
        pointsWritten++;
    }

    public long getPointsWritten() {
        return pointsWritten;
    }

    public int getSegmentsWritten() {
        return segmentsWritten;
    }

    public void finish() throws IOException {
        if (finished) return;

        if (segmentOpen) {
            writer.write("    </trkseg>\n");
        }
        writer.write("  </trk>\n");
        writer.write("</gpx>\n");
        writer.flush();
        finished = true;
    }

    @Override
    public void close() throws IOException {
        try {
            finish();
        } finally {
            writer.close();
        }
    }

    @NonNull
    static String escapeXml(@NonNull String text) {
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
