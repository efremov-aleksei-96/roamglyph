package com.sensareth.roamglyph;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class VisitedStore {
    private static final String PREFS = "roamglyph";
    private static final String KEY_CELLS = "visited_h3";
    private static final String KEY_LEGACY_CELLS_MIGRATED = "legacy_cells_migrated_to_room";
    private static final String KEY_TRACKING_ACTIVE = "tracking_active";
    private static final String KEY_ACTIVE_SESSION_ID = "active_session_id";
    private static final String KEY_VISITED_COUNT_CACHE = "visited_count_cache";
    private static final String KEY_FOG_ENABLED = "fog_enabled";
    private static final String KEY_HAS_LOCATION = "has_location";
    private static final String KEY_LAT = "last_lat";
    private static final String KEY_LNG = "last_lng";
    private static final String KEY_ACCURACY = "last_accuracy";

    private final SharedPreferences prefs;

    public VisitedStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public Set<String> loadLegacyCells() {
        return new HashSet<>(prefs.getStringSet(KEY_CELLS, Collections.emptySet()));
    }

    public boolean isLegacyCellMigrationComplete() {
        return prefs.getBoolean(KEY_LEGACY_CELLS_MIGRATED, false);
    }

    public void markLegacyCellMigrationComplete() {
        prefs.edit()
                .putBoolean(KEY_LEGACY_CELLS_MIGRATED, true)
                .remove(KEY_CELLS)
                .apply();
    }

    public boolean isTrackingActive() {
        return prefs.getBoolean(KEY_TRACKING_ACTIVE, false);
    }

    public void setTrackingActive(boolean active) {
        prefs.edit().putBoolean(KEY_TRACKING_ACTIVE, active).apply();
    }

    @Nullable
    public String getActiveSessionId() {
        String id = prefs.getString(KEY_ACTIVE_SESSION_ID, null);
        return id == null || id.isBlank() ? null : id;
    }

    public void setActiveSessionId(String sessionId) {
        prefs.edit().putString(KEY_ACTIVE_SESSION_ID, sessionId).apply();
    }

    public void clearActiveSessionId() {
        prefs.edit().remove(KEY_ACTIVE_SESSION_ID).apply();
    }

    public int getVisitedCountCache() {
        return prefs.getInt(KEY_VISITED_COUNT_CACHE, 0);
    }

    public void setVisitedCountCache(int count) {
        prefs.edit().putInt(KEY_VISITED_COUNT_CACHE, Math.max(0, count)).apply();
    }

    public boolean isFogEnabled() {
        return prefs.getBoolean(KEY_FOG_ENABLED, true);
    }

    public void setFogEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_FOG_ENABLED, enabled).apply();
    }

    public void saveLastLocation(Location location) {
        prefs.edit()
                .putBoolean(KEY_HAS_LOCATION, true)
                .putLong(KEY_LAT, Double.doubleToRawLongBits(location.getLatitude()))
                .putLong(KEY_LNG, Double.doubleToRawLongBits(location.getLongitude()))
                .putFloat(KEY_ACCURACY, location.hasAccuracy() ? location.getAccuracy() : 0f)
                .apply();
    }

    public boolean hasLastLocation() {
        return prefs.getBoolean(KEY_HAS_LOCATION, false);
    }

    public double getLastLatitude() {
        return Double.longBitsToDouble(
                prefs.getLong(KEY_LAT, Double.doubleToRawLongBits(0.0))
        );
    }

    public double getLastLongitude() {
        return Double.longBitsToDouble(
                prefs.getLong(KEY_LNG, Double.doubleToRawLongBits(0.0))
        );
    }

    public float getLastAccuracy() {
        return prefs.getFloat(KEY_ACCURACY, 0f);
    }
}
