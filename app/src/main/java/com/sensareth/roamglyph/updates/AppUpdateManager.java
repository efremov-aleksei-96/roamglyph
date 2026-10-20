package com.sensareth.roamglyph.updates;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * First-party test-channel updater. Catalog and APK identity must be verified
 * before Android's own package installer is invoked.
 *
 * APK downloads are owned by Android DownloadManager, not by this Activity.
 * An in-progress download therefore survives Home, activity recreation and
 * process death. Its system id and expected release identity persist in the
 * application's private preferences for verification on the next launch.
 */
public final class AppUpdateManager {
    private static final String RELEASES_API =
            "https://api.github.com/repos/efremov-aleksei-96/roamglyph/releases?per_page=30";
    private static final String PREFERENCES = "roamglyph_update_preferences";
    private static final String LAST_CHECK = "last_successful_check";
    private static final String DOWNLOAD_ID = "system_download_id";
    private static final String DOWNLOAD_CODE = "system_download_code";
    private static final String DOWNLOAD_TAG = "system_download_tag";
    private static final String DOWNLOAD_NAME = "system_download_name";
    private static final String DOWNLOAD_URL = "system_download_url";
    private static final String DOWNLOAD_DIGEST = "system_download_digest";
    private static final String DOWNLOAD_FAILURE_REASON = "system_download_failure_reason";
    private static final long CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private static final long MAX_APK_BYTES = 150L * 1024L * 1024L;
    private static final int MAX_CATALOG_BYTES = 2 * 1024 * 1024;
    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final String PENDING_MIME = "application/octet-stream";

    private final AppCompatActivity activity;
    private final BooleanSupplier tracking;
    private final SharedPreferences preferences;
    private final DownloadManager downloads;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicBoolean verifying = new AtomicBoolean(false);
    private boolean foreground;
    private volatile boolean disposed;
    private long installerPromptedId = -1L;
    private File pendingInstall;
    private File lastVerifiedStage;
    // Only accessed on the Activity thread. A catalog result is never lost
    // because the user pressed Home before its callback could be presented.
    private boolean deferredCatalogReady;
    private boolean deferredCatalogManual;
    private Release deferredCatalogRelease;

    private final BroadcastReceiver completionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())
                    && intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                    == preferences.getLong(DOWNLOAD_ID, -1L)) {
                inspectDownload(false);
            }
        }
    };

    private static final class Release {
        final int code;
        final String tag;
        final String name;
        final String url;
        final String digest;
        final long sizeBytes;

        Release(int code, String tag, String name, String url,
                String digest, long sizeBytes) {
            this.code = code;
            this.tag = tag;
            this.name = name;
            this.url = url;
            this.digest = digest;
            this.sizeBytes = sizeBytes;
        }
    }

    public AppUpdateManager(AppCompatActivity activity, BooleanSupplier tracking) {
        this.activity = activity;
        this.tracking = tracking;
        preferences = activity.getSharedPreferences(PREFERENCES, 0);
        downloads = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        ContextCompat.registerReceiver(activity, completionReceiver,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_EXPORTED);
    }

    public void onResume() {
        foreground = true;
        if (pendingInstall != null
                && activity.getPackageManager().canRequestPackageInstalls()) {
            File file = pendingInstall;
            pendingInstall = null;
            showSystemInstaller(file);
        }
        inspectDownload(false);
        if (readRecord() == null) {
            if (showDeferredDownloadFailure()) return;
            if (deferredCatalogReady) {
                Release deferred = deferredCatalogRelease;
                boolean manual = deferredCatalogManual;
                deferredCatalogRelease = null;
                deferredCatalogReady = false;
                deferredCatalogManual = false;
                presentCatalog(deferred, manual);
            } else {
                check(false);
            }
        }
    }

    public void onPause() {
        foreground = false;
    }

    public void check(boolean manuallyRequested) {
        if (disposed) return;
        if (readRecord() != null) {
            inspectDownload(manuallyRequested);
            return;
        }
        long now = System.currentTimeMillis();
        long last = preferences.getLong(LAST_CHECK, 0L);
        if (!manuallyRequested && last > 0 && now >= last
                && now - last < CHECK_INTERVAL_MS) return;
        if (!busy.compareAndSet(false, true)) {
            if (manuallyRequested) toast("An update check is already running.");
            return;
        }
        if (manuallyRequested) toast("Checking for RoamGlyph updates…");
        executor.execute(() -> {
            try {
                Release release = findLatestUpdate(installedCode());
                activity.runOnUiThread(() -> {
                    if (disposed || activity.isFinishing() || activity.isDestroyed()) return;
                    if (!foreground) {
                        deferredCatalogReady = true;
                        deferredCatalogRelease = release;
                        deferredCatalogManual = manuallyRequested;
                    } else {
                        presentCatalog(release, manuallyRequested);
                    }
                });
            } catch (Exception error) {
                onUi(() -> {
                    if (manuallyRequested) new AlertDialog.Builder(activity)
                            .setTitle("Update check failed")
                            .setMessage("Could not load verified GitHub development "
                                    + "releases. Check your connection and retry.\n\n"
                                    + error.getMessage())
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });
            } finally {
                busy.set(false);
            }
        });
    }

    private void presentCatalog(Release release, boolean manual) {
        // Record a completed check only once the result has been delivered
        // to the Activity or retained for an explicit foreground resume.
        preferences.edit().putLong(LAST_CHECK, System.currentTimeMillis()).apply();
        if (readRecord() != null) {
            inspectDownload(manual);
        } else if (release == null) {
            if (manual) new AlertDialog.Builder(activity)
                    .setTitle("RoamGlyph updates")
                    .setMessage("This development build is up to date. "
                            + "No newer verified test release was found.")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        } else {
            offerDownload(release);
        }
    }

    private int installedCode() {
        try {
            PackageInfo info = activity.getPackageManager().getPackageInfo(
                    activity.getPackageName(), 0);
            return (int) info.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException error) {
            return Integer.MAX_VALUE;
        }
    }

    private static Release findLatestUpdate(int installedCode) throws Exception {
        HttpURLConnection connection = open(RELEASES_API);
        try {
            JSONArray releases = new JSONArray(readText(connection, MAX_CATALOG_BYTES));
            Release best = null;
            for (int i = 0; i < releases.length(); i++) {
                JSONObject entry = releases.optJSONObject(i);
                if (entry == null || entry.optBoolean("draft")
                        || !entry.optBoolean("prerelease")) continue;
                String tag = entry.optString("tag_name");
                int code = DevelopmentReleasePolicy.versionCode(tag);
                if (code <= installedCode || (best != null && code <= best.code)) {
                    continue;
                }
                JSONArray assets = entry.optJSONArray("assets");
                if (assets == null) continue;
                for (int j = 0; j < assets.length(); j++) {
                    JSONObject asset = assets.optJSONObject(j);
                    if (asset == null) continue;
                    String name = asset.optString("name");
                    String url = asset.optString("browser_download_url");
                    String digest = DevelopmentReleasePolicy.digestHex(
                            asset.optString("digest"));
                    long size = asset.optLong("size", -1L);
                    if (digest != null && DevelopmentReleasePolicy.isAllowedAssetSize(size)
                            && DevelopmentReleasePolicy.isExpectedApk(tag, name)
                            && DevelopmentReleasePolicy.isTrustedAssetUrl(url)) {
                        best = new Release(code, tag, name, url, digest, size);
                    }
                }
            }
            return best;
        } finally {
            connection.disconnect();
        }
    }

    private void offerDownload(Release release) {
        if (tracking.getAsBoolean()) {
            new AlertDialog.Builder(activity)
                    .setTitle("Update available")
                    .setMessage("Stop exploration before updating, then check again.")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle("Update available: dev." + release.code)
                .setMessage("Download " + release.name
                        + "?\n\nAndroid will continue downloading in the background. "
                        + "Return to RoamGlyph after the download completes to "
                        + "verify and install it. Your exploration history is preserved.")
                .setPositiveButton("Download", (dialog, which) -> enqueue(release))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void enqueue(Release release) {
        if (readRecord() != null) {
            inspectDownload(true);
            return;
        }
        try {
            String prefix = "dev-vc" + release.code + "-";
            if (!release.tag.startsWith(prefix)
                    || !DevelopmentReleasePolicy.isExpectedApk(release.tag, release.name)
                    || !DevelopmentReleasePolicy.isTrustedAssetUrl(release.url)
                    || release.digest == null || !release.digest.matches("[0-9a-f]{64}")
                    || !DevelopmentReleasePolicy.isAllowedAssetSize(release.sizeBytes)) {
                throw new SecurityException("Untrusted development release metadata");
            }
            File output = destination(release.name);
            File parent = output.getParentFile();
            if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
                throw new IllegalStateException("Cannot create private update directory");
            }
            if (output.exists() && !output.delete()) {
                throw new IllegalStateException("Cannot replace an old update file");
            }
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(release.url))
                    .setTitle("RoamGlyph dev." + release.code)
                    .setDescription("Download only; RoamGlyph verifies APK before installation")
                    // An unverified system download is deliberately not an APK.
                    // Never let notification/content viewers open Package Installer.
                    .setMimeType(PENDING_MIME)
                    .setAllowedOverMetered(true)
                    .setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationInExternalFilesDir(activity,
                            Environment.DIRECTORY_DOWNLOADS,
                            "roamglyph/" + release.name + ".download");
            long id = downloads.enqueue(request);
            boolean saved = preferences.edit()
                    .putLong(DOWNLOAD_ID, id)
                    .putInt(DOWNLOAD_CODE, release.code)
                    .putString(DOWNLOAD_TAG, release.tag)
                    .putString(DOWNLOAD_NAME, release.name)
                    .putString(DOWNLOAD_URL, release.url)
                    .putString(DOWNLOAD_DIGEST, release.digest)
                    .commit();
            if (!saved) {
                downloads.remove(id);
                throw new IllegalStateException("Cannot save download progress identifier");
            }
            toast("Downloading in background. Progress is in Android notifications.");
        } catch (Exception error) {
            new AlertDialog.Builder(activity)
                    .setTitle("Could not start download")
                    .setMessage(error.getMessage())
                    .setPositiveButton(android.R.string.ok, null).show();
        }
    }

    private UpdateDownloadRecord readRecord() {
        long id = preferences.getLong(DOWNLOAD_ID, -1L);
        if (id < 0L) return null;
        return new UpdateDownloadRecord(id,
                preferences.getInt(DOWNLOAD_CODE, -1),
                preferences.getString(DOWNLOAD_TAG, ""),
                preferences.getString(DOWNLOAD_NAME, ""),
                preferences.getString(DOWNLOAD_URL, ""),
                preferences.getString(DOWNLOAD_DIGEST, ""));
    }

    private File destination(String name) {
        File directory = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (directory == null) {
            throw new IllegalStateException("App update storage is unavailable");
        }
        // System-visible bytes stay opaque until SHA-256 and signature checks.
        return new File(new File(directory, "roamglyph"), name + ".download");
    }

    /** Preserve Android download failure through backgrounding and recreation. */
    private void rememberFailure(int reason) {
        preferences.edit()
                .putInt(DOWNLOAD_FAILURE_REASON, reason)
                .remove(LAST_CHECK)
                .commit();
    }

    /** Called only on the UI thread; never silently suppress a failed update. */
    private boolean showDeferredDownloadFailure() {
        int reason = preferences.getInt(DOWNLOAD_FAILURE_REASON, Integer.MIN_VALUE);
        if (reason == Integer.MIN_VALUE || !foreground) return false;
        preferences.edit().remove(DOWNLOAD_FAILURE_REASON).apply();
        new AlertDialog.Builder(activity)
                .setTitle("Update download failed")
                .setMessage("Android could not complete the download (code "
                        + reason + "). Your previous exploration data is unchanged.")
                .setPositiveButton("Retry", (dialog, which) -> check(true))
                .setNegativeButton("Later", null)
                .show();
        return true;
    }

    private void inspectDownload(boolean manual) {
        UpdateDownloadRecord record = readRecord();
        if (disposed || record == null) return;
        if (!record.isValid()) {
            forgetDownload(record, true);
            if (manual) toast("Unsafe saved update metadata was discarded.");
            return;
        }
        if (record.code <= installedCode()) {
            forgetDownload(record, true);
            return;
        }
        int status;
        int reason;
        long complete;
        long total;
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(record.id);
        try (Cursor cursor = downloads.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) {
                rememberFailure(-2);
                forgetDownload(record, false);
                if (foreground) showDeferredDownloadFailure();
                return;
            }
            status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
            complete = cursor.getLong(cursor.getColumnIndexOrThrow(
                    DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            total = cursor.getLong(cursor.getColumnIndexOrThrow(
                    DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
        } catch (RuntimeException error) {
            if (manual) toast("Cannot inspect download: " + error.getMessage());
            return;
        }
        if (status == DownloadManager.STATUS_SUCCESSFUL) {
            verifyAndOfferInstall(record, manual);
        } else if (status == DownloadManager.STATUS_FAILED) {
            rememberFailure(reason);
            forgetDownload(record, true);
            if (foreground) showDeferredDownloadFailure();
        } else if (manual && foreground) {
            String amount = total > 0
                    ? Math.min(100L, Math.max(0L, complete * 100L / total)) + "%"
                    : (complete / (1024 * 1024)) + " MiB";
            String state = status == DownloadManager.STATUS_PAUSED
                    ? "Paused by Android; it will resume when conditions allow."
                    : status == DownloadManager.STATUS_PENDING
                    ? "Waiting for Android to start." : "Downloading in background.";
            new AlertDialog.Builder(activity)
                    .setTitle("RoamGlyph dev." + record.code + " — " + amount)
                    .setMessage(state + "\n\nYou may leave the app. "
                            + "Android keeps this download and its progress.")
                    .setPositiveButton(android.R.string.ok, null)
                    .setNegativeButton("Cancel download", (d, which) ->
                            forgetDownload(record, true))
                    .show();
        }
    }

    private void verifyAndOfferInstall(UpdateDownloadRecord record, boolean manual) {
        if (!foreground || (!manual && installerPromptedId == record.id)
                || !verifying.compareAndSet(false, true)) return;
        executor.execute(() -> {
            try {
                File apk = stageAndVerify(record);
                // This callback must also reclaim its staging file when the
                // Activity goes into the background before verification ends.
                activity.runOnUiThread(() -> {
                    UpdateDownloadRecord current = readRecord();
                    if (disposed || !foreground || current == null
                            || current.id != record.id
                            || activity.isFinishing() || activity.isDestroyed()) {
                        apk.delete();
                        return;
                    }
                    if (lastVerifiedStage != null && !lastVerifiedStage.equals(apk)) {
                        lastVerifiedStage.delete();
                    }
                    lastVerifiedStage = apk;
                    installerPromptedId = record.id;
                    if (tracking.getAsBoolean()) {
                        new AlertDialog.Builder(activity)
                                .setTitle("Update verified")
                                .setMessage("Stop Exploration before installing. "
                                        + "The verified download has been retained.")
                                .setPositiveButton(android.R.string.ok, null).show();
                        return;
                    }
                    pendingInstall = apk;
                    beginInstall(apk);
                });
            } catch (Exception error) {
                // Activity rotation/process teardown is not a corrupt download.
                // Do not delete the system job that the replacement Activity owns.
                if (disposed || Thread.currentThread().isInterrupted()) return;
                rememberFailure(-3);
                forgetDownload(record, true);
                // If the error occurred after Home was pressed, the private
                // failure flag survives until the next Activity resume.
                onUi(this::showDeferredDownloadFailure);
            } finally {
                verifying.set(false);
            }
        });
    }

    private static void verifyFileDigest(File file, String expected) throws Exception {
        if (!file.isFile() || file.length() <= 0 || file.length() > MAX_APK_BYTES) {
            throw new SecurityException("Missing, empty, or oversized APK");
        }
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] chunk = new byte[65536];
            int count;
            while ((count = input.read(chunk)) != -1) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("APK verification interrupted");
                }
                sha256.update(chunk, 0, count);
            }
        }
        if (!expected.equals(hex(sha256.digest()))) {
            throw new SecurityException("Downloaded APK SHA-256 mismatch");
        }
    }

    /**
     * Verify the opaque download first. Stage a private *.apk only after
     * matching the pinned public release digest. No system-download
     * notification or file manager can install unverified bytes as an APK.
     */
    private File stageAndVerify(UpdateDownloadRecord record) throws Exception {
        if (!record.isValid() || record.code <= installedCode()) {
            throw new SecurityException("Untrusted or outdated update record");
        }
        File downloaded = destination(record.name);
        verifyFileDigest(downloaded, record.digest);
        File directory = new File(activity.getCacheDir(), "updates");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Could not create private update cache");
        }
        // Every Activity/verifier owns a distinct private staging path.
        // Rotation cannot cause an obsolete worker to truncate a new file.
        File staged = File.createTempFile("roamglyph-verified-", ".apk", directory);
        try {
            try (InputStream input = new FileInputStream(downloaded);
                 FileOutputStream output = new FileOutputStream(staged)) {
                byte[] chunk = new byte[65536];
                int n;
                while ((n = input.read(chunk)) != -1) {
                    if (disposed || Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("APK staging interrupted");
                    }
                    output.write(chunk, 0, n);
                }
                output.getFD().sync();
            }
            // Recheck the exact staged bytes: even an incomplete private copy
            // must never be passed to Android Package Installer.
            verifyFileDigest(staged, record.digest);
            verifyApkIdentity(staged, record.code);
            return staged;
        } catch (Exception failure) {
            staged.delete();
            throw failure;
        }
    }

    private void verifyApkIdentity(File file, int expectedVersion) throws Exception {
        PackageManager pm = activity.getPackageManager();
        PackageInfo archive = pm.getPackageArchiveInfo(file.getAbsolutePath(),
                PackageManager.GET_SIGNING_CERTIFICATES);
        if (archive == null || archive.signingInfo == null
                || !activity.getPackageName().equals(archive.packageName)
                || archive.getLongVersionCode() != expectedVersion) {
            throw new SecurityException("APK identity or version is invalid");
        }
        Signature[] signatures = archive.signingInfo.getApkContentsSigners();
        if (signatures == null || signatures.length != 1) {
            throw new SecurityException("Unexpected signing identity");
        }
        byte[] certDigest = MessageDigest.getInstance("SHA-256")
                .digest(signatures[0].toByteArray());
        if (!pm.hasSigningCertificate(activity.getPackageName(), certDigest,
                PackageManager.CERT_INPUT_SHA256)) {
            throw new SecurityException("APK is signed with an incompatible certificate");
        }
    }

    private void forgetDownload(UpdateDownloadRecord record, boolean removeSystemJob) {
        UpdateDownloadRecord current = readRecord();
        if (current == null || current.id != record.id) return;
        preferences.edit()
                .remove(DOWNLOAD_ID).remove(DOWNLOAD_CODE).remove(DOWNLOAD_TAG)
                .remove(DOWNLOAD_NAME).remove(DOWNLOAD_URL).remove(DOWNLOAD_DIGEST)
                .commit();
        if (removeSystemJob) downloads.remove(record.id);
        try {
            File output = destination(record.name);
            if (output.isFile()) output.delete();
        } catch (RuntimeException ignored) {
            // Storage can be unavailable after an external storage transition.
        }
        if (installerPromptedId == record.id) installerPromptedId = -1L;
        File directory = new File(activity.getCacheDir(), "updates");
        File[] staged = directory.listFiles((dir, name) ->
                name.startsWith("roamglyph-verified-") && name.endsWith(".apk"));
        if (staged != null) for (File verified : staged) verified.delete();
        pendingInstall = null;
        lastVerifiedStage = null;
    }

    private void beginInstall(File file) {
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity)
                    .setTitle("Allow updates from RoamGlyph")
                    .setMessage("Allow this app to install verified APK updates, "
                            + "then return to RoamGlyph.")
                    .setPositiveButton("Open settings", (dialog, which) -> {
                        Intent intent = new Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + activity.getPackageName()));
                        activity.startActivity(intent);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        pendingInstall = null;
        showSystemInstaller(file);
    }

    private void showSystemInstaller(File file) {
        if (!file.isFile() || tracking.getAsBoolean()) return;
        Uri uri = FileProvider.getUriForFile(activity,
                activity.getPackageName() + ".updates", file);
        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(uri, APK_MIME);
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(install);
        } catch (Exception error) {
            new AlertDialog.Builder(activity)
                    .setTitle("Cannot open Android installer")
                    .setMessage(error.getMessage())
                    .setPositiveButton(android.R.string.ok, null).show();
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "RoamGlyph-Test-Updater");
        connection.connect();
        if (!"https".equalsIgnoreCase(connection.getURL().getProtocol())) {
            connection.disconnect();
            throw new SecurityException("Insecure download redirect");
        }
        if (connection.getResponseCode() != 200) {
            int status = connection.getResponseCode();
            connection.disconnect();
            throw new IllegalStateException("GitHub returned HTTP " + status);
        }
        return connection;
    }

    private static String readText(HttpURLConnection connection, int maxBytes)
            throws Exception {
        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[8192];
            int n;
            while ((n = input.read(bytes)) != -1) {
                if (output.size() + n > maxBytes) {
                    throw new IllegalStateException("Release catalog exceeds size limit");
                }
                output.write(bytes, 0, n);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) builder.append(String.format(Locale.ROOT, "%02x", b & 255));
        return builder.toString();
    }

    private void onUi(Runnable action) {
        activity.runOnUiThread(() -> {
            if (!disposed && foreground && !activity.isFinishing()
                    && !activity.isDestroyed()) action.run();
        });
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }

    public void close() {
        disposed = true;
        foreground = false;
        try {
            activity.unregisterReceiver(completionReceiver);
        } catch (IllegalArgumentException ignored) {
            // Receiver already unregistered after a partial Activity lifecycle.
        }
        // The Android-managed download intentionally survives this executor.
        executor.shutdownNow();
    }
}
