package com.sensareth.roamglyph.updates;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DevelopmentReleasePolicyTest {
    private static final String TAG = "dev-vc26-g70545883";

    @Test public void acceptsOnlyStrictDevelopmentTags() {
        assertEquals(26, DevelopmentReleasePolicy.versionCode(TAG));
        assertEquals(123, DevelopmentReleasePolicy.versionCode("dev-vc123-gabcdef01"));
        assertEquals(-1, DevelopmentReleasePolicy.versionCode("v0.5.0"));
        assertEquals(-1, DevelopmentReleasePolicy.versionCode("dev-vc0-gabcdef01"));
        assertEquals(-1, DevelopmentReleasePolicy.versionCode("dev-vc25-ggarbage"));
        assertEquals(-1, DevelopmentReleasePolicy.versionCode("dev-vc999999999999-gabcdef01"));
        assertEquals(-1, DevelopmentReleasePolicy.versionCode(null));
    }

    @Test public void apkNameMustMatchVersionAndSourceHash() {
        assertTrue(DevelopmentReleasePolicy.isExpectedApk(TAG,
                "roamglyph-0.5.0-dev.26-g70545883.apk"));
        assertFalse(DevelopmentReleasePolicy.isExpectedApk(TAG,
                "roamglyph-0.5.0-dev.25-g70545883.apk"));
        assertFalse(DevelopmentReleasePolicy.isExpectedApk(TAG, "other.apk"));
        assertFalse(DevelopmentReleasePolicy.isExpectedApk("v0.5.0",
                "roamglyph-0.5.0-dev.26-g70545883.apk"));
    }

    @Test public void sha256MetadataRequiredAndValidated() {
        assertEquals("ab".repeat(32), DevelopmentReleasePolicy.digestHex(
                "sha256:" + "AB".repeat(32)));
        assertNull(DevelopmentReleasePolicy.digestHex("sha1:" + "00".repeat(20)));
        assertNull(DevelopmentReleasePolicy.digestHex("sha256:xyz"));
        assertNull(DevelopmentReleasePolicy.digestHex(""));
    }

    @Test public void onlyFirstPartyPublicReleaseAssetsAccepted() {
        assertTrue(DevelopmentReleasePolicy.isTrustedAssetUrl(
                "https://github.com/efremov-aleksei-96/roamglyph/releases/"
                + "download/dev-vc26-g70545883/roamglyph-0.5.0-dev.26-g70545883.apk"));
        assertFalse(DevelopmentReleasePolicy.isTrustedAssetUrl(
                "https://somewhere.example/roamglyph.apk"));
        assertFalse(DevelopmentReleasePolicy.isTrustedAssetUrl(
                "http://github.com/efremov-aleksei-96/roamglyph/releases/download/asset"));
        assertFalse(DevelopmentReleasePolicy.isTrustedAssetUrl(
                "https://github.com/efremov-aleksei-96/roamglyph/releases/download/../evil"));
    }
}
