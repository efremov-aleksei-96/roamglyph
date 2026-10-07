package com.sensareth.roamglyph;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class VisitedStore {
    private static final String PREFS = "roamglyph";
    private static final String KEY_CELLS = "visited_h3";
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
}
