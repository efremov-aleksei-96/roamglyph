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
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.uber.h3core.H3Core;
import com.uber.h3core.util.LatLng;

import org.json.JSONArray;
import org.json.JSONObject;
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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
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
    private static final int H3_RESOLUTION = 13;
    private static final String HISTORY_FORMAT = "roamglyph-history";
    private static final int HISTORY_VERSION = 1;

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
    private FusedLocationProviderClient fused;

    private boolean tracking;
    private boolean receiverRegistered;
    private boolean hasLocation;
    private boolean autoCentered;
    private boolean pendingStartAfterLocationPermission;

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
                setLocateAvailable(true);

                if (!autoCentered) {
                    centerOnCurrentLocation(true);
                    autoCentered = true;
                }
            }

            refreshVisitedFromStore();
            updateUi(accepted);
        }
    };

    private final ActivityResultLauncher<String[]> locationPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        boolean granted = hasLocationPermission();
                        if (granted) {
                            requestCurrentLocation(true);
                            if (pendingStartAfterLocationPermission) {
                                pendingStartAfterLocationPermission = false;
                                ensureNotificationThenStart();
                            }
                        } else {
                            pendingStartAfterLocationPermission = false;
                            gpsText.setText("Геопозиция не разрешена");
                            setLocateAvailable(false);
                        }
                    }
            );

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> startTracking()
            );

    private final ActivityResultLauncher<String> exportHistoryLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.CreateDocument("application/json"),
                    uri -> {
                        if (uri != null) writeHistory(uri);
                    }
            );

    private final ActivityResultLauncher<String[]> importHistoryLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri != null) readHistory(uri);
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
        fused = LocationServices.getFusedLocationProviderClient(this);
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
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        statusLp.gravity = Gravity.TOP;
        statusLp.setMargins(dp(16), dp(52), dp(72), 0);
        root.addView(statusCard, statusLp);

        Button menuButton = new Button(this);
        menuButton.setAllCaps(false);
        menuButton.setText("⋮");
        menuButton.setTextSize(28f);
        menuButton.setPadding(0, 0, 0, dp(5));
        menuButton.setContentDescription("Меню");
        menuButton.setOnClickListener(this::showDataMenu);

        FrameLayout.LayoutParams menuLp = new FrameLayout.LayoutParams(dp(48), dp(48));
        menuLp.gravity = Gravity.TOP | Gravity.END;
        menuLp.setMargins(0, dp(52), dp(14), 0);
        root.addView(menuButton, menuLp);

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
        locateButton.setOnClickListener(v -> {
            if (hasLocation) {
                centerOnCurrentLocation(true);
            } else if (hasLocationPermission()) {
                requestCurrentLocation(true);
            } else {
                requestLocationPermission(false);
            }
        });

        FrameLayout.LayoutParams locateLp = new FrameLayout.LayoutParams(dp(56), dp(56));
        locateLp.gravity = Gravity.BOTTOM | Gravity.END;
        locateLp.setMargins(0, 0, dp(16), dp(104));
        root.addView(locateButton, locateLp);

        setContentView(root);
        setLocateAvailable(hasLocation);
        updateUi(true);

        if (hasLocationPermission()) {
            requestCurrentLocation(!hasLocation);
        } else {
            gpsText.setText("Разреши геопозицию, чтобы показать себя на карте");
            requestLocationPermission(false);
        }
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

    private void requestLocationPermission(boolean forTracking) {
        pendingStartAfterLocationPermission = forTracking;
        locationPermissionLauncher.launch(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        });
    }

    private void requestCurrentLocation(boolean centerWhenReady) {
        if (!hasLocationPermission()) return;

        gpsText.setText("Определяю местоположение…");

        try {
            fused.getLastLocation().addOnSuccessListener(this, location -> {
                if (location != null && !hasLocation) {
                    acceptCurrentLocation(location, centerWhenReady);
                }
            });

            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener(this, location -> {
                        if (location != null) {
                            acceptCurrentLocation(location, centerWhenReady);
                        } else if (!hasLocation) {
                            gpsText.setText("Не удалось получить GPS. Нажми ◎ ещё раз");
                        }
                    })
                    .addOnFailureListener(this, error -> {
                        if (!hasLocation) {
                            gpsText.setText("Ошибка GPS. Нажми ◎ для повтора");
                        }
                    });
        } catch (SecurityException error) {
            gpsText.setText("Нет разрешения на геопозицию");
        }
    }

    private void acceptCurrentLocation(Location location, boolean centerWhenReady) {
        hasLocation = true;
        lastLat = location.getLatitude();
        lastLng = location.getLongitude();
        lastAccuracy = location.hasAccuracy() ? location.getAccuracy() : 0f;
        store.saveLastLocation(location);

        renderCurrentLocation();
        setLocateAvailable(true);
        updateUi(true);

        if (centerWhenReady && map != null) {
            centerOnCurrentLocation(true);
            autoCentered = true;
        }
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
            return;
        }

        if (!hasLocationPermission()) {
            requestLocationPermission(true);
            return;
        }

        ensureNotificationThenStart();
    }

    private void ensureNotificationThenStart() {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }

        startTracking();
    }

    private void startTracking() {
        if (tracking) return;

        tracking = true;
        store.setTrackingActive(true);
        updateUi(true);
        gpsText.setText("Запускаю фоновое исследование…");

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

    private void showDataMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, "Экспорт истории");
        popup.getMenu().add(0, 2, 1, "Импорт истории");
        popup.getMenu().add(0, 3, 2, "Обновить местоположение");

        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                launchHistoryExport();
                return true;
            }
            if (item.getItemId() == 2) {
                importHistoryLauncher.launch(new String[]{
                        "application/json",
                        "text/plain",
                        "application/octet-stream"
                });
                return true;
            }
            if (item.getItemId() == 3) {
                if (hasLocationPermission()) {
                    requestCurrentLocation(true);
                } else {
                    requestLocationPermission(false);
                }
                return true;
            }
            return false;
        });

        popup.show();
    }

    private void launchHistoryExport() {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
        exportHistoryLauncher.launch("roamglyph-history-" + stamp + ".json");
    }

    private void writeHistory(Uri uri) {
        try {
            List<String> cells = new ArrayList<>(store.load());
            Collections.sort(cells);

            JSONObject root = new JSONObject();
            root.put("format", HISTORY_FORMAT);
            root.put("version", HISTORY_VERSION);
            root.put("h3_resolution", H3_RESOLUTION);
            root.put("cell_count", cells.size());
            root.put("exported_at_ms", System.currentTimeMillis());

            JSONArray array = new JSONArray();
            for (String cell : cells) array.put(cell);
            root.put("cells", array);

            try (OutputStream output = getContentResolver().openOutputStream(uri);
                 OutputStreamWriter writer = new OutputStreamWriter(
                         output,
                         StandardCharsets.UTF_8
                 )) {
                writer.write(root.toString(2));
            }

            Toast.makeText(
                    this,
                    "Экспортировано: " + cells.size() + " клеток",
                    Toast.LENGTH_LONG
            ).show();
        } catch (Exception error) {
            Toast.makeText(
                    this,
                    "Не удалось экспортировать историю",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void readHistory(Uri uri) {
        if (h3 == null) {
            Toast.makeText(this, "H3 недоступен: импорт невозможен", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            StringBuilder text = new StringBuilder();

            try (InputStream input = getContentResolver().openInputStream(uri);
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(input, StandardCharsets.UTF_8)
                 )) {
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    text.append(buffer, 0, read);
                    if (text.length() > 50_000_000) {
                        throw new IllegalArgumentException("History file is too large");
                    }
                }
            }

            JSONObject root = new JSONObject(text.toString());

            if (!HISTORY_FORMAT.equals(root.optString("format"))) {
                throw new IllegalArgumentException("Unknown history format");
            }
            if (root.optInt("h3_resolution", -1) != H3_RESOLUTION) {
                throw new IllegalArgumentException("Unsupported H3 resolution");
            }

            JSONArray cells = root.getJSONArray("cells");
            Set<String> merged = store.load();
            int added = 0;
            int invalid = 0;

            for (int i = 0; i < cells.length(); i++) {
                String cell = cells.optString(i, "").trim();
                if (cell.isEmpty()) {
                    invalid++;
                    continue;
                }

                try {
                    h3.cellToBoundary(cell);
                    if (merged.add(cell)) added++;
                } catch (Throwable error) {
                    invalid++;
                }
            }

            store.save(merged);
            refreshVisitedFromStore();
            updateUi(true);

            String message = "Импортировано: " + added + " новых клеток • всего " + merged.size();
            if (invalid > 0) message += " • пропущено " + invalid;

            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(
                    this,
                    "Не удалось импортировать историю: файл повреждён или не Roamglyph",
                    Toast.LENGTH_LONG
            ).show();
        }
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
        } else if (hasLocationPermission()) {
            gpsText.setText("Определяю местоположение…");
        } else {
            gpsText.setText("Нужен доступ к геопозиции");
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
            try {
                List<LatLng> boundary = h3.cellToBoundary(cell);
                List<Point> ring = new ArrayList<>();

                for (LatLng p : boundary) {
                    ring.add(Point.fromLngLat(p.lng, p.lat));
                }

                if (!ring.isEmpty()) ring.add(ring.get(0));

                List<List<Point>> rings = new ArrayList<>();
                rings.add(ring);
                features.add(Feature.fromGeometry(Polygon.fromLngLats(rings)));
            } catch (Throwable ignored) {
                // Skip malformed imported cells instead of crashing the map.
            }
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

        double zoom = map.getCameraPosition() == null
                ? 17.5
                : map.getCameraPosition().zoom;

        if (zoomIn && zoom < 17.0) zoom = 17.5;

        CameraPosition position = new CameraPosition.Builder()
                .target(new org.maplibre.android.geometry.LatLng(lastLat, lastLng))
                .zoom(zoom)
                .build();

        map.animateCamera(
                org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(position)
        );
    }

    private void setLocateAvailable(boolean available) {
        if (locateButton == null) return;
        locateButton.setEnabled(true);
        locateButton.setAlpha(available ? 1f : 0.72f);
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
        setLocateAvailable(hasLocation);
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
        setLocateAvailable(hasLocation);
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
