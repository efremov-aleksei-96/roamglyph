package com.sensareth.roamglyph;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.uber.h3core.H3Core;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class TrackingService extends Service implements LocationListener {
    public static final String ACTION_START = "com.sensareth.roamglyph.action.START";
    public static final String ACTION_STOP = "com.sensareth.roamglyph.action.STOP";
    public static final String ACTION_STATE_CHANGED = "com.sensareth.roamglyph.action.STATE_CHANGED";
    public static final String EXTRA_LAT = "lat";
    public static final String EXTRA_LNG = "lng";
    public static final String EXTRA_ACCURACY = "accuracy";
    public static final String EXTRA_ACCEPTED = "accepted";
    public static final String EXTRA_TRACKING = "tracking";
    public static final String EXTRA_LOCATION_ENABLED = "location_enabled";

    private static final int H3_RESOLUTION = 13;
    private static final long UPDATE_INTERVAL_MS = 2_000L;
    private static final float MIN_DISTANCE_M = 4f;
    private static final float MAX_ACCEPTED_ACCURACY_M = 35f;
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "exploration";

    private LocationManager locationManager;
    private VisitedStore store;
    private H3Core h3;
    private boolean updatesStarted;

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = getSystemService(LocationManager.class);
        store = new VisitedStore(this);
        try {
            h3 = H3Core.newSystemInstance();
        } catch (Throwable error) {
            h3 = null;
        }
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopExploration();
            return START_NOT_STICKY;
        }

        if (h3 == null || !hasLocationPermission()) {
            store.setTrackingActive(false);
            broadcastState(null, false);
            stopSelf();
            return START_NOT_STICKY;
        }

        promoteToForeground();
        store.setTrackingActive(true);
        startLocationUpdates();
        broadcastState(null, true);
        return START_STICKY;
    }

    private void promoteToForeground() {
        Notification notification = buildNotification(store.load().size());
        ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        );
    }

    private void startLocationUpdates() {
        if (updatesStarted) return;

        if (!isLocationEnabled()) {
            broadcastState(null, false);
            return;
        }

        boolean registered = false;
        try {
            if (hasFineLocationPermission()
                    && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        UPDATE_INTERVAL_MS,
                        MIN_DISTANCE_M,
                        this,
                        Looper.getMainLooper()
                );
                registered = true;
            }

            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        UPDATE_INTERVAL_MS,
                        MIN_DISTANCE_M,
                        this,
                        Looper.getMainLooper()
                );
                registered = true;
            }
        } catch (SecurityException error) {
            stopExploration();
            return;
        }

        updatesStarted = registered;
        if (!registered) {
            broadcastState(null, false);
        }
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        store.saveLastLocation(location);

        if (location.hasAccuracy() && location.getAccuracy() > MAX_ACCEPTED_ACCURACY_M) {
            broadcastState(location, false);
            return;
        }

        Set<String> visited = store.load();
        String center = h3.latLngToCellAddress(
                location.getLatitude(),
                location.getLongitude(),
                H3_RESOLUTION
        );

        Set<String> revealed = new HashSet<>(h3.gridDisk(center, 1));
        revealed.add(center);

        if (visited.addAll(revealed)) {
            store.save(visited);
            updateNotification(visited.size());
        }

        broadcastState(location, true);
    }

    @Override
    public void onProviderEnabled(@NonNull String provider) {
        if (!updatesStarted && hasLocationPermission()) {
            startLocationUpdates();
        }
        broadcastState(null, true);
    }

    @Override
    public void onProviderDisabled(@NonNull String provider) {
        if (!isLocationEnabled()) {
            updatesStarted = false;
        }
        broadcastState(null, false);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onStatusChanged(String provider, int status, Bundle extras) {
        // Required for compatibility with older Android LocationListener APIs.
    }

    private void broadcastState(@Nullable Location location, boolean accepted) {
        Intent update = new Intent(ACTION_STATE_CHANGED);
        update.setPackage(getPackageName());
        update.putExtra(EXTRA_TRACKING, store.isTrackingActive());
        update.putExtra(EXTRA_ACCEPTED, accepted);
        update.putExtra(EXTRA_LOCATION_ENABLED, isLocationEnabled());
        if (location != null) {
            update.putExtra(EXTRA_LAT, location.getLatitude());
            update.putExtra(EXTRA_LNG, location.getLongitude());
            update.putExtra(EXTRA_ACCURACY, location.hasAccuracy() ? location.getAccuracy() : 0f);
        }
        sendBroadcast(update);
    }

    private void stopExploration() {
        try {
            locationManager.removeUpdates(this);
        } catch (SecurityException ignored) {
        }
        updatesStarted = false;
        store.setTrackingActive(false);
        broadcastState(null, true);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
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

    private boolean isLocationEnabled() {
        return locationManager != null && locationManager.isLocationEnabled();
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.notification_channel_description));
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(int visitedCount) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, TrackingService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_roamglyph)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(notificationText(visitedCount))
                .setContentIntent(openPending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .addAction(0, getString(R.string.notification_stop), stopPending)
                .build();
    }

    private void updateNotification(int visitedCount) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        try {
            manager.notify(NOTIFICATION_ID, buildNotification(visitedCount));
        } catch (SecurityException ignored) {
            // Android 13+ may hide notification-drawer entries when notifications are denied.
            // The foreground service remains visible through Android's foreground-service UI.
        }
    }

    private String notificationText(int visitedCount) {
        double areaM2 = visitedCount * 43.87;
        if (areaM2 >= 1_000_000) {
            return String.format(
                    Locale.getDefault(),
                    "%d cells · ≈%.2f km²",
                    visitedCount,
                    areaM2 / 1_000_000.0
            );
        }
        return String.format(
                Locale.getDefault(),
                "%d cells · ≈%.0f m²",
                visitedCount,
                areaM2
        );
    }

    @Override
    public void onDestroy() {
        try {
            locationManager.removeUpdates(this);
        } catch (SecurityException ignored) {
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
