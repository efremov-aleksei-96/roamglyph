package com.sensareth.roamglyph;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
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
import com.sensareth.roamglyph.map.DiscoveryHitPolicy;
import com.sensareth.roamglyph.map.DiscoveryOverlayBuilder;
import com.sensareth.roamglyph.map.ExplorationCoverageIndex;
import com.sensareth.roamglyph.map.FogOverlayView;
import com.sensareth.roamglyph.map.FogRendererChoice;
import com.sensareth.roamglyph.map.QuickMapAccessMode;
import com.sensareth.roamglyph.map.FogGpsCorridors;
import com.sensareth.roamglyph.map.FogMapFrameGate;
import com.sensareth.roamglyph.map.FogFrameMetrics;
import com.sensareth.roamglyph.map.FogPerformanceDiagnostics;
import com.sensareth.roamglyph.map.OfflineMapStore;
import com.sensareth.roamglyph.map.OfflineMapStyle;
import com.sensareth.roamglyph.map.PoiDiscoveryCandidate;
import com.sensareth.roamglyph.map.ViewportOverlayBuilder;
import com.sensareth.roamglyph.updates.AppUpdateManager;
import com.uber.h3core.H3Core;
import com.uber.h3core.util.LatLng;

import org.json.JSONArray;
import org.json.JSONObject;
import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.geometry.VisibleRegion;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapLibreMapOptions;
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
import java.util.concurrent.ArrayBlockingQueue;
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
    private static final String RELEASES_URL =
            "https://github.com/efremov-aleksei-96/roamglyph/releases";
    private static final String DEVELOPMENT_BUILDS_URL =
            "https://github.com/efremov-aleksei-96/roamglyph/actions/workflows/android.yml?query=branch%3Amain";

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
    private AutoBenchmarkRunner benchmarkRunner;
    private FogOverlayView fogOverlayView;
    private FogFrameMetrics frameMetrics;
    private FogPerformanceDiagnostics performanceDiagnostics;
    private AppUpdateManager appUpdates;
    private MapLibreMap map;
    private Button trackingButton;
    private Button locateButton;
    private LinearLayout quickMapTools;
    private Button quickShareButton;
    private Button quickResetButton;
    private TextView stateText;
    private TextView statsText;
    private TextView gpsText;
    private TextView mapZoomText;
    private int displayedZoomTenth = Integer.MIN_VALUE;
    private VisitedStore store;
    private volatile boolean mapLifecycleResumed;
    private long lastCameraMotionAtNanos;
    private long lastCameraMotionHandledAtNanos;
    private boolean frameFallbackPending;
    private boolean frameFallbackIsRetry;
    private final Runnable frameFallback = new Runnable() {
        @Override public void run() {
            frameFallbackPending = false;
            frameFallbackIsRetry = false;
            if (!isDestroyed() && mapLifecycleResumed && store != null
                    && AutoBenchmarkRunner.mapFrameSync(store) && fogOverlayView != null) {
                // MapLibre may skip a finish callback even AFTER camera idle.
                // Keep showing the latest conservative fog projection while
                // awaiting a matching render-start/finish pair.
                fogOverlayView.invalidate();
                if (performanceDiagnostics != null) {
                    performanceDiagnostics.recordMapFrameFallback();
                }
                if (lastCameraMotionAtNanos > lastCameraMotionHandledAtNanos
                        && mapView != null) {
                    frameFallbackPending = true;
                    frameFallbackIsRetry = true;
                    mapView.postDelayed(this, 250L);
                }
            }
        }
    };
    // Pair MapLibre render-start and render-finish notifications in FIFO order.
    // A finish notification's timestamp alone can describe an OLD camera frame
    // that finished AFTER a newer camera position was requested.
    // Bounded queue: lost/mismatched callbacks fail closed to the 80ms fallback.
    private final ArrayBlockingQueue<Long> frameStartTimes = new ArrayBlockingQueue<>(64);
    private final MapView.OnWillStartRenderingFrameListener mapFrameStartListener = () -> {
        long startedAtNs = SystemClock.elapsedRealtimeNanos();
        if (!frameStartTimes.offer(startedAtNs)) {
            frameStartTimes.poll();
            frameStartTimes.offer(startedAtNs);
        }
    };
    private final MapView.OnDidFinishRenderingFrameListener mapFrameListener =
            (fully, frameEncodingTime, frameRenderingTime) -> {
                long callbackAtNs = SystemClock.elapsedRealtimeNanos();
                Long startNs = frameStartTimes.poll();
                long frameStartedAtNs = startNs == null ? -1L : startNs;
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    onRenderedMapFrame(callbackAtNs, frameStartedAtNs);
                } else {
                    runOnUiThread(() -> onRenderedMapFrame(callbackAtNs, frameStartedAtNs));
                }
            };

    private final Set<String> visited = new HashSet<>();
    private final AtomicBoolean refreshPending = new AtomicBoolean(false);
    private final AtomicBoolean discoveryScanScheduled = new AtomicBoolean(false);
    private final AtomicLong discoveryScanGeneration = new AtomicLong(0L);
    private final ExplorationCoverageIndex coverageIndex = new ExplorationCoverageIndex();
    private final AtomicLong gpsCorridorGeneration = new AtomicLong();
    private long lastGpsCorridorLoadAt;
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

                if (!autoCentered && !AutoBenchmarkRunner.isRunning()) {
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
                            overlayExecutor.execute(() -> {
                                synchronized (coverageIndex) {
                                    coverageIndex.addAll(h3, overlayCells);
                                }
                                runOnUiThread(() -> {
                                    if (!isDestroyed() && fogOverlayView != null)
                                        fogOverlayView.onCoverageChanged(false);
                                });
                            });
                        }
                        scheduleViewportOverlay();
                        refreshGpsCorridors();
                        requestDiscoveryScan();
                    }
                } else {
                    refreshVisitedFromDatabase();
                }
            }
            updateUi(accepted);
            if (accepted && (AutoBenchmarkRunner.rendererChoice(store) == 2
                    || AutoBenchmarkRunner.rendererChoice(store) == 3)) refreshGpsCorridors();
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

        // Optional diagnostic A/B: same exact fog/GPS data with different
        // Android compositor paths. Default remains fast GLSurfaceView.
        mapView = new MapView(this, new MapLibreMapOptions()
                .textureMode(AutoBenchmarkRunner.textureView(store)));
        mapView.onCreate(savedInstanceState);
        mapView.getMapAsync(this);
        root.addView(
                mapView,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        );

        if ((getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            performanceDiagnostics = new FogPerformanceDiagnostics(AutoBenchmarkRunner.fogEnabled(store));
        }

        // Foreground screen-space fog cannot expose a strip during a rapid
        // gesture while MapLibre updates/clips its world GeoJSON sources.
        fogOverlayView = new FogOverlayView(this);
        fogOverlayView.setPerformanceDiagnostics(performanceDiagnostics);
        fogOverlayView.setRendererV2(AutoBenchmarkRunner.rendererChoice(store) == 1);
        fogOverlayView.setRendererV3(AutoBenchmarkRunner.rendererChoice(store) == 2);
        fogOverlayView.setRendererV4(AutoBenchmarkRunner.rendererChoice(store) == 3);
        fogOverlayView.setMapFrameSyncEnabled(AutoBenchmarkRunner.mapFrameSync(store));
        fogOverlayView.setFogEnabled(AutoBenchmarkRunner.fogEnabled(store));
        root.addView(
                fogOverlayView,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        );

        // Keep required map attribution bright and accessible above fog.
        TextView mapAttribution = new TextView(this);
        mapAttribution.setText("© OpenStreetMap contributors");
        mapAttribution.setTextColor(Color.WHITE);
        mapAttribution.setTextSize(11f);
        mapAttribution.setPadding(dp(5), dp(3), dp(5), dp(3));
        mapAttribution.setBackgroundColor(0xBB202124);
        mapAttribution.setOnClickListener(v -> startActivity(
                new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.openstreetmap.org/copyright"))
        ));
        FrameLayout.LayoutParams attributionLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        attributionLp.gravity = Gravity.BOTTOM | Gravity.START;
        attributionLp.setMargins(dp(10), 0, 0, dp(6));
        root.addView(mapAttribution, attributionLp);

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

        LinearLayout zoomControls = new LinearLayout(this);
        zoomControls.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable zoomBackground = new GradientDrawable();
        zoomBackground.setColor(0xE6202124);
        zoomBackground.setCornerRadius(dp(12));
        zoomControls.setBackground(zoomBackground);
        zoomControls.setElevation(dp(5));

        // Show the actual fractional MapLibre zoom level rather than
        // guessing a map ratio from the device's physical screen density.
        // The label shares the existing + / - group and stays visible.
        mapZoomText = new TextView(this);
        mapZoomText.setText("z—");
        mapZoomText.setTextColor(Color.WHITE);
        mapZoomText.setTextSize(14f);
        mapZoomText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        mapZoomText.setGravity(Gravity.CENTER);
        mapZoomText.setSingleLine(true);
        mapZoomText.setContentDescription("Current map zoom level");
        zoomControls.addView(mapZoomText,
                new LinearLayout.LayoutParams(dp(56), dp(30)));

        Button zoomInButton = new Button(this);
        zoomInButton.setAllCaps(false);
        zoomInButton.setText("+");
        zoomInButton.setTextSize(24f);
        zoomInButton.setTextColor(Color.WHITE);
        zoomInButton.setBackgroundColor(Color.TRANSPARENT);
        zoomInButton.setContentDescription(getString(R.string.zoom_in_description));
        zoomInButton.setOnClickListener(v -> zoomMap(true));
        zoomControls.addView(zoomInButton, new LinearLayout.LayoutParams(dp(56), dp(54)));

        View zoomDivider = new View(this);
        zoomDivider.setBackgroundColor(0x55FFFFFF);
        zoomControls.addView(zoomDivider, new LinearLayout.LayoutParams(dp(56), dp(1)));

        Button zoomOutButton = new Button(this);
        zoomOutButton.setAllCaps(false);
        zoomOutButton.setText("−");
        zoomOutButton.setTextSize(24f);
        zoomOutButton.setTextColor(Color.WHITE);
        zoomOutButton.setBackgroundColor(Color.TRANSPARENT);
        zoomOutButton.setContentDescription(getString(R.string.zoom_out_description));
        zoomOutButton.setOnClickListener(v -> zoomMap(false));
        zoomControls.addView(zoomOutButton, new LinearLayout.LayoutParams(dp(56), dp(54)));

        FrameLayout.LayoutParams zoomLp = new FrameLayout.LayoutParams(
                dp(56), FrameLayout.LayoutParams.WRAP_CONTENT
        );
        zoomLp.gravity = Gravity.BOTTOM | Gravity.END;
        zoomLp.setMargins(0, 0, dp(16), dp(173));
        root.addView(zoomControls, zoomLp);

        // Fast A/B tools sit ABOVE existing zoom controls, never replacing
        // the regular menu. Three actions in Full, one in Basic, none in Off.
        if (performanceDiagnostics != null) {
            quickMapTools = new LinearLayout(this);
            quickMapTools.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable quickBackground = new GradientDrawable();
            quickBackground.setColor(0xE6202124);
            quickBackground.setCornerRadius(dp(12));
            quickMapTools.setBackground(quickBackground);
            quickMapTools.setElevation(dp(5));

            Button renderButton = makeQuickMapButton("⚙",
                    R.string.quick_render_content);
            renderButton.setOnClickListener(this::showQuickRenderingMenu);
            quickMapTools.addView(renderButton,
                    new LinearLayout.LayoutParams(dp(56), dp(52)));

            View firstSeparator = new View(this);
            firstSeparator.setBackgroundColor(0x55FFFFFF);
            quickMapTools.addView(firstSeparator,
                    new LinearLayout.LayoutParams(dp(56), dp(1)));

            quickShareButton = makeQuickMapButton("↗",
                    R.string.quick_share_content);
            quickShareButton.setOnClickListener(v -> sharePerformanceDiagnostics(
                    buildPerformanceReport()));
            quickMapTools.addView(quickShareButton,
                    new LinearLayout.LayoutParams(dp(56), dp(52)));

            quickResetButton = makeQuickMapButton("↻",
                    R.string.quick_reset_content);
            quickResetButton.setOnClickListener(v -> resetPerformanceDiagnostics());
            quickMapTools.addView(quickResetButton,
                    new LinearLayout.LayoutParams(dp(56), dp(52)));

            FrameLayout.LayoutParams quickLp = new FrameLayout.LayoutParams(
                    dp(56), FrameLayout.LayoutParams.WRAP_CONTENT
            );
            quickLp.gravity = Gravity.BOTTOM | Gravity.END;
            // Zoom control top is 312dp above bottom; retain a 16dp gap.
            quickLp.setMargins(0, 0, dp(16), dp(328));
            root.addView(quickMapTools, quickLp);
            updateQuickMapToolsVisibility();
            // Rotation and split-screen can shorten the window without an
            // Activity restart. Reflow shortcuts horizontally on short views
            // so Share/Reset do not become clipped above zoom controls.
            root.addOnLayoutChangeListener((v, left, top, right, bottom,
                    oldLeft, oldTop, oldRight, oldBottom) -> {
                if (bottom - top != oldBottom - oldTop
                        || right - left != oldRight - oldLeft) {
                    positionQuickMapTools(bottom - top);
                }
            });
        }

        setContentView(root);
        benchmarkRunner = new AutoBenchmarkRunner(this, mapView, fogOverlayView,
                performanceDiagnostics, () -> {
                    lastGpsCorridorLoadAt = 0L;
                    refreshGpsCorridors();
                });
        appUpdates = new AppUpdateManager(this, () -> tracking);
        if ((getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            frameMetrics = new FogFrameMetrics(
                    getWindow(), AutoBenchmarkRunner.fogEnabled(store), performanceDiagnostics);
            frameMetrics.start();
        }
        setLocateAvailable(hasLocation);
        updateUi(true);

        if (hasLocationPermission()) {
            requestCurrentLocation(!hasLocation);
        } else {
            gpsText.setText(R.string.location_permission_prompt);
            requestLocationPermission(false);
        }

        refreshVisitedFromDatabase();
        refreshGpsCorridors();
    }

    @Override
    public void onMapReady(@NonNull MapLibreMap mapLibreMap) {
        map = mapLibreMap;
        // A process may die after MapView saved disabled benchmark gestures.
        // The benchmark is process-local: restore regular gestures when absent.
        if (!AutoBenchmarkRunner.isRunning())
            map.getUiSettings().setAllGesturesEnabled(true);
        benchmarkRunner.setMap(map);
        fogOverlayView.attachMap(map);
        if (h3 != null) fogOverlayView.attachCoverage(h3, coverageIndex);
        updateMapZoomLabel();

        mapView.addOnWillStartRenderingFrameListener(mapFrameStartListener);
        mapView.addOnDidFinishRenderingFrameListener(mapFrameListener);
        map.addOnCameraMoveListener(() -> {
            updateMapZoomLabel();
            lastCameraMotionAtNanos = SystemClock.elapsedRealtimeNanos();
            if (AutoBenchmarkRunner.mapFrameSync(store)) {
                scheduleMapFrameFallback();
            } else {
                fogOverlayView.invalidate();
            }
            // Request missing geography independently of frame invalidation.
            fogOverlayView.requestTiles();
        });
        map.addOnCameraIdleListener(() -> {
            updateMapZoomLabel();
            if (AutoBenchmarkRunner.mapFrameSync(store)) {
                // Camera idle is NOT proof that the latest GL frame rendered.
                // A late/missing finish callback must leave the rescue armed.
                if (lastCameraMotionAtNanos > lastCameraMotionHandledAtNanos) {
                    scheduleMapFrameFallback();
                }
            } else {
                cancelMapFrameFallback();
                fogOverlayView.invalidate();
            }
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

    private void scheduleMapFrameFallback() {
        if (mapView == null) return;
        if (frameFallbackPending) {
            if (!frameFallbackIsRetry) return;
            // An older camera's 250ms retry must NOT delay a new camera
            // generation. Replace it with a fresh rescue within 80ms.
            mapView.removeCallbacks(frameFallback);
            frameFallbackPending = false;
            frameFallbackIsRetry = false;
        }
        frameFallbackPending = true;
        mapView.postDelayed(frameFallback, 80L);
    }

    private void cancelMapFrameFallback() {
        if (mapView != null && frameFallbackPending) {
            mapView.removeCallbacks(frameFallback);
            frameFallbackPending = false;
            frameFallbackIsRetry = false;
        }
    }

    private void onRenderedMapFrame(long callbackAtNs, long frameStartedAtNs) {
        if (!mapLifecycleResumed || isFinishing() || isDestroyed()
                || fogOverlayView == null || store == null) return;
        // The current camera may move DURING an older frame's rendering.
        // Accept only a frame whose render-start comes after that movement.
        // Otherwise keep the fallback armed for the most recent camera.
        boolean corresponding = FogMapFrameGate.isCurrentCameraFrame(
                lastCameraMotionAtNanos, lastCameraMotionHandledAtNanos,
                frameStartedAtNs, callbackAtNs);
        long deltaNs = corresponding
                ? callbackAtNs - lastCameraMotionAtNanos : -1L;
        if (performanceDiagnostics != null) {
            performanceDiagnostics.recordRenderedMapFrame(deltaNs);
        }
        // An actual rendered frame is preferable to the 80ms fallback.
        if (corresponding) {
            lastCameraMotionHandledAtNanos = lastCameraMotionAtNanos;
            cancelMapFrameFallback();
        }
        boolean redraw = AutoBenchmarkRunner.mapFrameSync(store) && corresponding;
        fogOverlayView.onMapFrameCallback(callbackAtNs, redraw);
        if (redraw && performanceDiagnostics != null) {
            performanceDiagnostics.recordMapFrameRedraw();
        }
    }

    private void updateMapZoomLabel() {
        if (map == null || mapZoomText == null) return;
        CameraPosition position = map.getCameraPosition();
        if (position == null || !Double.isFinite(position.zoom)) return;
        int tenth = (int) Math.round(position.zoom * 10.0);
        if (tenth == displayedZoomTenth) return;
        displayedZoomTenth = tenth;
        String zoom = String.format(Locale.US, "%.1f", tenth / 10.0);
        mapZoomText.setText("z" + zoom);
        mapZoomText.setContentDescription("Current map zoom level " + zoom);
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
        // Fog is composited in FogOverlayView above MapLibre. The old
        // viewport-clipped GeoJSON fog layers are deliberately removed.
        // MapLibre continues to render POIs and the user's position.

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
            CameraPosition benchmarkCamera = AutoBenchmarkRunner.initialCamera();
            if (benchmarkCamera != null) {
                map.setCameraPosition(benchmarkCamera);
                autoCentered = true;
            } else if (hasLocation) {
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
        benchmarkRunner.onStyleReady();
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
        if (AutoBenchmarkRunner.isRunning()) return;
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

    /**
     * One-screen action menu. Infrequently used data, developer and legal
     * actions live in compact category dialogs; no existing action is removed.
     */
    private void showDataMenu(View anchor) {
        if (AutoBenchmarkRunner.isRunning()) {
            benchmarkRunner.showStopDialog();
            return;
        }
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 11, 0, R.string.menu_history);
        popup.getMenu().add(0, 6, 1,
                AutoBenchmarkRunner.fogEnabled(store) ? R.string.menu_fog_on : R.string.menu_fog_off);
        popup.getMenu().add(0, 7, 2,
                store.isDiscoveriesEnabled()
                        ? R.string.menu_discoveries_on : R.string.menu_discoveries_off);
        popup.getMenu().add(0, 3, 3, R.string.menu_refresh_location);
        popup.getMenu().add(0, 20, 4, R.string.menu_data_offline);
        if (performanceDiagnostics != null) {
            popup.getMenu().add(0, 21, 5, R.string.menu_rendering_diagnostics);
        }
        popup.getMenu().add(0, 22, 6, R.string.menu_about_updates);
        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 20) {
                showDataOfflineMenu(anchor);
                return true;
            }
            if (id == 21 && performanceDiagnostics != null) {
                showRenderingMenu(anchor);
                return true;
            }
            if (id == 22) {
                showAboutUpdatesMenu(anchor);
                return true;
            }
            return handleDataMenuAction(id);
        });
        popup.show();
    }

    /** Data operations preserve the old paused-tracking requirements. */
    private void showDataOfflineMenu(View anchor) {
        List<Integer> ids = new ArrayList<>();
        List<CharSequence> labels = new ArrayList<>();
        ids.add(1);
        labels.add(getString(R.string.menu_export_history));
        ids.add(2);
        labels.add(getString(R.string.menu_import_history));

        boolean localMapAvailable = OfflineMapStore.hasValidMap(this);
        if (localMapAvailable) {
            ids.add(8);
            labels.add(getString(store.isOfflineMapEnabled()
                    ? R.string.menu_use_online_map : R.string.menu_use_local_map));
        }
        ids.add(9);
        labels.add(getString(R.string.menu_import_local_map));
        if (localMapAvailable) {
            ids.add(10);
            labels.add(getString(R.string.menu_remove_local_map));
        }
        showMenuSection(R.string.menu_data_offline, ids, labels, anchor);
    }

    /** Small category list; every value is also visible without opening it. */
    private Button makeQuickMapButton(String icon, int labelRes) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(icon);
        button.setTextSize(23f);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setPadding(0, 0, 0, 0);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setContentDescription(getString(labelRes));
        return button;
    }

    /** Only the floating tools change; persisted exploration is untouched. */
    private void positionQuickMapTools(int availableHeightPx) {
        if (quickMapTools == null) return;
        // Vertical stack needs at least 640dp to clear the top menu button.
        boolean shortWindow = availableHeightPx < dp(640);
        int orientation = shortWindow
                ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL;
        boolean orientationChanged = quickMapTools.getOrientation() != orientation;
        if (orientationChanged) {
            quickMapTools.setOrientation(orientation);
            View divider = quickMapTools.getChildAt(1);
            divider.setLayoutParams(new LinearLayout.LayoutParams(
                    shortWindow ? dp(1) : dp(56),
                    shortWindow ? dp(52) : dp(1)
            ));
        }
        FrameLayout.LayoutParams lp =
                (FrameLayout.LayoutParams) quickMapTools.getLayoutParams();
        // Full horizontal tools take 169dp; vertical column takes 56dp.
        lp.width = shortWindow
                ? FrameLayout.LayoutParams.WRAP_CONTENT : dp(56);
        if (availableHeightPx < dp(320)) {
            // In very short multi-window, the space below the status card
            // would collide with Stop exploration. Place the row ABOVE that
            // card instead: the existing status starts 52dp below root top.
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.setMargins(dp(8), 0, 0, 0);
        } else if (shortWindow) {
            // Beneath status card and left of the zoom/location controls.
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.setMargins(0, dp(166), dp(80), 0);
        } else {
            // Portrait: 16dp above zoom controls.
            lp.gravity = Gravity.BOTTOM | Gravity.END;
            lp.setMargins(0, 0, dp(16), dp(328));
        }
        quickMapTools.setLayoutParams(lp);
    }

    private void updateQuickMapToolsVisibility() {
        if (quickMapTools == null || store == null) return;
        int mode = store.getQuickMapAccessMode();
        quickMapTools.setVisibility(
                QuickMapAccessMode.showRender(mode) ? View.VISIBLE : View.GONE);
        int detailVisibility = QuickMapAccessMode.showDiagnostics(mode)
                ? View.VISIBLE : View.GONE;
        if (quickShareButton != null) quickShareButton.setVisibility(detailVisibility);
        if (quickResetButton != null) quickResetButton.setVisibility(detailVisibility);
        // The divider after Render should hide with optional actions.
        if (quickMapTools.getChildCount() > 1) {
            quickMapTools.getChildAt(1).setVisibility(detailVisibility);
        }
    }

    private void showQuickMapAccessChoices(View anchor) {
        int current = store.getQuickMapAccessMode();
        showRenderingChoices(R.string.menu_quick_map_access,
                QuickMapAccessMode.LABELS, current, anchor, selected -> {
                    if (selected == current) return;
                    store.setQuickMapAccessMode(selected);
                    updateQuickMapToolsVisibility();
                });
    }

    /** Fast map shortcut: exactly three rendering groups; standard menu stays intact. */
    private void showQuickRenderingMenu(View anchor) {
        if (AutoBenchmarkRunner.isRunning()) {
            benchmarkRunner.showStopDialog();
            return;
        }
        String[] labels = {
                getString(R.string.menu_fog_renderer)
                        + "\n" + FogRendererChoice.label(currentRendererChoice()),
                getString(R.string.menu_map_compositor)
                        + "\n" + (AutoBenchmarkRunner.textureView(store)
                                ? "TextureView" : "GLSurfaceView"),
                getString(R.string.menu_fog_synchronization)
                        + "\n" + (AutoBenchmarkRunner.mapFrameSync(store)
                                ? "Map frame" : "Standard")
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.quick_render_content)
                .setItems(labels, (dialog, which) -> {
                    if (which == 0) showRendererChoices(anchor, true);
                    else if (which == 1) showCompositorChoices(anchor, true);
                    else if (which == 2) showSynchronizationChoices(anchor, true);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showRenderingMenu(View anchor) {
        List<Integer> ids = new ArrayList<>();
        List<CharSequence> labels = new ArrayList<>();
        ids.add(23);
        labels.add(getString(R.string.menu_fog_renderer)
                + "\n" + FogRendererChoice.label(currentRendererChoice()));
        ids.add(24);
        labels.add(getString(R.string.menu_map_compositor)
                + "\n" + (AutoBenchmarkRunner.textureView(store)
                        ? "TextureView" : "GLSurfaceView"));
        ids.add(25);
        labels.add(getString(R.string.menu_fog_synchronization)
                + "\n" + (AutoBenchmarkRunner.mapFrameSync(store)
                        ? "Map frame" : "Standard"));
        ids.add(26);
        labels.add(getString(R.string.menu_quick_map_access)
                + "\n" + QuickMapAccessMode.label(store.getQuickMapAccessMode()));
        ids.add(14);
        labels.add(getString(R.string.menu_performance_diagnostics));
        ids.add(27);
        labels.add("Automatic camera benchmark");
        showMenuSection(R.string.menu_rendering_diagnostics, ids, labels, anchor);
    }

    private int currentRendererChoice() {
        return AutoBenchmarkRunner.rendererChoice(store);
    }

    /** Radio choices replace ambiguous independent toggles. A selected item is a no-op. */
    private void showRendererChoices(View anchor) {
        showRendererChoices(anchor, false);
    }

    private void showRendererChoices(View anchor, boolean fromQuick) {
        showRenderingChoices(R.string.menu_fog_renderer,
                FogRendererChoice.LABELS,
                currentRendererChoice(), anchor, selected -> {
                    int old = currentRendererChoice();
                    if (selected == old) return;
                    boolean v2 = selected == 1;
                    boolean v3 = selected == 2;
                    boolean v4 = selected == 3;
                    // Persist one of V1/V2/V3/V4 without touching any GPS/H3 data.
                    store.setRendererV2Enabled(v2);
                    store.setRendererV3Enabled(v3);
                    store.setRendererV4Enabled(v4);
                    // Disable old renderer before enabling the new renderer.
                    fogOverlayView.setRendererV4(false);
                    fogOverlayView.setRendererV3(false);
                    fogOverlayView.setRendererV2(false);
                    if (v2) fogOverlayView.setRendererV2(true);
                    if (v3) fogOverlayView.setRendererV3(true);
                    if (v4) fogOverlayView.setRendererV4(true);
                    if (v3 || v4) {
                        lastGpsCorridorLoadAt = 0;
                        refreshGpsCorridors();
                    }
                    if (performanceDiagnostics != null) performanceDiagnostics.reset();
                }, fromQuick);
    }

    private void showCompositorChoices(View anchor) {
        showCompositorChoices(anchor, false);
    }

    private void showCompositorChoices(View anchor, boolean fromQuick) {
        showRenderingChoices(R.string.menu_map_compositor,
                new String[]{"GLSurfaceView — Default", "TextureView — Experimental"},
                AutoBenchmarkRunner.textureView(store) ? 1 : 0,
                anchor, selected -> {
                    // Keep the existing stop-tracking guard, confirmation,
                    // signing-neutral Activity recreate and data preservation.
                    if ((selected == 1) != AutoBenchmarkRunner.textureView(store))
                        showMapCompositorExperiment();
                }, fromQuick);
    }

    private void showSynchronizationChoices(View anchor) {
        showSynchronizationChoices(anchor, false);
    }

    private void showSynchronizationChoices(View anchor, boolean fromQuick) {
        showRenderingChoices(R.string.menu_fog_synchronization,
                new String[]{"Standard — Camera events", "Map frame — Experimental"},
                AutoBenchmarkRunner.mapFrameSync(store) ? 1 : 0,
                anchor, selected -> {
                    if ((selected == 1) != AutoBenchmarkRunner.mapFrameSync(store))
                        showFrameSyncExperiment();
                }, fromQuick);
    }

    private void showRenderingChoices(int title, String[] labels,
                                      int selected, View anchor,
                                      java.util.function.IntConsumer onSelect) {
        showRenderingChoices(title, labels, selected, anchor, onSelect, false);
    }

    private void showRenderingChoices(int title, String[] labels,
                                      int selected, View anchor,
                                      java.util.function.IntConsumer onSelect,
                                      boolean fromQuick) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    dialog.dismiss();
                    if (which != selected) onSelect.accept(which);
                })
                .setNegativeButton(R.string.menu_back,
                        (dialog, which) -> {
                            if (fromQuick) showQuickRenderingMenu(anchor);
                            else showRenderingMenu(anchor);
                        })
                .show();
    }

    private void showAboutUpdatesMenu(View anchor) {
        List<Integer> ids = new ArrayList<>();
        List<CharSequence> labels = new ArrayList<>();
        ids.add(12);
        labels.add(getString(R.string.menu_version_changelog, installedVersion()));
        ids.add(13);
        labels.add(getString(R.string.menu_check_updates));
        ids.add(4);
        labels.add(getString(R.string.menu_source_code));
        ids.add(5);
        labels.add(getString(R.string.menu_privacy));
        showMenuSection(R.string.menu_about_updates, ids, labels, anchor);
    }

    /** Native scrollable item dialog with Back returning to the main menu. */
    private void showMenuSection(int title, List<Integer> ids,
                                 List<CharSequence> labels, View anchor) {
        CharSequence[] items = labels.toArray(new CharSequence[0]);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(items, (dialog, which) -> {
                    if (which >= 0 && which < ids.size()) {
                        int id = ids.get(which);
                        if (id == 23) showRendererChoices(anchor);
                        else if (id == 24) showCompositorChoices(anchor);
                        else if (id == 25) showSynchronizationChoices(anchor);
                        else if (id == 26) showQuickMapAccessChoices(anchor);
                        else if (id == 27) benchmarkRunner.choosePlan(
                                tracking || store.isTrackingActive());
                        else handleDataMenuAction(id);
                    }
                })
                .setNegativeButton(R.string.menu_back, (dialog, which) ->
                        showDataMenu(anchor))
                .show();
    }

    /** Shared handler retains the original action and safety semantics. */
    private boolean handleDataMenuAction(int id) {
            if (id == 11) {
                startActivity(new Intent(this, HistoryActivity.class));
                return true;
            }
            if (id == 1) {
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
            if (id == 2) {
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
            if (id == 6) {
                boolean enabled = !AutoBenchmarkRunner.fogEnabled(store);
                store.setFogEnabled(enabled);
                if (frameMetrics != null) frameMetrics.setFogEnabled(enabled);
                if (performanceDiagnostics != null) {
                    performanceDiagnostics.setFogEnabled(enabled);
                }
                if (enabled) {
                    showInitialFogLayer();
                } else {
                    clearFogLayer();
                }
                scheduleViewportOverlay();
                return true;
            }
            if (id == 7) {
                boolean enabled = !store.isDiscoveriesEnabled();
                store.setDiscoveriesEnabled(enabled);
                if (enabled) {
                    requestDiscoveryScan();
                } else {
                    clearDiscoveryLayers();
                }
                return true;
            }
            if (id == 8) {
                boolean useOffline = !store.isOfflineMapEnabled();
                if (useOffline && !OfflineMapStore.hasValidMap(this)) {
                    offlineMapLauncher.launch(new String[]{
                            "application/vnd.pmtiles",
                            "application/octet-stream",
                            "*/*"
                    });
                    return true;
                }

                store.setOfflineMapEnabled(useOffline);
                loadActiveMapStyle();
                Toast.makeText(
                        this,
                        useOffline
                                ? R.string.offline_map_enabled
                                : R.string.offline_map_online,
                        Toast.LENGTH_SHORT
                ).show();
                return true;
            }
            if (id == 9) {
                offlineMapLauncher.launch(new String[]{
                        "application/vnd.pmtiles",
                        "application/octet-stream",
                        "*/*"
                });
                return true;
            }
            if (id == 10) {
                removeOfflineMap();
                return true;
            }
            if (id == 3) {
                requestCurrentLocation(true);
                return true;
            }
            if (id == 12) {
                showVersionAndChangelog();
                return true;
            }
            if (id == 13) {
                appUpdates.check(true);
                return true;
            }
            if (id == 15 && performanceDiagnostics != null) {
                boolean next = AutoBenchmarkRunner.rendererChoice(store) != 1;
                store.setRendererV2Enabled(next);
                fogOverlayView.setRendererV2(next);
                if (next) {
                    store.setRendererV3Enabled(false);
                    fogOverlayView.setRendererV3(false);
                    store.setRendererV4Enabled(false);
                    fogOverlayView.setRendererV4(false);
                }
                performanceDiagnostics.reset();
                Toast.makeText(this,
                        next ? "Experimental Renderer V2 enabled"
                                : "Renderer V1 restored",
                        Toast.LENGTH_SHORT).show();
                return true;
            }
            if (id == 16 && performanceDiagnostics != null) {
                boolean next = AutoBenchmarkRunner.rendererChoice(store) != 2;
                store.setRendererV3Enabled(next);
                if (next) {
                    store.setRendererV2Enabled(false);
                    fogOverlayView.setRendererV2(false);
                    store.setRendererV4Enabled(false);
                    fogOverlayView.setRendererV4(false);
                    lastGpsCorridorLoadAt = 0;
                    refreshGpsCorridors();
                }
                fogOverlayView.setRendererV3(next);
                performanceDiagnostics.reset();
                Toast.makeText(this, next ? "GPS corridor renderer V3"
                                : "Renderer V1 restored", Toast.LENGTH_SHORT).show();
                return true;
            }
            if (id == 17 && performanceDiagnostics != null) {
                boolean next = AutoBenchmarkRunner.rendererChoice(store) != 3;
                store.setRendererV4Enabled(next);
                if (next) {
                    store.setRendererV2Enabled(false);
                    store.setRendererV3Enabled(false);
                    fogOverlayView.setRendererV2(false);
                    fogOverlayView.setRendererV3(false);
                    lastGpsCorridorLoadAt = 0;
                    refreshGpsCorridors();
                }
                fogOverlayView.setRendererV4(next);
                performanceDiagnostics.reset();
                Toast.makeText(this, next ? "Renderer V4 organic contour"
                                : "Renderer V1 restored", Toast.LENGTH_SHORT).show();
                return true;
            }
            if (id == 14) {
                showPerformanceDiagnostics();
                return true;
            }
            if (id == 18 && performanceDiagnostics != null) {
                showMapCompositorExperiment();
                return true;
            }
            if (id == 19 && performanceDiagnostics != null) {
                showFrameSyncExperiment();
                return true;
            }
            if (id == 4) {
                openUrl(SOURCE_URL);
                return true;
            }
            if (id == 5) {
                openUrl(PRIVACY_URL);
                return true;
            }
            return false;
    }

    private void importOfflineMap(@NonNull Uri uri) {
        if (mapIoExecutor == null) return;

        Toast.makeText(
                this,
                R.string.offline_map_importing,
                Toast.LENGTH_LONG
        ).show();

        mapIoExecutor.execute(() -> {
            try {
                long bytes = OfflineMapStore.importFromUri(this, uri);
                store.setOfflineMapEnabled(true);

                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    loadActiveMapStyle();
                    Toast.makeText(
                            this,
                            getString(
                                    R.string.offline_map_import_success,
                                    Formatter.formatFileSize(this, bytes)
                            ),
                            Toast.LENGTH_LONG
                    ).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    Toast.makeText(
                            this,
                            R.string.offline_map_import_failed,
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        });
    }

    private void removeOfflineMap() {
        store.setOfflineMapEnabled(false);

        if (map == null) {
            if (mapIoExecutor != null) {
                mapIoExecutor.execute(() -> OfflineMapStore.delete(this));
            }
            return;
        }

        map.setStyle(new Style.Builder().fromUri(MAP_STYLE_URI), style -> {
            configureLoadedMapStyle(style);

            if (mapIoExecutor != null) {
                mapIoExecutor.execute(() -> {
                    boolean removed = OfflineMapStore.delete(this);
                    if (removed) {
                        runOnUiThread(() -> {
                            if (!isDestroyed()) {
                                Toast.makeText(
                                        this,
                                        R.string.offline_map_removed,
                                        Toast.LENGTH_SHORT
                                ).show();
                            }
                        });
                    }
                });
            }
        });
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
            String gps = Float.isFinite(lastAccuracy) && lastAccuracy > 0f
                    ? "GPS ±" + Math.round(lastAccuracy) + " m"
                    : "GPS accuracy unavailable";
            if (!accepted) gps += " · fix ignored";
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
                        boolean removed = !stored.containsAll(visited);
                        visited.clear();
                        visited.addAll(stored);

                        Set<String> coverageSnapshot = new HashSet<>(stored);
                        if (overlayExecutor != null) {
                            overlayExecutor.execute(() -> {
                                synchronized (coverageIndex) {
                                    coverageIndex.replaceAll(coverageSnapshot);
                                }
                                runOnUiThread(() -> {
                                    if (!isDestroyed() && fogOverlayView != null)
                                        fogOverlayView.onCoverageChanged(removed);
                                });
                            });
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

    /** Background route snapshot. The persisted H3 and GPS rows are untouched. */
    private void refreshGpsCorridors() {
        if (dataExecutor == null || repository == null || fogOverlayView == null
                || (AutoBenchmarkRunner.rendererChoice(store) != 2 && AutoBenchmarkRunner.rendererChoice(store) != 3)) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastGpsCorridorLoadAt < 15_000L) return;
        lastGpsCorridorLoadAt = now;
        long generation = gpsCorridorGeneration.incrementAndGet();
        dataExecutor.execute(() -> {
            try {
                FogGpsCorridors snapshot = FogGpsCorridors.from(repository.loadGpsPoints());
                runOnUiThread(() -> {
                    if (!isDestroyed() && generation == gpsCorridorGeneration.get()
                            && (AutoBenchmarkRunner.rendererChoice(store) == 2
                                    || AutoBenchmarkRunner.rendererChoice(store) == 3)) {
                        fogOverlayView.setGpsCorridors(snapshot);
                    }
                });
            } catch (RuntimeException ignored) {
                // Keep last good snapshot. Never block GPS tracking.
            }
        });
    }

    private void scheduleViewportOverlay() {
        // The old per-viewport H3 union caused 400ms-plus gaps and
        // square bitmap boundaries when the user panned. Fog tiles now
        // fetch their own exact local H3 cells and persist across pans.
        if (fogOverlayView != null && !isDestroyed()) {
            fogOverlayView.requestTilesNow();
        }
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
                if (fogOverlayView != null) {
                    ArrayList<FogOverlayView.PoiMarker> opened =
                            new ArrayList<>(result.discovered.size());
                    for (DiscoveryEntity discovery : result.discovered) {
                        opened.add(new FogOverlayView.PoiMarker(
                                discovery.latitude, discovery.longitude,
                                discovery.category, discovery.subclass));
                    }
                    ArrayList<FogOverlayView.PoiMarker> hints =
                            new ArrayList<>(result.hints.size());
                    for (PoiDiscoveryCandidate hint : result.hints) {
                        hints.add(new FogOverlayView.PoiMarker(
                                hint.latitude, hint.longitude,
                                hint.category, hint.subclass));
                    }
                    fogOverlayView.setDiscoveries(opened, hints);
                }
            });
        });
    }

    private String installedVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? getString(R.string.version_unknown)
                    : info.versionName;
        } catch (PackageManager.NameNotFoundException error) {
            return getString(R.string.version_unknown);
        }
    }

    private void showFrameSyncExperiment() {
        boolean next = !AutoBenchmarkRunner.mapFrameSync(store);
        new AlertDialog.Builder(this)
                .setTitle("Experimental fog frame synchronization")
                .setMessage(next
                        ? "Schedule fog redraw after MapLibre rendered-frame callbacks? "
                                + "This may reduce visible map/fog offset, or increase "
                                + "lag. An 80ms fallback prevents a frozen overlay. "
                                + "Existing coverage and GPS history do not change."
                        : "Restore standard camera-driven fog redraw? "
                                + "No restart or exploration-data change is needed.")
                .setPositiveButton("Switch", (dialog, which) -> {
                    cancelMapFrameFallback();
                    store.setFogMapFrameSyncEnabled(next);
                    fogOverlayView.setMapFrameSyncEnabled(next);
                    if (performanceDiagnostics != null) performanceDiagnostics.reset();
                    fogOverlayView.requestTilesNow();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showMapCompositorExperiment() {
        if (tracking) {
            new AlertDialog.Builder(this)
                    .setTitle("Map compositor experiment")
                    .setMessage("Stop Exploration before restarting the map renderer. "
                            + "Tracking and exploration history will not be changed.")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        boolean next = !AutoBenchmarkRunner.textureView(store);
        new AlertDialog.Builder(this)
                .setTitle("Experimental map synchronization")
                .setMessage(next
                        ? "Try MapLibre TextureView instead of SurfaceView? "
                                + "This may reduce visible map/fog phase lag, "
                                + "but can LOWER FPS and increase battery usage. "
                                + "Only the map screen restarts; routes and H3 "
                                + "history stay unchanged. Switch back here if worse."
                        : "Restore the faster default SurfaceView compositor? "
                                + "Only the map screen restarts. "
                                + "Exploration history stays unchanged.")
                .setPositiveButton("Switch and restart", (dialog, which) -> {
                    store.setTextureMapTestEnabled(next);
                    recreate();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String buildPerformanceReport() {
        if (performanceDiagnostics == null) return "";
        StringBuilder report = new StringBuilder();
        report.append("App: ").append(installedVersion()).append('\n');
        report.append("Device: ").append(Build.MANUFACTURER)
                .append(' ').append(Build.MODEL)
                .append("; Android API ").append(Build.VERSION.SDK_INT).append('\n');
        report.append("Offline map: ").append(store.isOfflineMapEnabled())
                .append("; discoveries: ").append(store.isDiscoveriesEnabled())
                .append("; tracking: ").append(tracking).append('\n');
        report.append("Fog synchronization: ")
                .append(AutoBenchmarkRunner.mapFrameSync(store)
                        ? "MapLibre frame (experimental)" : "Standard camera events")
                .append('\n');
        report.append("Map compositor: ")
                .append(mapView != null
                        && mapView.getRenderView() instanceof android.view.TextureView
                        ? "TextureView (experimental)" : "GLSurfaceView (default)")
                .append('\n');
        report.append("Fog renderer: ")
                .append(AutoBenchmarkRunner.rendererChoice(store) == 3 ? "V4 organic contour"
                        : AutoBenchmarkRunner.rendererChoice(store) == 2 ? "V3 GPS corridors"
                        : AutoBenchmarkRunner.rendererChoice(store) == 1 ? "V2 SDF prototype" : "V1 legacy")
                .append('\n');
        report.append("Map zoom: ");
        if (map != null && map.getCameraPosition() != null) {
            report.append(String.format(Locale.US, "%.1f", map.getCameraPosition().zoom));
        } else {
            report.append("not ready");
        }
        report.append("\n\n").append(performanceDiagnostics.report());
        return report.toString();
    }

    private void showPerformanceDiagnostics() {
        if (performanceDiagnostics == null) return;
        String content = buildPerformanceReport();

        TextView notes = new TextView(this);
        notes.setText(content);
        notes.setTextSize(13f);
        notes.setTextIsSelectable(true);
        notes.setTypeface(Typeface.MONOSPACE);
        notes.setPadding(dp(20), dp(12), dp(20), dp(20));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(notes);
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_performance_diagnostics)
                .setView(scroll)
                .setPositiveButton(R.string.diagnostics_share,
                        (dialog, which) -> sharePerformanceDiagnostics(content))
                .setNeutralButton(R.string.diagnostics_reset,
                        (dialog, which) -> resetPerformanceDiagnostics())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void sharePerformanceDiagnostics(String content) {
        if (AutoBenchmarkRunner.isRunning()) {
            Toast.makeText(this, "Benchmark running: use main menu to stop",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (performanceDiagnostics == null) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, content);
        startActivity(Intent.createChooser(send,
                getString(R.string.diagnostics_share)));
    }

    private void resetPerformanceDiagnostics() {
        if (AutoBenchmarkRunner.isRunning()) {
            Toast.makeText(this, "Diagnostics reset automatically during benchmark",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (performanceDiagnostics == null) return;
        // Keep GPS/Room/H3 coverage, fog caches and renderer settings intact.
        performanceDiagnostics.reset();
        Toast.makeText(this, R.string.diagnostics_reset_done, Toast.LENGTH_SHORT).show();
    }

    private void showVersionAndChangelog() {
        String version = installedVersion();
        String header = getString(R.string.about_version, version);
        StringBuilder content = new StringBuilder(header)
                .append("\n").append(getString(R.string.about_development_build))
                .append("\n\n");

        // Keep the release notes bundled in the APK: no browser, network,
        // storage permissions or connected account is needed.
        try (InputStream stream = getAssets().open("changelog.txt");
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append('\n');
            }
        } catch (Exception error) {
            content.append(getString(R.string.about_changelog_unavailable));
        }

        TextView notes = new TextView(this);
        notes.setText(content.toString());
        notes.setTextSize(14f);
        notes.setTextIsSelectable(true);
        notes.setPadding(dp(22), dp(12), dp(22), dp(20));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.addView(notes);

        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_about)
                .setView(scroll)
                .setNeutralButton(R.string.menu_check_updates,
                        (dialog, which) -> appUpdates.check(true))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void clearDiscoveryLayers() {
        if (fogOverlayView != null) {
            fogOverlayView.setDiscoveries(Collections.emptyList(), Collections.emptyList());
        }
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
        if (AutoBenchmarkRunner.isRunning()) return false;
        if (map == null || map.getStyle() == null) return false;

        PointF screenPoint = map.getProjection().toScreenLocation(point);
        float touchRadius = dp(15);
        List<Feature> features = map.queryRenderedFeatures(
                new RectF(
                        screenPoint.x - touchRadius, screenPoint.y - touchRadius,
                        screenPoint.x + touchRadius, screenPoint.y + touchRadius),
                DISCOVERED_LAYER_ID,
                DISCOVERY_HINT_LAYER_ID
        );
        // The visible emoji disc is 13.5 dp across its radius while the
        // MapLibre hit layer is smaller. Match the touch area to the actual
        // emoji icon, and prefer the closest place when several overlap.
        Feature feature = null;
        float closestDistanceSquared = touchRadius * touchRadius;
        for (Feature candidate : features) {
            if (!(candidate.geometry() instanceof Point)) continue;
            Point poi = (Point) candidate.geometry();
            PointF screen = map.getProjection().toScreenLocation(
                    new org.maplibre.android.geometry.LatLng(
                            poi.latitude(), poi.longitude()));
            float dx = screen.x - screenPoint.x;
            float dy = screen.y - screenPoint.y;
            float distanceSquared = dx * dx + dy * dy;
            if (DiscoveryHitPolicy.shouldReplace(
                    distanceSquared,
                    "discovered".equals(candidate.getStringProperty("state")),
                    closestDistanceSquared,
                    feature != null
                            && "discovered".equals(feature.getStringProperty("state")),
                    feature != null,
                    touchRadius * touchRadius)) {
                feature = candidate;
                closestDistanceSquared = distanceSquared;
            }
        }
        if (feature == null) return false;
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

    private void showInitialFogLayer() {
        if (fogOverlayView != null) fogOverlayView.setFogEnabled(true);
    }

    private void clearFogLayer() {
        if (fogOverlayView != null) fogOverlayView.setFogEnabled(false);
    }

    private void zoomMap(boolean zoomIn) {
        if (AutoBenchmarkRunner.isRunning() || map == null) return;
        map.animateCamera(
                zoomIn
                        ? org.maplibre.android.camera.CameraUpdateFactory.zoomIn()
                        : org.maplibre.android.camera.CameraUpdateFactory.zoomOut(),
                300
        );
    }

    private void renderCurrentLocation() {
        if (fogOverlayView != null) {
            fogOverlayView.setCurrentLocation(hasLocation, lastLat, lastLng);
        }
        if (!hasLocation || map == null || map.getStyle() == null) return;

        GeoJsonSource source = map.getStyle().getSourceAs(LOCATION_SOURCE_ID);
        if (source == null) return;

        source.setGeoJson(Feature.fromGeometry(Point.fromLngLat(lastLng, lastLat)));
    }

    private void centerOnCurrentLocation(boolean zoomIn) {
        if (AutoBenchmarkRunner.isRunning()) return;
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
        mapLifecycleResumed = true;
        mapView.onResume();
        if (benchmarkRunner != null) benchmarkRunner.onResume();
        // A live Activity can survive onLowMemory without detaching the
        // fog view. Restart geographic tile demand even if the camera
        // never moves after the user returns to the app.
        scheduleViewportOverlay();

        tracking = store.isTrackingActive();
        locationEnabled = isSystemLocationEnabled();
        loadStoredLocation();
        refreshVisitedFromDatabase();
        renderCurrentLocation();
        setLocateAvailable(hasLocation);
        updateUi(true);
        if (appUpdates != null) appUpdates.onResume();
    }

    @Override
    protected void onPause() {
        if (benchmarkRunner != null) benchmarkRunner.onPause();
        mapLifecycleResumed = false;
        cancelMapFrameFallback();
        frameStartTimes.clear();
        if (appUpdates != null) appUpdates.onPause();
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
        if (fogOverlayView != null) fogOverlayView.trimForLowMemory();
        mapView.onLowMemory();
    }

    @Override
    protected void onDestroy() {
        cancelMapFrameFallback();
        if (mapView != null) {
            mapView.removeOnDidFinishRenderingFrameListener(mapFrameListener);
            mapView.removeOnWillStartRenderingFrameListener(mapFrameStartListener);
        }
        frameStartTimes.clear();
        if (frameMetrics != null) frameMetrics.stop();
        if (appUpdates != null) appUpdates.close();
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
