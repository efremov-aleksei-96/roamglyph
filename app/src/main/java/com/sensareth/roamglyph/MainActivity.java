package com.sensareth.roamglyph;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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
import org.maplibre.android.style.layers.CircleLayer;
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

import static org.maplibre.android.style.layers.PropertyFactory.circleColor;
import static org.maplibre.android.style.layers.PropertyFactory.circleOpacity;
import static org.maplibre.android.style.layers.PropertyFactory.circleRadius;
import static org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor;
import static org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth;
import static org.maplibre.android.style.layers.PropertyFactory.fillColor;
import static org.maplibre.android.style.layers.PropertyFactory.fillOpacity;
import static org.maplibre.android.style.layers.PropertyFactory.fillOutlineColor;

public class MainActivity extends AppCompatActivity implements OnMapReadyCallback {
    private static final String VISITED_SOURCE_ID = "visited-source";
    private static final String VISITED_LAYER_ID = "visited-layer";
    private static final String LOCATION_SOURCE_ID = "current-location-source";
    private static final String LOCATION_HALO_LAYER_ID = "current-location-halo";
    private static final String LOCATION_LAYER_ID = "current-location-dot";

    private MapView mapView;
    private MapLibreMap map;
    private Button trackingButton;
    private Button locateButton;
    private TextView stateText;
    private TextView statsText;
    private TextView gpsText;

    private final Set<String> visited = new HashSet<>();
    private VisitedStore store;
    private H3Core h3;
    private boolean tracking;
    private boolean receiverRegistered;
    private boolean hasLocation;
    private boolean autoCentered;
    private double lastLat;
    private double lastLng;
    private float lastAccuracy;

    private final BroadcastReceiver trackingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            tracking = intent.getBooleanExtra(
                    TrackingService.EXTRA_TRACKING,
                    store.isTrackingActive()
            );

            boolean accepted = intent.getBooleanExtra(TrackingService.EXTRA_ACCEPTED, true);
            if (intent.hasExtra(TrackingService.EXTRA_LAT)
                    && intent.hasExtra(TrackingService.EXTRA_LNG)) {
                hasLocation = true;
                lastLat = intent.getDoubleExtra(TrackingService.EXTRA_LAT, 0.0);
                lastLng = intent.getDoubleExtra(TrackingService.EXTRA_LNG, 0.0);
                lastAccuracy = intent.getFloatExtra(TrackingService.EXTRA_ACCURACY, 0f);
                renderCurrentLocation();
                locateButton.setEnabled(true);
                locateButton.setAlpha(1f);

                if (!autoCentered) {
                    centerOnCurrentLocation(true);
                    autoCentered = true;
                }
            }

            refreshVisitedFromStore();
            updateUi(accepted);
        }
    };

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        boolean granted = hasLocationPermission();
                        if (granted) {
                            startTracking();
                        } else {
                            tracking = false;
                            store.setTrackingActive(false);
                            updateUi(false);
                            gpsText.setText("Разреши доступ к геопозиции");
                        }
                    }
            );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MapLibre.getInstance(this);

        try {
            h3 = H3Core.newSystemInstance();
        } catch (Throwable error) {
            h3 = null;
        }

        store = new VisitedStore(this);
        tracking = store.isTrackingActive();
        visited.addAll(store.load());
        loadStoredLocation();

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

        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable cardBackground = new GradientDrawable();
        cardBackground.setColor(0xE6202124);
        cardBackground.setCornerRadius(dp(14));
        statusCard.setBackground(cardBackground);
        statusCard.setElevation(dp(6));

        stateText = new TextView(this);
        stateText.setTextColor(Color.WHITE);
        stateText.setTextSize(16f);
        stateText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

        statsText = new TextView(this);
        statsText.setTextColor(0xFFE8EAED);
        statsText.setTextSize(14f);
        statsText.setPadding(0, dp(3), 0, 0);

        gpsText = new TextView(this);
        gpsText.setTextColor(0xFFBDC1C6);
        gpsText.setTextSize(13f);
        gpsText.setPadding(0, dp(2), 0, 0);

        statusCard.addView(stateText);
        statusCard.addView(statsText);
        statusCard.addView(gpsText);

        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        statusLp.gravity = Gravity.TOP | Gravity.START;
        statusLp.setMargins(dp(16), dp(52), dp(16), 0);
        root.addView(statusCard, statusLp);

        trackingButton = new Button(this);
        trackingButton.setId(R.id.tracking_button);
        trackingButton.setAllCaps(false);
        trackingButton.setTextSize(15f);
        trackingButton.setMinHeight(dp(48));
        trackingButton.setPadding(dp(18), 0, dp(18), 0);
        trackingButton.setContentDescription("Включить или остановить исследование");
        trackingButton.setOnClickListener(v -> toggleTracking());
        FrameLayout.LayoutParams buttonLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                dp(52)
        );
        buttonLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        buttonLp.setMargins(dp(16), 0, dp(16), dp(32));
        root.addView(trackingButton, buttonLp);

        locateButton = new Button(this);
        locateButton.setAllCaps(false);
        locateButton.setText("◎");
        locateButton.setTextSize(26f);
        locateButton.setPadding(0, 0, 0, dp(2));
        locateButton.setContentDescription("Показать моё местоположение");
        locateButton.setOnClickListener(v -> centerOnCurrentLocation(true));
        locateButton.setEnabled(hasLocation);
        locateButton.setAlpha(hasLocation ? 1f : 0.5f);
        FrameLayout.LayoutParams locateLp = new FrameLayout.LayoutParams(dp(56), dp(56));
        locateLp.gravity = Gravity.BOTTOM | Gravity.END;
        locateLp.setMargins(0, 0, dp(16), dp(104));
        root.addView(locateButton, locateLp);

        setContentView(root);
        updateUi(true);
    }

    @Override
    public void onMapReady(@NonNull MapLibreMap mapLibreMap) {
        map = mapLibreMap;
        String style = "{\"version\":8,\"sources\":{\"osm\":{\"type\":\"raster\",\"tiles\":[\"https://tile.openstreetmap.org/{z}/{x}/{y}.png\"],\"tileSize\":256,\"attribution\":\"© OpenStreetMap contributors\"}},\"layers\":[{\"id\":\"osm\",\"type\":\"raster\",\"source\":\"osm\"}]}";

        map.setStyle(new org.maplibre.android.maps.Style.Builder().fromJson(style), s -> {
            s.addSource(new GeoJsonSource(
                    VISITED_SOURCE_ID,
                    FeatureCollection.fromFeatures(new Feature[]{})
            ));
            s.addLayer(new FillLayer(VISITED_LAYER_ID, VISITED_SOURCE_ID).withProperties(
                    fillColor("#34A853"),
                    fillOpacity(0.58f),
                    fillOutlineColor("#137333")
            ));

            s.addSource(new GeoJsonSource(
                    LOCATION_SOURCE_ID,
                    FeatureCollection.fromFeatures(new Feature[]{})
            ));
            s.addLayer(new CircleLayer(LOCATION_HALO_LAYER_ID, LOCATION_SOURCE_ID).withProperties(
                    circleColor("#4285F4"),
                    circleRadius(16f),
                    circleOpacity(0.22f)
            ));
            s.addLayer(new CircleLayer(LOCATION_LAYER_ID, LOCATION_SOURCE_ID).withProperties(
                    circleColor("#1A73E8"),
                    circleRadius(7f),
                    circleStrokeColor("#FFFFFF"),
                    circleStrokeWidth(3f)
            ));

            renderVisited();
            renderCurrentLocation();

            if (hasLocation) {
                map.setCameraPosition(
                        new CameraPosition.Builder()
                                .target(new org.maplibre.android.geometry.LatLng(lastLat, lastLng))
                                .zoom(17.5)
                                .build()
                );
                autoCentered = true;
            } else {
                map.setCameraPosition(
                        new CameraPosition.Builder()
                                .target(new org.maplibre.android.geometry.LatLng(40.1872, 44.5152))
                                .zoom(13.0)
                                .build()
                );
            }
        });
    }

    private void loadStoredLocation() {
        if (!store.hasLastLocation()) return;
        hasLocation = true;
        lastLat = store.getLastLatitude();
        lastLng = store.getLastLongitude();
        lastAccuracy = store.getLastAccuracy();
    }

    private void toggleTracking() {
        if (h3 == null) {
            gpsText.setText("H3 не запустился: исследование клеток недоступно");
            return;
        }

        if (tracking) {
            stopTracking();
        } else {
            ensurePermissionsThenStart();
        }
    }

    private void ensurePermissionsThenStart() {
        boolean notificationGranted = Build.VERSION.SDK_INT < 33
                || ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED;

        if (hasLocationPermission() && notificationGranted) {
            startTracking();
            return;
        }

        List<String> permissions = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33 && !notificationGranted) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        permissionLauncher.launch(permissions.toArray(new String[0]));
    }

    private void startTracking() {
        if (tracking) return;

        tracking = true;
        store.setTrackingActive(true);
        updateUi(true);
        gpsText.setText("Запускаю GPS…");

        Intent service = new Intent(this, TrackingService.class)
                .setAction(TrackingService.ACTION_START);
        ContextCompat.startForegroundService(this, service);
    }

    private void stopTracking() {
        tracking = false;
        store.setTrackingActive(false);
        updateUi(true);

        Intent service = new Intent(this, TrackingService.class)
                .setAction(TrackingService.ACTION_STOP);
        startService(service);
    }

    private void updateUi(boolean accepted) {
        stateText.setText(tracking ? "Исследование включено" : "Исследование остановлено");
        stateText.setTextColor(tracking ? 0xFF81C995 : Color.WHITE);

        double approxAreaM2 = visited.size() * 43.87;
        String area = approxAreaM2 >= 1_000_000
                ? String.format(Locale.getDefault(), "%.2f км²", approxAreaM2 / 1_000_000.0)
                : String.format(Locale.getDefault(), "%,.0f м²", approxAreaM2);
        statsText.setText(String.format(
                Locale.getDefault(),
                "%d клеток • ≈%s",
                visited.size(),
                area
        ));

        if (hasLocation) {
            String gps = "GPS ±" + Math.round(lastAccuracy) + " м";
            if (!accepted) gps += " • точка слишком неточная";
            gpsText.setText(gps);
        } else {
            gpsText.setText(tracking ? "Ожидание GPS…" : "Позиция ещё не определена");
        }

        if (tracking) {
            trackingButton.setText("■  Остановить");
        } else if (visited.isEmpty()) {
            trackingButton.setText("▶  Начать исследование");
        } else {
            trackingButton.setText("▶  Продолжить");
        }
    }

    private void refreshVisitedFromStore() {
        visited.clear();
        visited.addAll(store.load());
        renderVisited();
    }

    private void renderVisited() {
        if (h3 == null || map == null || map.getStyle() == null) return;

        GeoJsonSource source = map.getStyle().getSourceAs(VISITED_SOURCE_ID);
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

    private void renderCurrentLocation() {
        if (!hasLocation || map == null || map.getStyle() == null) return;

        GeoJsonSource source = map.getStyle().getSourceAs(LOCATION_SOURCE_ID);
        if (source == null) return;

        source.setGeoJson(Feature.fromGeometry(Point.fromLngLat(lastLng, lastLat)));
    }

    private void centerOnCurrentLocation(boolean zoomIn) {
        if (!hasLocation) {
            gpsText.setText("Позиция ещё не определена");
            return;
        }
        if (map == null) return;

        double zoom = map.getCameraPosition() == null ? 17.5 : map.getCameraPosition().zoom;
        if (zoomIn && zoom < 17.0) zoom = 17.5;

        CameraPosition position = new CameraPosition.Builder()
                .target(new org.maplibre.android.geometry.LatLng(lastLat, lastLng))
                .zoom(zoom)
                .build();
        map.animateCamera(
                org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(position)
        );
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
        loadStoredLocation();
        refreshVisitedFromStore();
        renderCurrentLocation();
        locateButton.setEnabled(hasLocation);
        locateButton.setAlpha(hasLocation ? 1f : 0.5f);
        updateUi(true);

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
        loadStoredLocation();
        refreshVisitedFromStore();
        renderCurrentLocation();
        updateUi(true);
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
