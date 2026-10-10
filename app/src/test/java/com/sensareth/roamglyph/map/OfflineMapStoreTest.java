package com.sensareth.roamglyph.map;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import static org.junit.Assert.assertThrows;

public class OfflineMapStoreTest {
    @Test
    public void acceptsPmtilesV3MvtHeader() throws Exception {
        File file = writeHeader(3, 1, true);
        try {
            OfflineMapStore.validateVectorPmtiles(file);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    @Test
    public void rejectsWrongMagic() throws Exception {
        File file = writeHeader(3, 1, false);
        try {
            assertThrows(
                    IOException.class,
                    () -> OfflineMapStore.validateVectorPmtiles(file)
            );
        } finally {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    @Test
    public void rejectsOldPmtilesVersion() throws Exception {
        File file = writeHeader(2, 1, true);
        try {
            assertThrows(
                    IOException.class,
                    () -> OfflineMapStore.validateVectorPmtiles(file)
            );
        } finally {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    @Test
    public void rejectsRasterArchiveForOpenMapTilesStyle() throws Exception {
        File file = writeHeader(3, 2, true);
        try {
            assertThrows(
                    IOException.class,
                    () -> OfflineMapStore.validateVectorPmtiles(file)
            );
        } finally {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private static File writeHeader(
            int version,
            int tileType,
            boolean validMagic
    ) throws Exception {
        byte[] header = new byte[127];
        byte[] magic = new byte[]{'P', 'M', 'T', 'i', 'l', 'e', 's'};
        System.arraycopy(magic, 0, header, 0, magic.length);
        if (!validMagic) header[0] = 'X';
        header[7] = (byte) version;
        header[99] = (byte) tileType;

        File file = File.createTempFile("roamglyph-pmtiles-", ".pmtiles");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(header);
        }
        return file;
    }
}
