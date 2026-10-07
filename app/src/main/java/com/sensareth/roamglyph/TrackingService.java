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
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.uber.h3core.H3Core;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class TrackingService extends Service {
    public static final String ACTION_START = "com.sensareth.roamglyph.action.START";
    public static final String ACTION_STOP = "com.sensareth.roamglyph.action.STOP";
    public static final String ACTION_STATE_CHANGED = "com.sensareth.roamglyph.action.STATE_CHANGED";
    public static final String EXTRA_LAT = "lat";
    public static final String EXTRA_LNG = "lng";
    public static final String EXTRA_ACCURACY = "accuracy";
    public static final String EXTRA_ACCEPTED = "accepted";
    public static final String EXTRA_TRACKING = "tracking";

    private static final int H3_RESOLUTION = 13;
    private static final long UPDATE_INTERVAL_MS = 2000L;
    private static final float MIN_DISTANCE_M = 4f;
    private static final float MAX_ACCEPTED_ACCURACY_M = 35f;
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "exploration";

    private FusedLocationProviderClient fused;
    private LocationCallback callback;
    private VisitedStore store;
    private H3Core h3;
    private boolean updatesStarted;

    @Override
    public void onCreate() {
        super.onCreate();
        fused = LocationServices.getFusedLocationProviderClient(this);
        store = new VisitedStore(this);
        try {
            h3 = H3Core.newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
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

        boolean fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (!fine && !coarse) {
            stopExploration();
            return;
        }

        LocationRequest request = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                UPDATE_INTERVAL_MS
        )
                .setMinUpdateIntervalMillis(1000L)
                .setMinUpdateDistanceMeters(MIN_DISTANCE_M)
                .build();

        callback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                Location location = result.getLastLocation();
                if (location != null) handleLocation(location);
            }
        };

        fused.requestLocationUpdates(request, callback, Looper.getMainLooper());
        updatesStarted = true;
    }

    private void handleLocation(Location location) {
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

    private void broadcastState(@Nullable Location location, boolean accepted) {
        Intent update = new Intent(ACTION_STATE_CHANGED);
        update.setPackage(getPackageName());
        update.putExtra(EXTRA_TRACKING, store.isTrackingActive());
        update.putExtra(EXTRA_ACCEPTED, accepted);
        if (location != null) {
            update.putExtra(EXTRA_LAT, location.getLatitude());
            update.putExtra(EXTRA_LNG, location.getLongitude());
            update.putExtra(EXTRA_ACCURACY, location.getAccuracy());
        }
        sendBroadcast(update);
    }

    private void stopExploration() {
        if (callback != null) {
            fused.removeLocationUpdates(callback);
            callback = null;
        }
        updatesStarted = false;
        store.setTrackingActive(false);
        broadcastState(null, true);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Исследование карты",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Фоновое GPS-исследование Roamglyph");
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
                .setContentTitle("Roamglyph исследует карту")
                .setContentText(notificationText(visitedCount))
                .setContentIntent(openPending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .addAction(0, "Остановить", stopPending)
                .build();
    }

    private void updateNotification(int visitedCount) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        try {
            manager.notify(NOTIFICATION_ID, buildNotification(visitedCount));
        } catch (SecurityException ignored) {
            // Android 13+ can hide drawer notifications if POST_NOTIFICATIONS is denied.
            // The foreground service itself remains valid and visible in system task UI.
        }
    }

    private String notificationText(int visitedCount) {
        double areaM2 = visitedCount * 43.87;
        if (areaM2 >= 1_000_000) {
            return String.format(Locale.getDefault(), "%d клеток · ≈%.2f км²", visitedCount, areaM2 / 1_000_000.0);
        }
        return String.format(Locale.getDefault(), "%d клеток · ≈%.0f м²", visitedCount, areaM2);
    }

    @Override
    public void onDestroy() {
        if (callback != null) fused.removeLocationUpdates(callback);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
