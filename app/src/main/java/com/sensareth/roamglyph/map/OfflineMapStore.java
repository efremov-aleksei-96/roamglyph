package com.sensareth.roamglyph.map;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public final class OfflineMapStore {
    private static final int HEADER_SIZE = 127;
    private static final byte[] MAGIC = new byte[]{
            'P', 'M', 'T', 'i', 'l', 'e', 's'
    };
    private static final int PMTILES_VERSION = 3;
    private static final int TILE_TYPE_MVT = 1;
    private static final String DIRECTORY_NAME = "maps";
    private static final String FILE_NAME = "basemap.pmtiles";

    private OfflineMapStore() {
    }

    @NonNull
    public static File mapFile(@NonNull Context context) {
        return new File(new File(context.getFilesDir(), DIRECTORY_NAME), FILE_NAME);
    }

    public static boolean hasValidMap(@NonNull Context context) {
        File file = mapFile(context);
        if (!file.isFile()) return false;
        try {
            validateVectorPmtiles(file);
            return true;
        } catch (IOException error) {
            return false;
        }
    }

    public static long importFromUri(
            @NonNull Context context,
            @NonNull Uri uri
    ) throws IOException {
        File directory = new File(context.getFilesDir(), DIRECTORY_NAME);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create offline map directory");
        }

        File destination = mapFile(context);
        File temp = new File(directory, FILE_NAME + ".part");

        if (temp.exists() && !temp.delete()) {
            throw new IOException("Could not replace temporary map file");
        }

        long copied = 0L;
        try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new IOException("Could not open selected PMTiles file");

            try (
                    BufferedInputStream input = new BufferedInputStream(raw, 1024 * 1024);
                    BufferedOutputStream output = new BufferedOutputStream(
                            new FileOutputStream(temp),
                            1024 * 1024
                    )
            ) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    copied += read;
                }
            }

            validateVectorPmtiles(temp);

            try {
                Files.move(
                        temp.toPath(),
                        destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(
                        temp.toPath(),
                        destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                );
            }

            return copied;
        } finally {
            if (temp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        }
    }

    public static boolean delete(@NonNull Context context) {
        File file = mapFile(context);
        return !file.exists() || file.delete();
    }

    @NonNull
    public static String sourceUri(@NonNull Context context) throws IOException {
        File file = mapFile(context);
        validateVectorPmtiles(file);
        return "pmtiles://file://" + file.getAbsolutePath();
    }

    public static void validateVectorPmtiles(@NonNull File file) throws IOException {
        if (!file.isFile() || file.length() < HEADER_SIZE) {
            throw new IOException("Selected file is not a PMTiles v3 archive");
        }

        byte[] header = new byte[HEADER_SIZE];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < header.length) {
                int read = input.read(header, offset, header.length - offset);
                if (read < 0) break;
                offset += read;
            }
            if (offset != header.length) {
                throw new IOException("Incomplete PMTiles header");
            }
        }

        for (int i = 0; i < MAGIC.length; i++) {
            if (header[i] != MAGIC[i]) {
                throw new IOException("Selected file does not have a PMTiles header");
            }
        }

        int version = header[7] & 0xFF;
        if (version != PMTILES_VERSION) {
            throw new IOException("Only PMTiles v3 is supported");
        }

        int tileType = header[99] & 0xFF;
        if (tileType != TILE_TYPE_MVT) {
            throw new IOException("Only vector MVT PMTiles archives are supported");
        }
    }
}
