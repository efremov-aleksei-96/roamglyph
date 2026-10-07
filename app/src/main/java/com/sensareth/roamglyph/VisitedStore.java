package com.sensareth.roamglyph;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class VisitedStore {
    private static final String PREFS = "roamglyph";
    private static final String KEY_CELLS = "visited_h3";
    private static final String KEY_TRACKING_ACTIVE = "tracking_active";
    private static final String KEY_HAS_LOCATION = "has_location";
    private static final String KEY_LAT = "last_lat";
    private static final String KEY_LNG = "last_lng";
    private static final String KEY_ACCURACY = "last_accuracy";

    private final SharedPreferences prefs;

    public VisitedStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public Set<String> load() {
        return new HashSet<>(prefs.getStringSet(KEY_CELLS, Collections.emptySet()));
    }

    public void save(Set<String> cells) {
        prefs.edit().putStringSet(KEY_CELLS, new HashSet<>(cells)).apply();
    }

    public boolean isTrackingActive() {
        return prefs.getBoolean(KEY_TRACKING_ACTIVE, false);
    }

    public void setTrackingActive(boolean active) {
        prefs.edit().putBoolean(KEY_TRACKING_ACTIVE, active).apply();
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
