package com.sensareth.roamglyph.map;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.maplibre.geojson.Feature;
import org.maplibre.geojson.Geometry;
import org.maplibre.geojson.Point;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

public final class PoiDiscoveryCandidate {
    @NonNull public final String discoveryId;
    @NonNull public final String name;
    @NonNull public final String category;
    @Nullable public final String subclass;
    public final double latitude;
    public final double longitude;
    public final int rank;
    public final int score;

    public PoiDiscoveryCandidate(
            @NonNull String discoveryId,
            @NonNull String name,
            @NonNull String category,
            @Nullable String subclass,
            double latitude,
            double longitude,
            int rank,
            int score
    ) {
        this.discoveryId = discoveryId;
        this.name = name;
        this.category = category;
        this.subclass = subclass;
        this.latitude = latitude;
        this.longitude = longitude;
        this.rank = rank;
        this.score = score;
    }

    @Nullable
    public static PoiDiscoveryCandidate fromFeature(@NonNull Feature feature) {
        Geometry geometry = feature.geometry();
        if (!(geometry instanceof Point)) return null;

        Point point = (Point) geometry;
        if (!Double.isFinite(point.latitude()) || !Double.isFinite(point.longitude())) {
            return null;
        }

        JsonObject properties = feature.properties();
        String poiClass = stringProperty(properties, "class");
        String subclass = stringProperty(properties, "subclass");
        String explicitName = preferredName(properties);
        int rank = intProperty(properties, "rank", 999);

        DiscoveryClassifier.Classification classification =
                DiscoveryClassifier.classify(
                        poiClass,
                        subclass,
                        explicitName,
                        rank
                );
        if (classification == null) return null;

        String displayName = DiscoveryClassifier.displayName(
                explicitName,
                classification.category,
                subclass
        );

        String sourceId = stringProperty(properties, "osm_id");
        String discoveryId = stableId(
                sourceId,
                displayName,
                classification.category,
                subclass,
                point.latitude(),
                point.longitude()
        );

        return new PoiDiscoveryCandidate(
                discoveryId,
                displayName,
                classification.category,
                subclass,
                point.latitude(),
                point.longitude(),
                rank,
                classification.baseScore
        );
    }

    @NonNull
    private static String stableId(
            @Nullable String sourceId,
            @NonNull String name,
            @NonNull String category,
            @Nullable String subclass,
            double latitude,
            double longitude
    ) {
        String sub = subclass == null ? "" : subclass;

        if (sourceId != null && !sourceId.isBlank()) {
            return "omt:poi:" + sourceId.trim() + ":" + sub;
        }

        String signature = String.format(
                Locale.ROOT,
                "%s\u0000%s\u0000%s\u0000%.6f\u0000%.6f",
                name,
                category,
                sub,
                latitude,
                longitude
        );
        UUID id = UUID.nameUUIDFromBytes(signature.getBytes(StandardCharsets.UTF_8));
        return "omt:poi:fallback:" + id;
    }

    @Nullable
    private static String preferredName(@Nullable JsonObject properties) {
        String localized = stringProperty(properties, "name:latin");
        if (localized != null && !localized.isBlank()) return localized;

        String english = stringProperty(properties, "name_en");
        if (english != null && !english.isBlank()) return english;

        return stringProperty(properties, "name");
    }

    @Nullable
    private static String stringProperty(
            @Nullable JsonObject properties,
            @NonNull String name
    ) {
        if (properties == null) return null;
        JsonElement value = properties.get(name);
        if (value == null || value.isJsonNull()) return null;
        try {
            return value.getAsString();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static int intProperty(
            @Nullable JsonObject properties,
            @NonNull String name,
            int fallback
    ) {
        if (properties == null) return fallback;
        JsonElement value = properties.get(name);
        if (value == null || value.isJsonNull()) return fallback;
        try {
            return value.getAsInt();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
