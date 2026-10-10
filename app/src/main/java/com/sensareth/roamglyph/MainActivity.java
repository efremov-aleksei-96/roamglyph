package com.sensareth.roamglyph;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.text.format.Formatter;
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

import com.sensareth.roamglyph.data.BackupManager;
import com.sensareth.roamglyph.data.DiscoveryEntity;
import com.sensareth.roamglyph.data.ExplorationRepository;
import com.sensareth.roamglyph.map.DiscoveryClassifier;
import com.sensareth.roamglyph.map.DiscoveryEngine;
import com.sensareth.roamglyph.map.DiscoveryOverlayBuilder;
import com.sensareth.roamglyph.map.ExplorationCoverageIndex;
import com.sensareth.roamglyph.map.OfflineMapStore;
import com.sensareth.roamglyph.map.OfflineMapStyle;
import com.sensareth.roamglyph.map.PoiDiscoveryCandidate;
import com.sensareth.roamglyph.map.ViewportOverlayBuilder;
import com.uber.h3core.H3Core;
import com.uber.h3core.util.LatLng;

import org.json.JSONArray;
import org.json.JSONObject;
import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.geometry.VisibleRegion;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.OnMapReadyCallback;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.FillLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.android.style.sources.VectorSource;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
    private static final String MAP_STYLE_URI = "https://tiles.openfreemap.org/styles/liberty";
    private static final long CAMERA_ANIMATION_MS = 1200L;
    private static final String SOURCE_URL =
            "https://github.com/efremov-aleksei-96/roamglyph";
    private static final String PRIVACY_URL =
            "https://github.com/efremov-aleksei-96/roamglyph/blob/main/PRIVACY.md";

    private static final String FOG_SOURCE_ID = "roamglyph-fog-source";
    private static final String FOG_LAYER_ID = "roamglyph-fog-layer";
    private static final String VISITED_SOURCE_ID = "roamglyph-visited-source";
    private static final String VISITED_LAYER_ID = "roamglyph-visited-layer";
    private static final String DISCOVERY_HINT_SOURCE_ID = "roamglyph-discovery-hint-source";
    private static final String DISCOVERY_HINT_LAYER_ID = "roamglyph-discovery-hint-layer";
    private static final String DISCOVERED_SOURCE_ID = "roamglyph-discovered-source";
    private static final String DISCOVERED_LAYER_ID = "roamglyph-discovered-layer";
    private static final String BASE_MAP_SOURCE_ID = "openmaptiles";
    private static final String BASE_POI_SOURCE_LAYER = "poi";
    private static final double MIN_DISCOVERY_RENDER_ZOOM = 12.5;
    private static final double MIN_POI_QUERY_ZOOM = 14.0;
    private static final String LOCATION_SOURCE_ID = "roamglyph-current-location-source";
    private static final String LOCATION_HALO_LAYER_ID = "roamglyph-current-location-halo";
    private static final String LOCATION_LAYER_ID = "roamglyph-current-location-dot";

    private MapView mapView;
    private MapLibreMap map;
    private Button trackingButton;
    private Button locateButton;
    private TextView stateText;
    private TextView statsText;
    private TextView gpsText;

    private final Set<String> visited = new HashSet<>();
    private final AtomicBoolean refreshPending = new AtomicBoolean(false);
    private final AtomicBoolean discoveryScanScheduled = new AtomicBoolean(false);
    private final AtomicLong overlayGeneration = new AtomicLong(0L);
    private final AtomicLong discoveryScanGeneration = new AtomicLong(0L);
    private final ExplorationCoverageIndex coverageIndex = new ExplorationCoverageIndex();
    private VisitedStore store;
    private ExplorationRepository repository;
    private ExecutorService dataExecutor;
    private ExecutorService overlayExecutor;
    private ExecutorService mapIoExecutor;
    private H3Core h3;
    private LocationManager locationManager;

    private boolean tracking;
    private boolean trackingReceiverRegistered;
    private boolean providerReceiverRegistered;
    private boolean hasLocation;
    private boolean locationEnabled;
    private boolean autoCentered;
    private boolean initialMapCameraSet;
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
            locationEnabled = intent.getBooleanExtra(
                    TrackingService.EXTRA_LOCATION_ENABLED,
                    isSystemLocationEnabled()
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

            if (intent.getBooleanExtra(TrackingService.EXTRA_CELLS_CHANGED, false)) {
                ArrayList<String> newCells =
                        intent.getStringArrayListExtra(TrackingService.EXTRA_NEW_CELLS);

                if (newCells != null && !newCells.isEmpty()) {
                    boolean changed = visited.addAll(newCells);
                    if (changed) {
                        store.setVisitedCountCache(visited.size());
                        if (overlayExecutor != null) {
                            ArrayList<String> overlayCells = new ArrayList<>(newCells);
                            overlayExecutor.execute(
                                    () -> coverageIndex.addAll(h3, overlayCells)
                            );
                        }
                        scheduleViewportOverlay();
                        requestDiscoveryScan();
                    }
                } else {
                    refreshVisitedFromDatabase();
                }
            }
            updateUi(accepted);
        }
    };

    private final BroadcastReceiver providerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean wasEnabled = locationEnabled;
            locationEnabled = isSystemLocationEnabled();
            updateUi(true);

            if (!wasEnabled && locationEnabled && hasLocationPermission()) {
                requestCurrentLocation(false);
            }
        }
    };

    private final ActivityResultLauncher<String[]> locationPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        boolean granted = hasLocationPermission();
                        if (granted) {
                            locationEnabled = isSystemLocationEnabled();
                            requestCurrentLocation(true);
                            if (pendingStartAfterLocationPermission) {
                                pendingStartAfterLocationPermission = false;
                                ensureNotificationThenStart();
                            }
                        } else {
                            pendingStartAfterLocationPermission = false;
                            gpsText.setText(R.string.location_permission_required);
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

    private final ActivityResultLauncher<String[]> offlineMapLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri != null) importOfflineMap(uri);
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
        repository = new ExplorationRepository(this);
        dataExecutor = Executors.newSingleThreadExecutor();
        overlayExecutor = Executors.newSingleThreadExecutor();
        mapIoExecutor = Executors.newSingleThreadExecutor();
        locationManager = getSystemService(LocationManager.class);
        locationEnabled = isSystemLocationEnabled();
        tracking = store.isTrackingActive();
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
        menuButton.setContentDescription(getString(R.string.menu_description));
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
        trackingButton.setContentDescription(getString(R.string.tracking_button_description));
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
        locateButton.setContentDescription(getString(R.string.locate_button_description));
        locateButton.setOnClickListener(v -> {
            if (hasLocation && locationEnabled) {
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
            gpsText.setText(R.string.location_permission_prompt);
            requestLocationPermission(false);
        }

        refreshVisitedFromDatabase();
    }

    @Override
    public void onMapReady(@NonNull MapLibreMap mapLibreMap) {
        map = mapLibreMap;

        map.addOnCameraIdleListener(() -> {
            scheduleViewportOverlay();
            requestDiscoveryScan();
        });
        map.addOnMapClickListener(this::onMapClickForDiscovery);
        mapView.addOnSourceChangedListener(sourceId -> {
            if (BASE_MAP_SOURCE_ID.equals(sourceId)) {
                requestDiscoveryScan();
            }
        });
        mapView.addOnDidBecomeIdleListener(this::requestDiscoveryScan);

        loadActiveMapStyle();
    }

    private void loadActiveMapStyle() {
        if (map == null) return;

        Style.Builder builder;
        if (store.isOfflineMapEnabled()) {
            if (OfflineMapStore.hasValidMap(this)) {
                try {
                    builder = new Style.Builder().fromJson(
                            OfflineMapStyle.build(
                                    this,
                                    OfflineMapStore.mapFile(this)
                            )
                    );
                } catch (Exception error) {
                    store.setOfflineMapEnabled(false);
                    builder = new Style.Builder().fromUri(MAP_STYLE_URI);
                }
            } else {
                store.setOfflineMapEnabled(false);
                builder = new Style.Builder().fromUri(MAP_STYLE_URI);
            }
        } else {
            builder = new Style.Builder().fromUri(MAP_STYLE_URI);
        }

        map.setStyle(builder, this::configureLoadedMapStyle);
    }

    private void configureLoadedMapStyle(@NonNull Style style) {
        style.addSource(new GeoJsonSource(
                FOG_SOURCE_ID,
                FeatureCollection.fromFeatures(new Feature[]{})
        ));
        style.addLayer(new FillLayer(FOG_LAYER_ID, FOG_SOURCE_ID).withProperties(
                fillColor("#111418"),
                fillOpacity(0.58f),
                fillOutlineColor("#111418")
        ));

        style.addSource(new GeoJsonSource(
                VISITED_SOURCE_ID,
                FeatureCollection.fromFeatures(new Feature[]{})
        ));
        style.addLayer(new FillLayer(VISITED_LAYER_ID, VISITED_SOURCE_ID).withProperties(
                fillColor("#1B5E20"),
                fillOpacity(0.12f),
                fillOutlineColor("#176A20")
        ));

        style.addSource(new GeoJsonSource(
                DISCOVERY_HINT_SOURCE_ID,
                FeatureCollection.fromFeatures(new Feature[]{})
        ));
        style.addLayer(new CircleLayer(
                DISCOVERY_HINT_LAYER_ID,
                DISCOVERY_HINT_SOURCE_ID
        ).withProperties(
                circleColor("#F9AB00"),
                circleRadius(7f),
                circleOpacity(0.88f),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(2f)
        ));

        style.addSource(new GeoJsonSource(
                DISCOVERED_SOURCE_ID,
                FeatureCollection.fromFeatures(new Feature[]{})
        ));
        style.addLayer(new CircleLayer(
                DISCOVERED_LAYER_ID,
                DISCOVERED_SOURCE_ID
        ).withProperties(
                circleColor("#34A853"),
                circleRadius(8f),
                circleOpacity(0.96f),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(2.5f)
        ));

        style.addSource(new GeoJsonSource(
                LOCATION_SOURCE_ID,
                FeatureCollection.fromFeatures(new Feature[]{})
        ));
        style.addLayer(new CircleLayer(
                LOCATION_HALO_LAYER_ID,
                LOCATION_SOURCE_ID
        ).withProperties(
                circleColor("#4285F4"),
                circleRadius(16f),
                circleOpacity(0.22f)
        ));
        style.addLayer(new CircleLayer(
                LOCATION_LAYER_ID,
                LOCATION_SOURCE_ID
        ).withProperties(
                circleColor("#1A73E8"),
                circleRadius(7f),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(3f)
        ));

        renderCurrentLocation();

        if (!initialMapCameraSet) {
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
            initialMapCameraSet = true;
        }

        scheduleViewportOverlay();
        requestDiscoveryScan();
    }

    private void requestLocationPermission(boolean forTracking) {
        pendingStartAfterLocationPermission = forTracking;
        locationPermissionLauncher.launch(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        });
    }

    private void requestCurrentLocation(boolean centerWhenReady) {
        if (!hasLocationPermission()) {
            gpsText.setText(R.string.location_permission_required);
            return;
        }

        locationEnabled = isSystemLocationEnabled();
        if (!locationEnabled) {
            gpsText.setText(R.string.location_disabled);
            return;
        }

        String provider = preferredCurrentProvider();
        if (provider == null) {
            gpsText.setText(R.string.location_no_provider);
            return;
        }

        gpsText.setText(R.string.location_finding);

        try {
            Location cached = bestLastKnownLocation();
            if (cached != null && !hasLocation) {
                acceptCurrentLocation(cached, centerWhenReady);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                locationManager.getCurrentLocation(
                        provider,
                        null,
                        ContextCompat.getMainExecutor(this),
                        location -> {
                            if (location != null) {
                                acceptCurrentLocation(location, centerWhenReady);
                            } else if (!hasLocation) {
                                gpsText.setText(R.string.location_unavailable);
                            }
                        }
                );
            } else {
                @SuppressWarnings("deprecation")
                LocationListener oneShot = new LocationListener() {
                    @Override
                    public void onLocationChanged(@NonNull Location location) {
                        try {
                            locationManager.removeUpdates(this);
                        } catch (SecurityException ignored) {
                        }
                        acceptCurrentLocation(location, centerWhenReady);
                    }

                    @Override
                    @SuppressWarnings("deprecation")
                    public void onStatusChanged(String name, int status, Bundle extras) {
                    }

                    @Override
                    public void onProviderEnabled(@NonNull String name) {
                    }

                    @Override
                    public void onProviderDisabled(@NonNull String name) {
                    }
                };
                locationManager.requestSingleUpdate(
                        provider,
                        oneShot,
                        Looper.getMainLooper()
                );
            }
        } catch (SecurityException error) {
            gpsText.setText(R.string.location_permission_required);
        } catch (RuntimeException error) {
            if (!hasLocation) {
                gpsText.setText(R.string.location_error);
            }
        }
    }

    private Location bestLastKnownLocation() {
        Location best = null;
        String[] providers = new String[]{
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
        };

        for (String provider : providers) {
            try {
                if (LocationManager.GPS_PROVIDER.equals(provider)
                        && !hasFineLocationPermission()) {
                    continue;
                }
                Location candidate = locationManager.getLastKnownLocation(provider);
                if (candidate == null) continue;
                if (best == null || candidate.getTime() > best.getTime()) {
                    best = candidate;
                }
            } catch (SecurityException | IllegalArgumentException ignored) {
            }
        }
        return best;
    }

    private String preferredCurrentProvider() {
        try {
            if (hasFineLocationPermission()
                    && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                return LocationManager.GPS_PROVIDER;
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                return LocationManager.NETWORK_PROVIDER;
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private void acceptCurrentLocation(Location location, boolean centerWhenReady) {
        hasLocation = true;
        locationEnabled = true;
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
            gpsText.setText(R.string.h3_unavailable);
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

        locationEnabled = isSystemLocationEnabled();
        if (!locationEnabled) {
            gpsText.setText(R.string.location_disabled);
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
        gpsText.setText(R.string.location_starting);

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
        popup.getMenu().add(0, 1, 0, R.string.menu_export_history);
        popup.getMenu().add(0, 2, 1, R.string.menu_import_history);
        popup.getMenu().add(
                0,
                6,
                2,
                store.isFogEnabled() ? R.string.menu_fog_on : R.string.menu_fog_off
        );
        popup.getMenu().add(
                0,
                7,
                3,
                store.isDiscoveriesEnabled()
                        ? R.string.menu_discoveries_on
                        : R.string.menu_discoveries_off
        );
        popup.getMenu().add(0, 3, 4, R.string.menu_refresh_location);
        popup.getMenu().add(0, 4, 5, R.string.menu_source_code);
        popup.getMenu().add(0, 5, 6, R.string.menu_privacy);

        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                if (tracking) {
                    Toast.makeText(
                            this,
                            R.string.backup_requires_paused,
                            Toast.LENGTH_LONG
                    ).show();
                } else {
                    launchHistoryExport();
                }
                return true;
            }
            if (item.getItemId() == 2) {
                if (tracking) {
                    Toast.makeText(
                            this,
                            R.string.backup_requires_paused,
                            Toast.LENGTH_LONG
                    ).show();
                } else {
                    importHistoryLauncher.launch(new String[]{
                            "application/json",
                            "text/plain",
                            "application/octet-stream"
                    });
                }
                return true;
            }
            if (item.getItemId() == 6) {
                boolean enabled = !store.isFogEnabled();
                store.setFogEnabled(enabled);
                if (!enabled) {
                    clearFogLayer();
                }
                scheduleViewportOverlay();
                return true;
            }
            if (item.getItemId() == 7) {
                boolean enabled = !store.isDiscoveriesEnabled();
                store.setDiscoveriesEnabled(enabled);
                if (enabled) {
                    requestDiscoveryScan();
                } else {
                    clearDiscoveryLayers();
                }
                return true;
            }
            if (item.getItemId() == 3) {
                requestCurrentLocation(true);
                return true;
            }
            if (item.getItemId() == 4) {
                openUrl(SOURCE_URL);
                return true;
            }
            if (item.getItemId() == 5) {
                openUrl(PRIVACY_URL);
                return true;
            }
            return false;
        });

        popup.show();
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException error) {
            new AlertDialog.Builder(this)
                    .setMessage(url)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
    }

    private void launchHistoryExport() {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
        exportHistoryLauncher.launch("roamglyph-backup-" + stamp + ".json");
    }

    private void writeHistory(Uri uri) {
        dataExecutor.execute(() -> {
            try {
                repository.migrateLegacyCellsIfNeeded(store);

                int cells = repository.countVisitedCells();
                int sessions = repository.countSessions();
                long points = repository.countGpsPoints();
                int discoveries = repository.countDiscoveries();

                try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                    if (output == null) throw new IllegalStateException("No output stream");
                    BackupManager.exportBackup(output, repository);
                }

                runOnUiThread(() -> Toast.makeText(
                        this,
                        getString(
                                R.string.backup_export_success,
                                cells,
                                sessions,
                                points,
                                discoveries
                        ),
                        Toast.LENGTH_LONG
                ).show());
            } catch (Exception error) {
                runOnUiThread(() ->
                        Toast.makeText(this, R.string.export_failed, Toast.LENGTH_LONG).show()
                );
            }
        });
    }

    private void readHistory(Uri uri) {
        if (h3 == null) {
            Toast.makeText(this, R.string.import_h3_unavailable, Toast.LENGTH_LONG).show();
            return;
        }

        dataExecutor.execute(() -> {
            java.io.File temp = null;
            try {
                repository.migrateLegacyCellsIfNeeded(store);

                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new IllegalStateException("No input stream");
                    temp = BackupManager.copyToPrivateTemp(input, getCacheDir());
                }

                BackupManager.ImportResult result =
                        BackupManager.importBackup(temp, repository, h3);

                store.setVisitedCountCache(result.totalCells);
                store.setDiscoveryCountCache(repository.countDiscoveries());

                runOnUiThread(() -> {
                    refreshVisitedFromDatabase();
                    requestDiscoveryScan();
                    Toast.makeText(
                            this,
                            getString(
                                    R.string.backup_import_success,
                                    result.newCells,
                                    result.newSessions,
                                    result.newGpsPoints,
                                    result.newDiscoveries,
                                    result.totalCells
                            ),
                            Toast.LENGTH_LONG
                    ).show();
                });
            } catch (Exception error) {
                runOnUiThread(() ->
                        Toast.makeText(this, R.string.import_failed, Toast.LENGTH_LONG).show()
                );
            } finally {
                if (temp != null) {
                    //noinspection ResultOfMethodCallIgnored
                    temp.delete();
                }
            }
        });
    }

    private void updateUi(boolean accepted) {
        stateText.setText(tracking ? R.string.state_active : R.string.state_paused);
        stateText.setTextColor(tracking ? 0xFF81C995 : Color.WHITE);

        int count = visited.size();
        double approxAreaM2 = count * 43.87;
        String area = approxAreaM2 >= 1_000_000
                ? String.format(Locale.getDefault(), "%.2f km²", approxAreaM2 / 1_000_000.0)
                : String.format(Locale.getDefault(), "%,.0f m²", approxAreaM2);

        statsText.setText(getString(
                R.string.stats_summary,
                count,
                store.getDiscoveryCountCache(),
                area
        ));

        if (!hasLocationPermission()) {
            gpsText.setText(R.string.location_permission_required);
        } else if (!locationEnabled) {
            gpsText.setText(R.string.location_disabled);
        } else if (hasLocation) {
            String gps = "GPS ±" + Math.round(lastAccuracy) + " m";
            if (!accepted) gps += " · low accuracy";
            gpsText.setText(gps);
        } else if (tracking) {
            gpsText.setText(R.string.location_waiting);
        } else {
            gpsText.setText(R.string.location_finding);
        }

        if (tracking) {
            trackingButton.setText(R.string.action_stop);
        } else if (visited.isEmpty()) {
            trackingButton.setText(R.string.action_start);
        } else {
            trackingButton.setText(R.string.action_continue);
        }
    }

    private void refreshVisitedFromDatabase() {
        if (dataExecutor == null || !refreshPending.compareAndSet(false, true)) return;

        dataExecutor.execute(() -> {
            try {
                repository.migrateLegacyCellsIfNeeded(store);
                Set<String> stored = repository.loadVisitedCellIds();
                int discoveryCount = repository.countDiscoveries();
                store.setVisitedCountCache(stored.size());
                store.setDiscoveryCountCache(discoveryCount);

                runOnUiThread(() -> {
                    refreshPending.set(false);
                    if (isDestroyed()) return;
                    if (!stored.equals(visited)) {
                        visited.clear();
                        visited.addAll(stored);

                        Set<String> coverageSnapshot = new HashSet<>(stored);
                        if (overlayExecutor != null) {
                            overlayExecutor.execute(
                                    () -> coverageIndex.replaceAll(coverageSnapshot)
                            );
                        }
                        scheduleViewportOverlay();
                        requestDiscoveryScan();
                    }
                    updateUi(true);
                });
            } catch (RuntimeException error) {
                refreshPending.set(false);
            }
        });
    }

    private void scheduleViewportOverlay() {
        if (h3 == null
                || map == null
                || map.getStyle() == null
                || overlayExecutor == null) {
            return;
        }

        CameraPosition camera = map.getCameraPosition();
        if (camera == null) return;

        VisibleRegion visibleRegion = map.getProjection().getVisibleRegion();
        LatLngBounds bounds = visibleRegion.latLngBounds;
        double zoom = camera.zoom;

        long generation = overlayGeneration.incrementAndGet();
        boolean fogEnabled = store.isFogEnabled();

        double north = bounds.getLatNorth();
        double east = bounds.getLonEast();
        double south = bounds.getLatSouth();
        double west = bounds.getLonWest();

        overlayExecutor.execute(() -> {
            ViewportOverlayBuilder.Result result;
            try {
                result = ViewportOverlayBuilder.build(
                        h3,
                        coverageIndex,
                        north,
                        east,
                        south,
                        west,
                        zoom
                );
            } catch (Throwable error) {
                return;
            }

            runOnUiThread(() -> {
                if (generation != overlayGeneration.get()
                        || isDestroyed()
                        || map == null
                        || map.getStyle() == null) {
                    return;
                }

                GeoJsonSource fogSource = map.getStyle().getSourceAs(FOG_SOURCE_ID);
                GeoJsonSource visitedSource =
                        map.getStyle().getSourceAs(VISITED_SOURCE_ID);

                if (fogSource != null) {
                    fogSource.setGeoJson(
                            fogEnabled
                                    ? result.fog
                                    : FeatureCollection.fromFeatures(new Feature[]{})
                    );
                }
                if (visitedSource != null) {
                    visitedSource.setGeoJson(result.explored);
                }
            });
        });
    }

    private void requestDiscoveryScan() {
        if (map == null
                || map.getStyle() == null
                || dataExecutor == null
                || h3 == null) {
            return;
        }

        discoveryScanGeneration.incrementAndGet();

        if (!store.isDiscoveriesEnabled()) {
            clearDiscoveryLayers();
            return;
        }

        if (!discoveryScanScheduled.compareAndSet(false, true)) {
            return;
        }

        mapView.postDelayed(() -> {
            discoveryScanScheduled.set(false);
            scanDiscoveries();
        }, 300L);
    }

    private void scanDiscoveries() {
        if (!store.isDiscoveriesEnabled()
                || map == null
                || map.getStyle() == null
                || h3 == null) {
            clearDiscoveryLayers();
            return;
        }

        CameraPosition camera = map.getCameraPosition();
        if (camera == null || camera.zoom < MIN_DISCOVERY_RENDER_ZOOM) {
            clearDiscoveryLayers();
            return;
        }

        VisibleRegion region = map.getProjection().getVisibleRegion();
        LatLngBounds bounds = region.latLngBounds;
        double south = bounds.getLatSouth();
        double north = bounds.getLatNorth();
        double west = bounds.getLonWest();
        double east = bounds.getLonEast();
        double zoom = camera.zoom;

        List<PoiDiscoveryCandidate> candidates = new ArrayList<>();

        if (zoom >= MIN_POI_QUERY_ZOOM) {
            VectorSource source = map.getStyle().getSourceAs(BASE_MAP_SOURCE_ID);
            if (source != null) {
                try {
                    List<Feature> features = source.querySourceFeatures(
                            new String[]{BASE_POI_SOURCE_LAYER},
                            null
                    );
                    for (Feature feature : features) {
                        PoiDiscoveryCandidate candidate =
                                PoiDiscoveryCandidate.fromFeature(feature);
                        if (candidate != null
                                && candidate.latitude >= south
                                && candidate.latitude <= north
                                && candidate.longitude >= west
                                && candidate.longitude <= east) {
                            candidates.add(candidate);
                        }
                    }
                } catch (RuntimeException ignored) {
                    // The source may be between tile/style states; a later source/idle
                    // callback will rescan without affecting stored discoveries.
                }
            }
        }

        candidates.sort((a, b) -> Integer.compare(b.score, a.score));
        if (candidates.size() > DiscoveryEngine.MAX_SOURCE_CANDIDATES) {
            candidates = new ArrayList<>(
                    candidates.subList(0, DiscoveryEngine.MAX_SOURCE_CANDIDATES)
            );
        }

        List<PoiDiscoveryCandidate> scanCandidates = candidates;
        long generation = discoveryScanGeneration.get();
        boolean currentLocationAvailable = hasLocation;
        double currentLat = lastLat;
        double currentLng = lastLng;

        dataExecutor.execute(() -> {
            DiscoveryEngine.Result result;
            try {
                result = DiscoveryEngine.process(
                        repository,
                        h3,
                        scanCandidates,
                        south,
                        north,
                        west,
                        east,
                        zoom,
                        currentLocationAvailable,
                        currentLat,
                        currentLng
                );
            } catch (RuntimeException error) {
                return;
            }

            if (result.newlyDiscovered > 0) {
                store.setDiscoveryCountCache(repository.countDiscoveries());
            }

            runOnUiThread(() -> {
                if (generation != discoveryScanGeneration.get()
                        || isDestroyed()
                        || !store.isDiscoveriesEnabled()
                        || map == null
                        || map.getStyle() == null) {
                    return;
                }

                if (result.newlyDiscovered > 0) {
                    updateUi(true);
                }

                GeoJsonSource discoveredSource =
                        map.getStyle().getSourceAs(DISCOVERED_SOURCE_ID);
                GeoJsonSource hintSource =
                        map.getStyle().getSourceAs(DISCOVERY_HINT_SOURCE_ID);

                if (discoveredSource != null) {
                    discoveredSource.setGeoJson(
                            DiscoveryOverlayBuilder.discovered(result.discovered)
                    );
                }
                if (hintSource != null) {
                    hintSource.setGeoJson(
                            DiscoveryOverlayBuilder.hints(result.hints)
                    );
                }
            });
        });
    }

    private void clearDiscoveryLayers() {
        if (map == null || map.getStyle() == null) return;

        FeatureCollection empty = FeatureCollection.fromFeatures(new Feature[]{});
        GeoJsonSource discoveredSource =
                map.getStyle().getSourceAs(DISCOVERED_SOURCE_ID);
        GeoJsonSource hintSource =
                map.getStyle().getSourceAs(DISCOVERY_HINT_SOURCE_ID);

        if (discoveredSource != null) {
            discoveredSource.setGeoJson(empty);
        }
        if (hintSource != null) {
            hintSource.setGeoJson(empty);
        }
    }

    private boolean onMapClickForDiscovery(
            @NonNull org.maplibre.android.geometry.LatLng point
    ) {
        if (map == null || map.getStyle() == null) return false;

        PointF screenPoint = map.getProjection().toScreenLocation(point);
        List<Feature> features = map.queryRenderedFeatures(
                screenPoint,
                DISCOVERED_LAYER_ID,
                DISCOVERY_HINT_LAYER_ID
        );
        if (features.isEmpty()) return false;

        Feature feature = features.get(0);
        String state = feature.getStringProperty("state");

        if ("discovered".equals(state)) {
            String name = feature.getStringProperty("name");
            String category = feature.getStringProperty("category");
            new AlertDialog.Builder(this)
                    .setTitle(
                            name == null || name.isBlank()
                                    ? getString(R.string.discovery_opened_label)
                                    : name
                    )
                    .setMessage(
                            DiscoveryClassifier.humanize(category)
                                    + "\n"
                                    + getString(R.string.discovery_opened_label)
                    )
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return true;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.discovery_hint_title)
                .setMessage(R.string.discovery_hint_message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
        return true;
    }

    private void clearFogLayer() {
        if (map == null || map.getStyle() == null) return;
        GeoJsonSource source = map.getStyle().getSourceAs(FOG_SOURCE_ID);
        if (source != null) {
            source.setGeoJson(FeatureCollection.fromFeatures(new Feature[]{}));
        }
    }

    private void renderCurrentLocation() {
        if (!hasLocation || map == null || map.getStyle() == null) return;

        GeoJsonSource source = map.getStyle().getSourceAs(LOCATION_SOURCE_ID);
        if (source == null) return;

        source.setGeoJson(Feature.fromGeometry(Point.fromLngLat(lastLng, lastLat)));
    }

    private void centerOnCurrentLocation(boolean zoomIn) {
        if (!hasLocation) {
            gpsText.setText(R.string.location_not_determined);
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
                org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(position),
                (int) CAMERA_ANIMATION_MS
        );
    }

    private void setLocateAvailable(boolean available) {
        if (locateButton == null) return;
        locateButton.setEnabled(true);
        locateButton.setAlpha(available ? 1f : 0.72f);
    }

    private boolean hasLocationPermission() {
        return hasFineLocationPermission()
                || ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasFineLocationPermission() {
        return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isSystemLocationEnabled() {
        return locationManager != null && locationManager.isLocationEnabled();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mapView.onStart();

        if (!trackingReceiverRegistered) {
            ContextCompat.registerReceiver(
                    this,
                    trackingReceiver,
                    new IntentFilter(TrackingService.ACTION_STATE_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED
            );
            trackingReceiverRegistered = true;
        }

        if (!providerReceiverRegistered) {
            IntentFilter filter = new IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION);
            filter.addAction(LocationManager.MODE_CHANGED_ACTION);
            ContextCompat.registerReceiver(
                    this,
                    providerReceiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
            );
            providerReceiverRegistered = true;
        }

        tracking = store.isTrackingActive();
        locationEnabled = isSystemLocationEnabled();
        loadStoredLocation();
        refreshVisitedFromDatabase();
        renderCurrentLocation();
        setLocateAvailable(hasLocation);
        updateUi(true);
        requestDiscoveryScan();

        if (tracking && hasLocationPermission() && locationEnabled) {
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
        locationEnabled = isSystemLocationEnabled();
        loadStoredLocation();
        refreshVisitedFromDatabase();
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
        if (trackingReceiverRegistered) {
            unregisterReceiver(trackingReceiver);
            trackingReceiverRegistered = false;
        }
        if (providerReceiverRegistered) {
            unregisterReceiver(providerReceiver);
            providerReceiverRegistered = false;
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
        if (dataExecutor != null) {
            dataExecutor.shutdown();
        }
        if (overlayExecutor != null) {
            overlayExecutor.shutdownNow();
        }
        if (mapIoExecutor != null) {
            mapIoExecutor.shutdownNow();
        }
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        mapView.onSaveInstanceState(outState);
    }
}
