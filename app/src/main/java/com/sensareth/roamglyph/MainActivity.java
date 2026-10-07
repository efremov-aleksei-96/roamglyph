package com.sensareth.roamglyph;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
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
    private static final String SOURCE_ID = "visited-source";
    private static final String LAYER_ID = "visited-layer";

    private MapView mapView;
    private MapLibreMap map;
    private Button trackingButton;
    private TextView status;
    private final Set<String> visited = new HashSet<>();
    private VisitedStore store;
    private H3Core h3;
    private boolean tracking;
    private boolean receiverRegistered;

    private final BroadcastReceiver trackingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            tracking = intent.getBooleanExtra(
                    TrackingService.EXTRA_TRACKING,
                    store.isTrackingActive()
            );
            updateButtonText();
            refreshVisitedFromStore();

            Float accuracy = intent.hasExtra(TrackingService.EXTRA_ACCURACY)
                    ? intent.getFloatExtra(TrackingService.EXTRA_ACCURACY, 0f)
                    : null;
            boolean accepted = intent.getBooleanExtra(TrackingService.EXTRA_ACCEPTED, true);
            updateStatus(accuracy, accepted);

            if (accepted
                    && map != null
                    && intent.hasExtra(TrackingService.EXTRA_LAT)
                    && intent.hasExtra(TrackingService.EXTRA_LNG)) {
                double lat = intent.getDoubleExtra(TrackingService.EXTRA_LAT, 0.0);
                double lng = intent.getDoubleExtra(TrackingService.EXTRA_LNG, 0.0);
                map.animateCamera(
                        org.maplibre.android.camera.CameraUpdateFactory.newLatLng(
                                new org.maplibre.android.geometry.LatLng(lat, lng)
                        )
                );
            }
        }
    };

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        boolean granted =
                                ContextCompat.checkSelfPermission(
                                        this,
                                        Manifest.permission.ACCESS_FINE_LOCATION
                                ) == PackageManager.PERMISSION_GRANTED
                                || ContextCompat.checkSelfPermission(
                                        this,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                ) == PackageManager.PERMISSION_GRANTED;

                        if (granted) {
                            startTracking();
                        } else {
                            tracking = false;
                            updateButtonText();
                            status.setText("Нужен доступ к геопозиции");
                        }
                    }
            );

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
        tracking = store.isTrackingActive();
        visited.addAll(store.load());

        FrameLayout root = new FrameLayout(this);

        mapView = new MapView(this);
        mapView.onCreate(savedInstanceState);
        mapView.getMapAsync(this);
        root.addView(
                mapView,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        );

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(0xCC202124);
        status.setPadding(24, 16, 24, 16);
        status.setTextSize(16f);
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        statusLp.gravity = Gravity.TOP | Gravity.START;
        statusLp.setMargins(24, 42, 24, 0);
        root.addView(status, statusLp);

        trackingButton = new Button(this);
        trackingButton.setOnClickListener(v -> toggleTracking());
        FrameLayout.LayoutParams buttonLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        buttonLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        buttonLp.setMargins(24, 24, 24, 48);
        root.addView(trackingButton, buttonLp);

        setContentView(root);
        updateButtonText();
        updateStatus(null, true);
    }

    @Override
    public void onMapReady(@NonNull MapLibreMap mapLibreMap) {
        map = mapLibreMap;
        String style = "{\"version\":8,\"sources\":{\"osm\":{\"type\":\"raster\",\"tiles\":[\"https://tile.openstreetmap.org/{z}/{x}/{y}.png\"],\"tileSize\":256,\"attribution\":\"© OpenStreetMap contributors\"}},\"layers\":[{\"id\":\"osm\",\"type\":\"raster\",\"source\":\"osm\"}]}";

        map.setStyle(new org.maplibre.android.maps.Style.Builder().fromJson(style), s -> {
            s.addSource(
                    new GeoJsonSource(
                            SOURCE_ID,
                            FeatureCollection.fromFeatures(new Feature[]{})
                    )
            );
            s.addLayer(
                    new FillLayer(LAYER_ID, SOURCE_ID).withProperties(
                            fillColor("#4CAF50"),
                            fillOpacity(0.48f),
                            fillOutlineColor("#2E7D32")
                    )
            );

            renderVisited();
            map.setCameraPosition(
                    new CameraPosition.Builder()
                            .target(new org.maplibre.android.geometry.LatLng(40.1872, 44.5152))
                            .zoom(13.0)
                            .build()
            );
        });
    }

    private void toggleTracking() {
        if (tracking) {
            stopTracking();
        } else {
            ensurePermissionsThenStart();
        }
    }

    private void ensurePermissionsThenStart() {
        boolean fine = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED;
        boolean notificationGranted = Build.VERSION.SDK_INT < 33
                || ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED;

        if ((fine || coarse) && notificationGranted) {
            startTracking();
            return;
        }

        List<String> permissions = new ArrayList<>();
        if (!fine) permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (!coarse) permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33 && !notificationGranted) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        permissionLauncher.launch(permissions.toArray(new String[0]));
    }

    private void startTracking() {
        if (tracking) return;

        tracking = true;
        store.setTrackingActive(true);
        updateButtonText();
        status.setText("GPS: запуск фонового исследования…");

        Intent service = new Intent(this, TrackingService.class)
                .setAction(TrackingService.ACTION_START);
        ContextCompat.startForegroundService(this, service);
    }

    private void stopTracking() {
        tracking = false;
        store.setTrackingActive(false);
        updateButtonText();
        updateStatus(null, true);

        Intent service = new Intent(this, TrackingService.class)
                .setAction(TrackingService.ACTION_STOP);
        startService(service);
    }

    private void updateButtonText() {
        if (tracking) {
            trackingButton.setText("Остановить исследование");
        } else if (visited.isEmpty()) {
            trackingButton.setText("Начать исследование");
        } else {
            trackingButton.setText("Продолжить исследование");
        }
    }

    private void refreshVisitedFromStore() {
        visited.clear();
        visited.addAll(store.load());
        renderVisited();
        updateButtonText();
    }

    private void updateStatus(Float accuracy, boolean accepted) {
        double approxAreaM2 = visited.size() * 43.87;
        String area = approxAreaM2 >= 1_000_000
                ? String.format(
                        Locale.getDefault(),
                        "%.2f км²",
                        approxAreaM2 / 1_000_000.0
                )
                : String.format(Locale.getDefault(), "%.0f м²", approxAreaM2);

        StringBuilder text = new StringBuilder()
                .append("Исследовано: ")
                .append(visited.size())
                .append(" клеток • ≈")
                .append(area)
                .append(tracking ? " • запись включена" : " • остановлено");

        if (accuracy != null) {
            text.append(" • GPS ±").append(Math.round(accuracy)).append(" м");
            if (!accepted) text.append(" • точка пропущена");
        }

        status.setText(text.toString());
    }

    private void renderVisited() {
        if (map == null || map.getStyle() == null) return;

        GeoJsonSource source = map.getStyle().getSourceAs(SOURCE_ID);
        if (source == null) return;

        List<Feature> features = new ArrayList<>();
        for (String cell : visited) {
            List<LatLng> boundary = h3.cellToBoundary(cell);
            List<Point> ring = new ArrayList<>();

            for (LatLng p : boundary) {
                ring.add(Point.fromLngLat(p.lng, p.lat));
            }
            if (!ring.isEmpty()) ring.add(ring.get(0));

            List<List<Point>> rings = new ArrayList<>();
            rings.add(ring);
            features.add(Feature.fromGeometry(Polygon.fromLngLats(rings)));
        }

        source.setGeoJson(FeatureCollection.fromFeatures(features));
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onStart() {
        super.onStart();
        mapView.onStart();

        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                    this,
                    trackingReceiver,
                    new IntentFilter(TrackingService.ACTION_STATE_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED
            );
            receiverRegistered = true;
        }

        tracking = store.isTrackingActive();
        refreshVisitedFromStore();
        updateStatus(null, true);

        if (tracking && hasLocationPermission()) {
            Intent service = new Intent(this, TrackingService.class)
                    .setAction(TrackingService.ACTION_START);
            ContextCompat.startForegroundService(this, service);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
        tracking = store.isTrackingActive();
        refreshVisitedFromStore();
        updateStatus(null, true);
    }

    @Override
    protected void onPause() {
        mapView.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(trackingReceiver);
            receiverRegistered = false;
        }
        mapView.onStop();
        super.onStop();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mapView.onLowMemory();
    }

    @Override
    protected void onDestroy() {
        mapView.onDestroy();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        mapView.onSaveInstanceState(outState);
    }
}
