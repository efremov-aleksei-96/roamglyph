package com.sensareth.roamglyph.history;

import androidx.annotation.NonNull;

import java.util.Locale;

public final class HistoryFormatter {
    private HistoryFormatter() {
    }

    @NonNull
    public static String distance(double meters, @NonNull Locale locale) {
        double safeMeters = Math.max(0.0, meters);
        if (safeMeters >= 1000.0) {
            return String.format(locale, "%.1f km", safeMeters / 1000.0);
        }
        return String.format(locale, "%.0f m", safeMeters);
    }

    @NonNull
    public static String area(int cells, @NonNull Locale locale) {
        double areaM2 = Math.max(0, cells) * 43.87;
        if (areaM2 >= 1_000_000.0) {
            return String.format(locale, "%.2f km²", areaM2 / 1_000_000.0);
        }
        if (areaM2 >= 10_000.0) {
            return String.format(locale, "%.1f ha", areaM2 / 10_000.0);
        }
        return String.format(locale, "%,.0f m²", areaM2);
    }

    @NonNull
    public static String duration(long durationMs) {
        long totalMinutes = Math.max(0L, durationMs) / 60_000L;
        long days = totalMinutes / (24L * 60L);
        long hours = (totalMinutes / 60L) % 24L;
        long minutes = totalMinutes % 60L;

        if (days > 0L) {
            return String.format(Locale.ROOT, "%dd %dh %02dm", days, hours, minutes);
        }
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%dh %02dm", hours, minutes);
        }
        return String.format(Locale.ROOT, "%dm", minutes);
    }
}
