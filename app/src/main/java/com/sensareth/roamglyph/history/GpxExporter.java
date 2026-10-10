package com.sensareth.roamglyph.history;

import androidx.annotation.NonNull;

import com.sensareth.roamglyph.data.ExplorationRepository;
import com.sensareth.roamglyph.data.GpsPointEntity;
import com.sensareth.roamglyph.data.SessionEntity;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

public final class GpxExporter {
    private static final int PAGE_SIZE = 1000;

    private GpxExporter() {
    }

    @NonNull
    public static ExportResult exportSession(
            @NonNull OutputStream output,
            @NonNull ExplorationRepository repository,
            @NonNull SessionEntity session,
            long throughTimestampMs,
            @NonNull String trackName
    ) throws IOException {
        long expectedPoints = repository.countAcceptedGpsPointsForSession(
                session.sessionId,
                throughTimestampMs
        );

        int offset = 0;
        try (GpxWriter writer = new GpxWriter(output, trackName, session.startedAtMs)) {
            while (offset < expectedPoints) {
                List<GpsPointEntity> page =
                        repository.loadAcceptedGpsPointsForSessionPage(
                                session.sessionId,
                                throughTimestampMs,
                                PAGE_SIZE,
                                offset
                        );
                if (page.isEmpty()) break;

                for (GpsPointEntity point : page) {
                    writer.append(point);
                }

                offset += page.size();
                if (page.size() < PAGE_SIZE) break;
            }

            writer.finish();
            return new ExportResult(
                    writer.getPointsWritten(),
                    writer.getSegmentsWritten()
            );
        }
    }

    public static final class ExportResult {
        public final long points;
        public final int segments;

        ExportResult(long points, int segments) {
            this.points = points;
            this.segments = segments;
        }
    }
}
