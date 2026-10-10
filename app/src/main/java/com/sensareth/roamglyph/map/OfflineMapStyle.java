package com.sensareth.roamglyph.map;

import android.content.Context;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class OfflineMapStyle {
    private static final String ASSET_NAME = "offline-map-style.json";
    private static final String SOURCE_PLACEHOLDER = "__ROAMGLYPH_PMTILES_URL__";

    private OfflineMapStyle() {
    }

    @NonNull
    public static String build(
            @NonNull Context context,
            @NonNull File pmtilesFile
    ) throws IOException {
        OfflineMapStore.validateVectorPmtiles(pmtilesFile);

        StringBuilder style = new StringBuilder();
        try (
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                context.getAssets().open(ASSET_NAME),
                                StandardCharsets.UTF_8
                        )
                )
        ) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                style.append(buffer, 0, read);
            }
        }

        String sourceUri = "pmtiles://file://" + pmtilesFile.getAbsolutePath();
        return style.toString().replace(
                SOURCE_PLACEHOLDER,
                JSONObject.quote(sourceUri)
        );
    }
}
