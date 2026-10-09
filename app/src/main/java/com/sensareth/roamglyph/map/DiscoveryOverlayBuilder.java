package com.sensareth.roamglyph.map;

import androidx.annotation.NonNull;

import com.sensareth.roamglyph.data.DiscoveryEntity;

import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.Point;

import java.util.ArrayList;
import java.util.List;

public final class DiscoveryOverlayBuilder {
    private DiscoveryOverlayBuilder() {
    }

    @NonNull
    public static FeatureCollection discovered(
            @NonNull List<DiscoveryEntity> discoveries
    ) {
        List<Feature> features = new ArrayList<>(discoveries.size());

        for (DiscoveryEntity discovery : discoveries) {
            Feature feature = Feature.fromGeometry(
                    Point.fromLngLat(discovery.longitude, discovery.latitude)
            );
            feature.addStringProperty("discovery_id", discovery.discoveryId);
            feature.addStringProperty("name", discovery.name);
            feature.addStringProperty("category", discovery.category);
            feature.addStringProperty("state", "discovered");
            if (discovery.subclass != null) {
                feature.addStringProperty("subclass", discovery.subclass);
            }
            if (discovery.discoveredAtMs != null) {
                feature.addNumberProperty(
                        "discovered_at_ms",
                        discovery.discoveredAtMs
                );
            }
            features.add(feature);
        }

        return FeatureCollection.fromFeatures(features.toArray(new Feature[0]));
    }

    @NonNull
    public static FeatureCollection hints(
            @NonNull List<PoiDiscoveryCandidate> hints
    ) {
        List<Feature> features = new ArrayList<>(hints.size());

        for (PoiDiscoveryCandidate hint : hints) {
            Feature feature = Feature.fromGeometry(
                    Point.fromLngLat(hint.longitude, hint.latitude)
            );
            feature.addStringProperty("discovery_id", hint.discoveryId);
            feature.addStringProperty("category", hint.category);
            feature.addStringProperty("state", "hint");
            features.add(feature);
        }

        return FeatureCollection.fromFeatures(features.toArray(new Feature[0]));
    }
}
