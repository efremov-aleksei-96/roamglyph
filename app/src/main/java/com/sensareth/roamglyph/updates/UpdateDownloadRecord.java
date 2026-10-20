package com.sensareth.roamglyph.updates;

/**
 * Persistent identity of a verified GitHub development release download.
 * Contains no credentials or GPS data. Serializable through private preferences.
 */
final class UpdateDownloadRecord {
    final long id;
    final int code;
    final String tag;
    final String name;
    final String url;
    final String digest;

    UpdateDownloadRecord(long id, int code, String tag, String name,
                         String url, String digest) {
        this.id = id;
        this.code = code;
        this.tag = tag;
        this.name = name;
        this.url = url;
        this.digest = digest;
    }

    boolean isValid() {
        return id >= 0L && code > 0
                && DevelopmentReleasePolicy.versionCode(tag) == code
                && DevelopmentReleasePolicy.isExpectedApk(tag, name)
                && DevelopmentReleasePolicy.isTrustedAssetUrl(url)
                && digest != null && digest.matches("[0-9a-f]{64}");
    }
}
