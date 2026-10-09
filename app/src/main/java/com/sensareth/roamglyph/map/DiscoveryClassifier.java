package com.sensareth.roamglyph.map;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

public final class DiscoveryClassifier {
    private DiscoveryClassifier() {
    }

    @Nullable
    public static Classification classify(
            @Nullable String poiClass,
            @Nullable String subclass,
            @Nullable String name,
            int rank
    ) {
        String cls = normalize(poiClass);
        String sub = normalize(subclass);
        boolean named = name != null && !name.isBlank();

        Classification base = classifySubclass(sub);
        if (base == null) {
            base = classifyClass(cls);
        }
        if (base == null) return null;

        if (!named && base.requiresName) {
            return null;
        }

        int rankBonus = rank > 0 ? Math.max(0, 24 - Math.min(rank, 24)) : 0;
        int nameBonus = named ? 10 : 0;
        return new Classification(
                base.category,
                base.baseScore + rankBonus + nameBonus,
                base.requiresName
        );
    }

    @Nullable
    private static Classification classifySubclass(String sub) {
        switch (sub) {
            case "viewpoint":
                return candidate("viewpoint", 125, false);

            case "monument":
            case "memorial":
            case "archaeological_site":
            case "ruins":
            case "castle":
            case "fort":
            case "city_gate":
            case "tower":
                return candidate("landmark", 120, false);

            case "artwork":
            case "mural":
            case "sculpture":
            case "statue":
                return candidate("art", 115, false);

            case "museum":
            case "gallery":
            case "theatre":
            case "planetarium":
            case "observatory":
                return candidate("culture", 105, true);

            case "waterfall":
            case "cave_entrance":
            case "spring":
            case "peak":
            case "nature_reserve":
            case "botanical_garden":
                return candidate("nature", 105, false);

            case "fountain":
                return candidate("fountain", 90, false);

            case "drinking_water":
                return candidate("useful", 70, false);

            case "park":
            case "garden":
                return candidate("nature", 68, true);

            case "library":
                return candidate("culture", 65, true);

            case "place_of_worship":
                return candidate("architecture", 62, true);

            default:
                return null;
        }
    }

    @Nullable
    private static Classification classifyClass(String cls) {
        switch (cls) {
            case "attraction":
                return candidate("landmark", 110, true);
            case "castle":
                return candidate("landmark", 118, false);
            case "museum":
                return candidate("culture", 108, true);
            case "art_gallery":
                return candidate("art", 100, true);
            case "zoo":
                return candidate("nature", 95, true);
            case "park":
                return candidate("nature", 65, true);
            case "library":
                return candidate("culture", 62, true);
            case "stadium":
                return candidate("landmark", 55, true);
            default:
                return null;
        }
    }

    @NonNull
    public static String displayName(
            @Nullable String explicitName,
            @NonNull String category,
            @Nullable String subclass
    ) {
        if (explicitName != null && !explicitName.isBlank()) {
            return explicitName.trim();
        }

        String sub = normalize(subclass);
        switch (sub) {
            case "viewpoint": return "Viewpoint";
            case "monument": return "Monument";
            case "memorial": return "Memorial";
            case "archaeological_site": return "Archaeological site";
            case "ruins": return "Ruins";
            case "artwork": return "Public artwork";
            case "mural": return "Mural";
            case "sculpture": return "Sculpture";
            case "statue": return "Statue";
            case "waterfall": return "Waterfall";
            case "cave_entrance": return "Cave entrance";
            case "spring": return "Spring";
            case "peak": return "Peak";
            case "fountain": return "Fountain";
            case "drinking_water": return "Drinking water";
            default:
                return humanize(category);
        }
    }

    @NonNull
    public static String humanize(@Nullable String value) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) return "Discovery";
        String spaced = normalized.replace('_', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static Classification candidate(
            String category,
            int baseScore,
            boolean requiresName
    ) {
        return new Classification(category, baseScore, requiresName);
    }

    private static String normalize(@Nullable String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public static final class Classification {
        @NonNull public final String category;
        public final int baseScore;
        public final boolean requiresName;

        Classification(
                @NonNull String category,
                int baseScore,
                boolean requiresName
        ) {
            this.category = category;
            this.baseScore = baseScore;
            this.requiresName = requiresName;
        }
    }
}
