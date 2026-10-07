package com.sensareth.roamglyph;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.uber.h3core.H3Core;
import com.uber.h3core.util.LatLng;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.OnMapReadyCallback;
import org.maplibre.android.style.layers.FillLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.Point;
import org.maplibre.geojson.Polygon;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.maplibre.android.style.layers.PropertyFactory.fillColor;
import static org.maplibre.android.style.layers.PropertyFactory.fillOpacity;
import static org.maplibre.android.style.layers.PropertyFactory.fillOutlineColor;

public class MainActivity extends AppCompatActivity implements OnMapReadyCallback {
    private static final int H3_RESOLUTION = 13;
    private static final long UPDATE_INTERVAL_MS = 2000L;
    private static final float MIN_DISTANCE_M = 4f;
    private static final float MAX_ACCEPTED_ACCURACY_M = 35f;
    private static final String SOURCE_ID = "visited-source";
    private static final String LAYER_ID = "visited-layer";

    private MapView mapView;
    private MapLibreMap map;
    private FusedLocationProviderClient fused;
    private LocationCallback locationCallback;
    private boolean tracking = false;
    private Button trackingButton;
    private TextView status;
    private final Set<String> visited = new HashSet<>();
    private VisitedStore store;
    private H3Core h3;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean granted = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION))
                        || Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_COARSE_LOCATION));
                if (granted) startTracking();
                else status.setText("Нужен доступ к геопозиции");
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MapLibre.getInstance(this);
        try {
            h3 = H3Core.newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        store = new VisitedStore(this);
        visited.addAll(store.load());
        fused = LocationServices.getFusedLocationProviderClient(this);

        FrameLayout root = new FrameLayout(this);
        mapView = new MapView(this);
        mapView.onCreate(savedInstanceState);
        mapView.getMapAsync(this);
        root.addView(mapView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(0xCC202124);
        status.setPadding(24, 16, 24, 16);
        status.setTextSize(16f);
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        statusLp.gravity = Gravity.TOP | Gravity.START;
        statusLp.setMargins(24, 42, 24, 0);
        root.addView(status, statusLp);

        trackingButton = new Button(this);
        trackingButton.setText("Начать исследование");
        trackingButton.setOnClickListener(v -> toggleTracking());
        FrameLayout.LayoutParams buttonLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        buttonLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        buttonLp.setMargins(24, 24, 24, 48);
        root.addView(trackingButton, buttonLp);

        setContentView(root);
        updateStatus(null);
    }

    @Override
    public void onMapReady(@NonNull MapLibreMap mapLibreMap) {
        map = mapLibreMap;
        String style = "{\"version\":8,\"sources\":{\"osm\":{\"type\":\"raster\",\"tiles\":[\"https://tile.openstreetmap.org/{z}/{x}/{y}.png\"],\"tileSize\":256,\"attribution\":\"© OpenStreetMap contributors\"}},\"layers\":[{\"id\":\"osm\",\"type\":\"raster\",\"source\":\"osm\"}]}";
        map.setStyle(new org.maplibre.android.maps.Style.Builder().fromJson(style), s -> {
            s.addSource(new GeoJsonSource(SOURCE_ID, FeatureCollection.fromFeatures(new Feature[]{})));
            s.addLayer(new FillLayer(LAYER_ID, SOURCE_ID).withProperties(
                    fillColor("#4CAF50"),
                    fillOpacity(0.48f),
                    fillOutlineColor("#2E7D32")
            ));
            renderVisited();
            map.setCameraPosition(new CameraPosition.Builder()
                    .target(new org.maplibre.android.geometry.LatLng(40.1872, 44.5152))
                    .zoom(13.0)
                    .build());
        });
    }

    private void toggleTracking() {
        if (tracking) stopTracking();
        else ensurePermissionThenStart();
    }

    private void ensurePermissionThenStart() {
        boolean fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (fine || coarse) startTracking();
        else permissionLauncher.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});
    }

    private void startTracking() {
        if (tracking) return;
        tracking = true;
        trackingButton.setText("Остановить");
        status.setText("GPS: запуск…");

        LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
                .setMinUpdateDistanceMeters(MIN_DISTANCE_M)
                .setMinUpdateIntervalMillis(1000L)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                Location loc = result.getLastLocation();
                if (loc != null) handleLocation(loc);
            }
        };

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fused.requestLocationUpdates(request, locationCallback, getMainLooper());
        }
    }

    private void stopTracking() {
        tracking = false;
        trackingButton.setText("Продолжить исследование");
        if (locationCallback != null) fused.removeLocationUpdates(locationCallback);
        store.save(visited);
        updateStatus(null);
    }

    private void handleLocation(Location loc) {
        if (loc.hasAccuracy() && loc.getAccuracy() > MAX_ACCEPTED_ACCURACY_M) {
            status.setText("GPS неточный: ±" + Math.round(loc.getAccuracy()) + " м");
            return;
        }

        String center = h3.latLngToCellAddress(loc.getLatitude(), loc.getLongitude(), H3_RESOLUTION);
        Set<String> newlyAdded = new HashSet<>();
        newlyAdded.add(center);
        newlyAdded.addAll(h3.gridDisk(center, 1));

        boolean changed = visited.addAll(newlyAdded);
        if (changed) {
            store.save(visited);
            renderVisited();
        }
        updateStatus(loc);

        if (map != null) {
            map.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLng(
                    new org.maplibre.android.geometry.LatLng(loc.getLatitude(), loc.getLongitude())));
        }
    }

    private void updateStatus(Location loc) {
        double approxAreaM2 = visited.size() * 43.87;
        String area = approxAreaM2 >= 1_000_000
                ? String.format(Locale.getDefault(), "%.2f км²", approxAreaM2 / 1_000_000.0)
                : String.format(Locale.getDefault(), "%.0f м²", approxAreaM2);
        String gps = loc == null ? "" : " • GPS ±" + Math.round(loc.getAccuracy()) + " м";
        status.setText("Исследовано: " + visited.size() + " клеток • ≈" + area + gps);
    }

    private void renderVisited() {
        if (map == null || map.getStyle() == null) return;
        GeoJsonSource source = map.getStyle().getSourceAs(SOURCE_ID);
        if (source == null) return;

        List<Feature> features = new ArrayList<>();
        for (String cell : visited) {
            List<LatLng> boundary = h3.cellToBoundary(cell);
            List<Point> ring = new ArrayList<>();
            for (LatLng p : boundary) ring.add(Point.fromLngLat(p.lng, p.lat));
            if (!ring.isEmpty()) ring.add(ring.get(0));
            List<List<Point>> rings = new ArrayList<>();
            rings.add(ring);
            features.add(Feature.fromGeometry(Polygon.fromLngLats(rings)));
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features));
    }

    @Override protected void onStart() { super.onStart(); mapView.onStart(); }
    @Override protected void onResume() { super.onResume(); mapView.onResume(); }
    @Override protected void onPause() { mapView.onPause(); super.onPause(); }
    @Override protected void onStop() { mapView.onStop(); super.onStop(); }
    @Override protected void onLowMemory() { super.onLowMemory(); mapView.onLowMemory(); }

    @Override
    protected void onDestroy() {
        if (tracking) stopTracking();
        mapView.onDestroy();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        mapView.onSaveInstanceState(outState);
    }
}
