package com.sensareth.roamglyph.updates;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class UpdateDownloadRecordTest {
    private static final String TAG = "dev-vc35-gabcdef01";
    private static final String NAME = "roamglyph-0.5.0-dev.35-gabcdef01.apk";
    private static final String URL = "https://github.com/efremov-aleksei-96/"
            + "roamglyph/releases/download/" + TAG + "/" + NAME;
    private static final String DIGEST = "a1".repeat(32);

    private static UpdateDownloadRecord record(long id, int code, String tag,
                                               String name, String url, String digest) {
        return new UpdateDownloadRecord(id, code, tag, name, url, digest);
    }

    @Test public void validPersistedDownloadRecoversAcrossAppRestarts() {
        assertTrue(record(1234, 35, TAG, NAME, URL, DIGEST).isValid());
    }

    @Test public void rejectsCorruptIdentityAndWrongCode() {
        assertFalse(record(-1, 35, TAG, NAME, URL, DIGEST).isValid());
        assertFalse(record(1234, 34, TAG, NAME, URL, DIGEST).isValid());
        assertFalse(record(1234, 35, TAG, NAME.replace("35", "34"), URL, DIGEST)
                .isValid());
        assertFalse(record(1234, 35, "dev-vc35-g00000000", NAME, URL, DIGEST)
                .isValid());
    }

    @Test public void rejectsUntrustedOriginsOrMalformedDigests() {
        assertFalse(record(1234, 35, TAG, NAME,
                "https://mirror.example.org/update.apk", DIGEST).isValid());
        assertFalse(record(1234, 35, TAG, NAME, URL, "deadbeef").isValid());
        assertFalse(record(1234, 35, TAG, NAME, URL, null).isValid());
    }

    @Test public void deniesPathEscapeAndUnexpectedApkNames() {
        assertFalse(record(1234, 35, TAG, "../" + NAME, URL, DIGEST).isValid());
        assertFalse(record(1234, 35, TAG, "foo.apk", URL, DIGEST).isValid());
    }
}
