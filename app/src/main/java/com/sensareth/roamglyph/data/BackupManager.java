package com.sensareth.roamglyph.data;

import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.uber.h3core.H3Core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class BackupManager {
    public static final String BACKUP_FORMAT = "roamglyph-backup";
    public static final int BACKUP_VERSION = 3;
    private static final int PREVIOUS_BACKUP_VERSION = 2;

    private static final String LEGACY_FORMAT = "roamglyph-history";
    private static final int LEGACY_VERSION = 1;
    private static final int H3_RESOLUTION = 13;
    private static final int PAGE_SIZE = 1000;
    private static final long MAX_IMPORT_BYTES = 2L * 1024L * 1024L * 1024L;

    private BackupManager() {
    }

    public static void exportBackup(
            @NonNull OutputStream output,
            @NonNull ExplorationRepository repository
    ) throws IOException {
        JsonWriter writer = new JsonWriter(new OutputStreamWriter(
                new BufferedOutputStream(output),
                StandardCharsets.UTF_8
        ));

        try {
            writer.beginObject();
            writer.name("format").value(BACKUP_FORMAT);
            writer.name("version").value(BACKUP_VERSION);
            writer.name("h3_resolution").value(H3_RESOLUTION);
            writer.name("exported_at_ms").value(System.currentTimeMillis());
            writer.name("cell_count").value(repository.countVisitedCells());
            writer.name("session_count").value(repository.countSessions());
            writer.name("gps_point_count").value(repository.countGpsPoints());
            writer.name("discovery_count").value(repository.countDiscoveries());

            writer.name("visited_cells");
            writer.beginArray();
            int offset = 0;
            while (true) {
                List<VisitedCellEntity> page =
                        repository.loadVisitedCellsPage(PAGE_SIZE, offset);
                if (page.isEmpty()) break;
                for (VisitedCellEntity cell : page) {
                    writeVisitedCell(writer, cell);
                }
                offset += page.size();
            }
            writer.endArray();

            writer.name("sessions");
            writer.beginArray();
            offset = 0;
            while (true) {
                List<SessionEntity> page =
                        repository.loadSessionsPage(PAGE_SIZE, offset);
                if (page.isEmpty()) break;
                for (SessionEntity session : page) {
                    writeSession(writer, session);
                }
                offset += page.size();
            }
            writer.endArray();

            writer.name("gps_points");
            writer.beginArray();
            offset = 0;
            while (true) {
                List<GpsPointEntity> page =
                        repository.loadGpsPointsPage(PAGE_SIZE, offset);
                if (page.isEmpty()) break;
                for (GpsPointEntity point : page) {
                    writeGpsPoint(writer, point);
                }
                offset += page.size();
            }
            writer.endArray();

            writer.name("discoveries");
            writer.beginArray();
            offset = 0;
            while (true) {
                List<DiscoveryEntity> page =
                        repository.loadDiscoveriesPage(PAGE_SIZE, offset);
                if (page.isEmpty()) break;
                for (DiscoveryEntity discovery : page) {
                    writeDiscovery(writer, discovery);
                }
                offset += page.size();
            }
            writer.endArray();

            writer.endObject();
            writer.flush();
        } finally {
            writer.close();
        }
    }

    @NonNull
    public static File copyToPrivateTemp(
            @NonNull InputStream input,
            @NonNull File cacheDir
    ) throws IOException {
        File file = File.createTempFile("roamglyph-import-", ".json", cacheDir);
        long total = 0L;

        try (InputStream in = new BufferedInputStream(input);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_IMPORT_BYTES) {
                    throw new IOException("Backup exceeds 2 GiB safety limit");
                }
                out.write(buffer, 0, read);
            }
        } catch (IOException error) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            throw error;
        }

        return file;
    }

    @NonNull
    public static ImportResult importBackup(
            @NonNull File file,
            @NonNull ExplorationRepository repository,
            @NonNull H3Core h3
    ) throws IOException {
        Header header = validate(file, h3);

        if (LEGACY_FORMAT.equals(header.format)) {
            return importLegacyV1(file, repository, h3);
        }

        if (!BACKUP_FORMAT.equals(header.format)
                || (header.version != PREVIOUS_BACKUP_VERSION
                && header.version != BACKUP_VERSION)) {
            throw new IOException("Unsupported Roamglyph backup");
        }

        int newCells = importV2Cells(file, repository);
        int newSessions = importV2Sessions(file, repository);
        long newPoints = importV2Points(file, repository);
        int newDiscoveries = header.version >= 3
                ? importV3Discoveries(file, repository)
                : 0;

        return new ImportResult(
                newCells,
                repository.countVisitedCells(),
                newSessions,
                newPoints,
                newDiscoveries,
                0
        );
    }

    private static Header validate(File file, H3Core h3) throws IOException {
        Header header = readHeader(file);

        if (!BACKUP_FORMAT.equals(header.format)
                && !LEGACY_FORMAT.equals(header.format)) {
            throw new IOException("Unsupported Roamglyph backup format");
        }

        if (BACKUP_FORMAT.equals(header.format)
                && header.version != PREVIOUS_BACKUP_VERSION
                && header.version != BACKUP_VERSION) {
            throw new IOException("Unsupported Roamglyph backup version");
        }

        if (LEGACY_FORMAT.equals(header.format) && header.version != LEGACY_VERSION) {
            throw new IOException("Unsupported legacy Roamglyph history version");
        }

        Set<String> sessionIds = new HashSet<>();
        Set<String> pointSessionIds = new HashSet<>();
        boolean sawVisitedCells = false;
        boolean sawSessions = false;
        boolean sawGpsPoints = false;
        boolean sawDiscoveries = false;
        boolean sawLegacyCells = false;

        try (JsonReader reader = newReader(file)) {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();

                if (BACKUP_FORMAT.equals(header.format)) {
                    switch (name) {
                        case "visited_cells":
                            sawVisitedCells = true;
                            reader.beginArray();
                            while (reader.hasNext()) {
                                VisitedCellEntity cell = readVisitedCell(reader, null);
                                validateCell(h3, cell.h3);
                            }
                            reader.endArray();
                            break;
                        case "sessions":
                            sawSessions = true;
                            reader.beginArray();
                            while (reader.hasNext()) {
                                SessionEntity session = readSession(reader, false);
                                if (!sessionIds.add(session.sessionId)) {
                                    throw new IOException("Duplicate session id in backup");
                                }
                            }
                            reader.endArray();
                            break;
                        case "gps_points":
                            sawGpsPoints = true;
                            reader.beginArray();
                            while (reader.hasNext()) {
                                GpsPointEntity point = readGpsPoint(reader);
                                pointSessionIds.add(point.sessionId);
                                if (point.acceptedForExploration && point.h3 != null) {
                                    validateCell(h3, point.h3);
                                }
                            }
                            reader.endArray();
                            break;
                        case "discoveries":
                            sawDiscoveries = true;
                            reader.beginArray();
                            while (reader.hasNext()) {
                                DiscoveryEntity discovery = readDiscovery(reader);
                                validateDiscovery(h3, discovery);
                            }
                            reader.endArray();
                            break;
                        default:
                            reader.skipValue();
                    }
                } else if ("cells".equals(name)) {
                    sawLegacyCells = true;
                    reader.beginArray();
                    while (reader.hasNext()) {
                        validateCell(h3, reader.nextString());
                    }
                    reader.endArray();
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
        } catch (IllegalStateException | NumberFormatException error) {
            throw new IOException("Malformed Roamglyph backup", error);
        }

        if (BACKUP_FORMAT.equals(header.format)) {
            if (!sawVisitedCells || !sawSessions || !sawGpsPoints) {
                throw new IOException("Incomplete Roamglyph backup");
            }
            if (header.version >= 3 && !sawDiscoveries) {
                throw new IOException("Incomplete Roamglyph backup");
            }
            if (!sessionIds.containsAll(pointSessionIds)) {
                throw new IOException("GPS point references a missing session");
            }
        } else if (!sawLegacyCells) {
            throw new IOException("Incomplete legacy Roamglyph history");
        }

        return header;
    }

    private static Header readHeader(File file) throws IOException {
        String format = null;
        int version = -1;
        int resolution = -1;

        try (JsonReader reader = newReader(file)) {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                switch (name) {
                    case "format":
                        format = reader.nextString();
                        break;
                    case "version":
                        version = reader.nextInt();
                        break;
                    case "h3_resolution":
                        resolution = reader.nextInt();
                        break;
                    default:
                        reader.skipValue();
                }
            }
            reader.endObject();
        } catch (IllegalStateException | NumberFormatException error) {
            throw new IOException("Malformed Roamglyph backup header", error);
        }

        if (format == null || version < 0 || resolution != H3_RESOLUTION) {
            throw new IOException("Missing or unsupported Roamglyph backup header");
        }

        return new Header(format, version);
    }

    private static ImportResult importLegacyV1(
            File file,
            ExplorationRepository repository,
            H3Core h3
    ) throws IOException {
        Set<String> batch = new HashSet<>();
        int added = 0;

        try (JsonReader reader = newReader(file)) {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!"cells".equals(name)) {
                    reader.skipValue();
                    continue;
                }

                reader.beginArray();
                while (reader.hasNext()) {
                    String cell = reader.nextString();
                    validateCell(h3, cell);
                    batch.add(cell);

                    if (batch.size() >= PAGE_SIZE) {
                        added += repository.importCells(batch, null, "import-v1");
                        batch.clear();
                    }
                }
                reader.endArray();
            }
            reader.endObject();
        }

        if (!batch.isEmpty()) {
            added += repository.importCells(batch, null, "import-v1");
        }

        return new ImportResult(
                added,
                repository.countVisitedCells(),
                0,
                0L,
                0,
                0
        );
    }

    private static int importV2Cells(
            File file,
            ExplorationRepository repository
    ) throws IOException {
        List<VisitedCellEntity> batch = new ArrayList<>(PAGE_SIZE);
        int inserted = 0;

        try (JsonReader reader = newReader(file)) {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!"visited_cells".equals(name)) {
                    reader.skipValue();
                    continue;
                }

                reader.beginArray();
                while (reader.hasNext()) {
                    VisitedCellEntity cell = readVisitedCell(reader, "import-v2");
                    batch.add(cell);
                    if (batch.size() >= PAGE_SIZE) {
                        inserted += repository.importVisitedCells(batch);
                        batch.clear();
                    }
                }
                reader.endArray();
            }
            reader.endObject();
        }

        if (!batch.isEmpty()) {
            inserted += repository.importVisitedCells(batch);
        }

        return inserted;
    }

    private static int importV2Sessions(
            File file,
            ExplorationRepository repository
    ) throws IOException {
        List<SessionEntity> batch = new ArrayList<>(PAGE_SIZE);
        int inserted = 0;

        try (JsonReader reader = newReader(file)) {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!"sessions".equals(name)) {
                    reader.skipValue();
                    continue;
                }

                reader.beginArray();
                while (reader.hasNext()) {
                    batch.add(readSession(reader, true));
                    if (batch.size() >= PAGE_SIZE) {
                        inserted += repository.importSessions(batch);
                        batch.clear();
                    }
                }
                reader.endArray();
            }
            reader.endObject();
        }

        if (!batch.isEmpty()) {
            inserted += repository.importSessions(batch);
        }

        return inserted;
    }

    private static long importV2Points(
            File file,
            ExplorationRepository repository
    ) throws IOException {
        List<GpsPointEntity> batch = new ArrayList<>(PAGE_SIZE);
        long inserted = 0L;

        try (JsonReader reader = newReader(file)) {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!"gps_points".equals(name)) {
                    reader.skipValue();
                    continue;
                }

                reader.beginArray();
                while (reader.hasNext()) {
                    batch.add(readGpsPoint(reader));
                    if (batch.size() >= PAGE_SIZE) {
                        inserted += repository.importGpsPoints(batch);
                        batch.clear();
                    }
                }
                reader.endArray();
            }
            reader.endObject();
        }

        if (!batch.isEmpty()) {
            inserted += repository.importGpsPoints(batch);
        }

        return inserted;
    }

    private static int importV3Discoveries(
            File file,
            ExplorationRepository repository
    ) throws IOException {
        List<DiscoveryEntity> batch = new ArrayList<>(PAGE_SIZE);
        int inserted = 0;

        try (JsonReader reader = newReader(file)) {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!"discoveries".equals(name)) {
                    reader.skipValue();
                    continue;
                }

                reader.beginArray();
                while (reader.hasNext()) {
                    batch.add(readDiscovery(reader));
                    if (batch.size() >= PAGE_SIZE) {
                        inserted += repository.importDiscoveries(batch);
                        batch.clear();
                    }
                }
                reader.endArray();
            }
            reader.endObject();
        }

        if (!batch.isEmpty()) {
            inserted += repository.importDiscoveries(batch);
        }

        return inserted;
    }

    private static void writeVisitedCell(JsonWriter writer, VisitedCellEntity cell)
            throws IOException {
        writer.beginObject();
        writer.name("h3").value(cell.h3);
        writer.name("first_seen_at_ms");
        writeNullableLong(writer, cell.firstSeenAtMs);
        writer.name("source").value(cell.source);
        writer.endObject();
    }

    private static void writeSession(JsonWriter writer, SessionEntity session)
            throws IOException {
        writer.beginObject();
        writer.name("session_id").value(session.sessionId);
        writer.name("started_at_ms").value(session.startedAtMs);
        writer.name("ended_at_ms");
        writeNullableLong(writer, session.endedAtMs);
        writer.name("distance_m").value(session.distanceM);
        writer.name("accepted_points").value(session.acceptedPoints);
        writer.name("new_cells").value(session.newCells);
        writer.name("source").value(session.source);
        writer.endObject();
    }

    private static void writeGpsPoint(JsonWriter writer, GpsPointEntity point)
            throws IOException {
        writer.beginObject();
        writer.name("point_id").value(point.pointId);
        writer.name("session_id").value(point.sessionId);
        writer.name("timestamp_ms").value(point.timestampMs);
        writer.name("latitude").value(point.latitude);
        writer.name("longitude").value(point.longitude);
        writer.name("accuracy_m").value(point.accuracyM);
        writer.name("speed_mps");
        writeNullableNumber(writer, point.speedMps);
        writer.name("altitude_m");
        writeNullableNumber(writer, point.altitudeM);
        writer.name("provider");
        writeNullableString(writer, point.provider);
        writer.name("accepted_for_exploration").value(point.acceptedForExploration);
        writer.name("h3");
        writeNullableString(writer, point.h3);
        writer.name("rejection_reason");
        writeNullableString(writer, point.rejectionReason);
        writer.endObject();
    }

    private static void writeDiscovery(
            JsonWriter writer,
            DiscoveryEntity discovery
    ) throws IOException {
        writer.beginObject();
        writer.name("discovery_id").value(discovery.discoveryId);
        writer.name("name").value(discovery.name);
        writer.name("category").value(discovery.category);
        writer.name("subclass");
        writeNullableString(writer, discovery.subclass);
        writer.name("latitude").value(discovery.latitude);
        writer.name("longitude").value(discovery.longitude);
        writer.name("h3").value(discovery.h3);
        writer.name("discovered_at_ms");
        writeNullableLong(writer, discovery.discoveredAtMs);
        writer.name("source").value(discovery.source);
        writer.endObject();
    }

    private static VisitedCellEntity readVisitedCell(
            JsonReader reader,
            @Nullable String sourceOverride
    ) throws IOException {
        String h3 = null;
        Long firstSeenAtMs = null;
        String source = "unknown";

        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "h3":
                    h3 = reader.nextString();
                    break;
                case "first_seen_at_ms":
                    firstSeenAtMs = readNullableLong(reader);
                    break;
                case "source":
                    source = readNullableString(reader);
                    if (source == null) source = "unknown";
                    break;
                default:
                    reader.skipValue();
            }
        }
        reader.endObject();

        if (h3 == null || h3.isBlank()) throw new IOException("Missing H3 cell");
        if (sourceOverride != null) source = sourceOverride;
        return new VisitedCellEntity(h3, firstSeenAtMs, source);
    }

    private static SessionEntity readSession(
            JsonReader reader,
            boolean imported
    ) throws IOException {
        String id = null;
        long started = 0L;
        Long ended = null;
        double distance = 0.0;
        long acceptedPoints = 0L;
        long newCells = 0L;
        String source = "unknown";

        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "session_id":
                    id = reader.nextString();
                    break;
                case "started_at_ms":
                    started = reader.nextLong();
                    break;
                case "ended_at_ms":
                    ended = readNullableLong(reader);
                    break;
                case "distance_m":
                    distance = reader.nextDouble();
                    break;
                case "accepted_points":
                    acceptedPoints = reader.nextLong();
                    break;
                case "new_cells":
                    newCells = reader.nextLong();
                    break;
                case "source":
                    source = readNullableString(reader);
                    if (source == null) source = "unknown";
                    break;
                default:
                    reader.skipValue();
            }
        }
        reader.endObject();

        if (id == null || id.isBlank()) throw new IOException("Missing session id");
        if (started <= 0L || distance < 0.0 || acceptedPoints < 0L || newCells < 0L) {
            throw new IOException("Invalid session");
        }

        if (imported) source = "import-v2";

        return new SessionEntity(
                id,
                started,
                ended,
                distance,
                acceptedPoints,
                newCells,
                source
        );
    }

    private static GpsPointEntity readGpsPoint(JsonReader reader) throws IOException {
        String pointId = null;
        String sessionId = null;
        long timestamp = 0L;
        double latitude = Double.NaN;
        double longitude = Double.NaN;
        float accuracy = 0f;
        Float speed = null;
        Double altitude = null;
        String provider = null;
        boolean accepted = false;
        String h3 = null;
        String rejection = null;

        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "point_id":
                    pointId = reader.nextString();
                    break;
                case "session_id":
                    sessionId = reader.nextString();
                    break;
                case "timestamp_ms":
                    timestamp = reader.nextLong();
                    break;
                case "latitude":
                    latitude = reader.nextDouble();
                    break;
                case "longitude":
                    longitude = reader.nextDouble();
                    break;
                case "accuracy_m":
                    accuracy = (float) reader.nextDouble();
                    break;
                case "speed_mps":
                    Double speedValue = readNullableDouble(reader);
                    speed = speedValue == null ? null : speedValue.floatValue();
                    break;
                case "altitude_m":
                    altitude = readNullableDouble(reader);
                    break;
                case "provider":
                    provider = readNullableString(reader);
                    break;
                case "accepted_for_exploration":
                    accepted = reader.nextBoolean();
                    break;
                case "h3":
                    h3 = readNullableString(reader);
                    break;
                case "rejection_reason":
                    rejection = readNullableString(reader);
                    break;
                default:
                    reader.skipValue();
            }
        }
        reader.endObject();

        if (pointId == null || pointId.isBlank()
                || sessionId == null || sessionId.isBlank()) {
            throw new IOException("Missing GPS point id/session");
        }

        GpsPointEntity point = new GpsPointEntity(
                pointId,
                sessionId,
                timestamp,
                latitude,
                longitude,
                accuracy,
                speed,
                altitude,
                provider,
                accepted,
                h3,
                rejection
        );
        validatePoint(point);
        return point;
    }

    private static DiscoveryEntity readDiscovery(JsonReader reader) throws IOException {
        String id = null;
        String name = null;
        String category = null;
        String subclass = null;
        double latitude = Double.NaN;
        double longitude = Double.NaN;
        String h3 = null;
        Long discoveredAtMs = null;
        String source = "unknown";

        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "discovery_id":
                    id = reader.nextString();
                    break;
                case "name":
                    name = reader.nextString();
                    break;
                case "category":
                    category = reader.nextString();
                    break;
                case "subclass":
                    subclass = readNullableString(reader);
                    break;
                case "latitude":
                    latitude = reader.nextDouble();
                    break;
                case "longitude":
                    longitude = reader.nextDouble();
                    break;
                case "h3":
                    h3 = reader.nextString();
                    break;
                case "discovered_at_ms":
                    discoveredAtMs = readNullableLong(reader);
                    break;
                case "source":
                    source = readNullableString(reader);
                    if (source == null) source = "unknown";
                    break;
                default:
                    reader.skipValue();
            }
        }
        reader.endObject();

        if (id == null || id.isBlank()
                || name == null || name.isBlank()
                || category == null || category.isBlank()
                || h3 == null || h3.isBlank()) {
            throw new IOException("Invalid discovery");
        }

        return new DiscoveryEntity(
                id,
                name,
                category,
                subclass,
                latitude,
                longitude,
                h3,
                discoveredAtMs,
                source
        );
    }

    private static void validatePoint(GpsPointEntity point) throws IOException {
        if (point.timestampMs <= 0L
                || !Double.isFinite(point.latitude)
                || !Double.isFinite(point.longitude)
                || point.latitude < -90.0
                || point.latitude > 90.0
                || point.longitude < -180.0
                || point.longitude > 180.0
                || !Float.isFinite(point.accuracyM)
                || point.accuracyM < 0f) {
            throw new IOException("Invalid GPS point");
        }

        if (point.acceptedForExploration && (point.h3 == null || point.h3.isBlank())) {
            throw new IOException("Accepted GPS point is missing H3 cell");
        }
    }

    private static void validateDiscovery(
            H3Core h3,
            DiscoveryEntity discovery
    ) throws IOException {
        if (!Double.isFinite(discovery.latitude)
                || !Double.isFinite(discovery.longitude)
                || discovery.latitude < -90.0
                || discovery.latitude > 90.0
                || discovery.longitude < -180.0
                || discovery.longitude > 180.0
                || (discovery.discoveredAtMs != null && discovery.discoveredAtMs <= 0L)) {
            throw new IOException("Invalid discovery");
        }

        validateCell(h3, discovery.h3);

        try {
            String expected;
            synchronized (h3) {
                expected = h3.latLngToCellAddress(
                        discovery.latitude,
                        discovery.longitude,
                        H3_RESOLUTION
                );
            }
            if (!expected.equals(discovery.h3)) {
                throw new IOException("Discovery H3 does not match coordinates");
            }
        } catch (RuntimeException error) {
            throw new IOException("Invalid discovery coordinates", error);
        }
    }

    private static void validateCell(H3Core h3, String cell) throws IOException {
        if (cell == null || cell.isBlank()) throw new IOException("Blank H3 cell");
        try {
            synchronized (h3) {
                h3.cellToBoundary(cell);
            }
        } catch (Throwable error) {
            throw new IOException("Invalid H3 cell", error);
        }
    }

    private static JsonReader newReader(File file) throws IOException {
        return new JsonReader(new InputStreamReader(
                new BufferedInputStream(new FileInputStream(file)),
                StandardCharsets.UTF_8
        ));
    }

    private static void writeNullableLong(JsonWriter writer, @Nullable Long value)
            throws IOException {
        if (value == null) writer.nullValue();
        else writer.value(value);
    }

    private static void writeNullableNumber(JsonWriter writer, @Nullable Number value)
            throws IOException {
        if (value == null) writer.nullValue();
        else writer.value(value);
    }

    private static void writeNullableString(JsonWriter writer, @Nullable String value)
            throws IOException {
        if (value == null) writer.nullValue();
        else writer.value(value);
    }

    @Nullable
    private static Long readNullableLong(JsonReader reader) throws IOException {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull();
            return null;
        }
        return reader.nextLong();
    }

    @Nullable
    private static Double readNullableDouble(JsonReader reader) throws IOException {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull();
            return null;
        }
        return reader.nextDouble();
    }

    @Nullable
    private static String readNullableString(JsonReader reader) throws IOException {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull();
            return null;
        }
        return reader.nextString();
    }

    private static final class Header {
        final String format;
        final int version;

        Header(String format, int version) {
            this.format = format;
            this.version = version;
        }
    }

    public static final class ImportResult {
        public final int newCells;
        public final int totalCells;
        public final int newSessions;
        public final long newGpsPoints;
        public final int newDiscoveries;
        public final int invalidItems;

        ImportResult(
                int newCells,
                int totalCells,
                int newSessions,
                long newGpsPoints,
                int newDiscoveries,
                int invalidItems
        ) {
            this.newCells = newCells;
            this.totalCells = totalCells;
            this.newSessions = newSessions;
            this.newGpsPoints = newGpsPoints;
            this.newDiscoveries = newDiscoveries;
            this.invalidItems = invalidItems;
        }
    }
}
