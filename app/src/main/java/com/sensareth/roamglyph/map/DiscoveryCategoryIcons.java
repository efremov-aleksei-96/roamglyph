package com.sensareth.roamglyph.map;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/** User-visible POI symbols, derived only from local OpenMapTiles categories. */
public final class DiscoveryCategoryIcons {
    private DiscoveryCategoryIcons() {
    }

    @NonNull
    public static String iconFor(@Nullable String category, @Nullable String subclass) {
        String sub = normalize(subclass);
        switch (sub) {
            case "viewpoint": return "🌄";
            case "castle":
            case "fort":
            case "city_gate": return "🏰";
            case "museum": return "🏛️";
            case "gallery":
            case "artwork":
            case "sculpture":
            case "statue":
            case "mural": return "🎨";
            case "theatre": return "🎭";
            case "cinema": return "🎬";
            case "library": return "📚";
            case "waterfall": return "💧";
            case "peak": return "⛰️";
            case "cave_entrance": return "🪨";
            case "park":
            case "garden":
            case "botanical_garden": return "🌳";
            case "fountain": return "⛲";
            case "drinking_water":
            case "spring": return "🚰";
            case "place_of_worship": return "⛪";
            case "monument":
            case "memorial":
            case "ruins":
            case "archaeological_site": return "🏺";
            case "information":
            case "guidepost":
            case "map": return "ℹ️";
            default: break;
        }

        switch (normalize(category)) {
            case "viewpoint": return "🌄";
            case "landmark": return "🏛️";
            case "culture": return "🎭";
            case "art": return "🎨";
            case "nature": return "🌿";
            case "fountain": return "⛲";
            case "useful": return "🚰";
            case "architecture": return "🏛️";
            case "history": return "🏺";
            case "information": return "ℹ️";
            default: return "📍";
        }
    }

    private static String normalize(@Nullable String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
