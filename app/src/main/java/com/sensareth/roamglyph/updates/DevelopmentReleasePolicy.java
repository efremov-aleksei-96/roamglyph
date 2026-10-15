package com.sensareth.roamglyph.updates;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure-Java validation of public, test-channel GitHub release metadata. */
public final class DevelopmentReleasePolicy {
    private static final Pattern TAG =
            Pattern.compile("^dev-vc([1-9][0-9]*)-g([0-9a-f]{8})$");

    private DevelopmentReleasePolicy() {}

    public static int versionCode(String tag) {
        if (tag == null) return -1;
        Matcher matcher = TAG.matcher(tag);
        if (!matcher.matches()) return -1;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    public static boolean isExpectedApk(String tag, String name) {
        Matcher matcher = TAG.matcher(tag == null ? "" : tag);
        if (!matcher.matches() || name == null) return false;
        return name.equals("roamglyph-0.5.0-dev." + matcher.group(1)
                + "-g" + matcher.group(2) + ".apk");
    }

    public static String digestHex(String digest) {
        if (digest == null || !digest.matches("^sha256:[a-fA-F0-9]{64}$")) {
            return null;
        }
        return digest.substring(7).toLowerCase(Locale.ROOT);
    }

    public static boolean isTrustedAssetUrl(String url) {
        return url != null && url.startsWith(
                "https://github.com/efremov-aleksei-96/roamglyph/releases/download/")
                && !url.contains("?") && !url.contains("#")
                && !url.contains("@") && !url.contains("..");
    }
}
