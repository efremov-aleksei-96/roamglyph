package com.sensareth.roamglyph.updates;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
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
 * First-party test-channel updater. Checks at most once per day or on demand;
 * downloads only after confirmation. Android ALWAYS handles final installation.
 *
 * Never consumes expiring Actions artifacts, untrusted mirror URLs, APKs with
 * unexpected certificates, or releases with an absent SHA-256 asset digest.
 */
public final class AppUpdateManager {
    private static final String RELEASES_API =
            "https://api.github.com/repos/efremov-aleksei-96/roamglyph/releases?per_page=30";
    private static final String PREFERENCES = "roamglyph_update_preferences";
    private static final String LAST_CHECK = "last_successful_check";
    private static final long CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private static final long MAX_APK_BYTES = 150L * 1024L * 1024L;
    private static final int MAX_CATALOG_BYTES = 2 * 1024 * 1024;

    private final AppCompatActivity activity;
    private final BooleanSupplier tracking;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private File pendingInstall;

    private static final class Release {
        final int code;
        final String name;
        final String url;
        final String digest;

        Release(int code, String name, String url, String digest) {
            this.code = code;
            this.name = name;
            this.url = url;
            this.digest = digest;
        }
    }

    public AppUpdateManager(AppCompatActivity activity, BooleanSupplier tracking) {
        this.activity = activity;
        this.tracking = tracking;
    }

    public void onResume() {
        if (pendingInstall != null
                && activity.getPackageManager().canRequestPackageInstalls()) {
            File file = pendingInstall;
            pendingInstall = null;
            if (file.isFile()) showSystemInstaller(file);
        }
        check(false);
    }

    public void check(boolean manuallyRequested) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFERENCES, 0);
        long now = System.currentTimeMillis();
        long last = prefs.getLong(LAST_CHECK, 0L);
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
                prefs.edit().putLong(LAST_CHECK, System.currentTimeMillis()).apply();
                onUi(() -> {
                    if (release == null) {
                        if (manuallyRequested) new AlertDialog.Builder(activity)
                                .setTitle("RoamGlyph updates")
                                .setMessage("This development build is up to date. "
                                        + "No newer verified test release was found.")
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                    } else {
                        offerDownload(release);
                    }
                });
            } catch (Exception error) {
                onUi(() -> {
                    if (manuallyRequested) new AlertDialog.Builder(activity)
                            .setTitle("Update check failed")
                            .setMessage("Could not load the verified GitHub development "
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
                if (entry == null || entry.optBoolean("draft")) continue;
                String tag = entry.optString("tag_name");
                int code = DevelopmentReleasePolicy.versionCode(tag);
                if (code <= installedCode || (best != null && code <= best.code)) {
                    continue;
                }
                // Official stable releases use another signing certificate.
                // Only explicitly marked prereleases in this developer channel
                // can update the currently installed test build.
                if (!entry.optBoolean("prerelease")) continue;
                JSONArray assets = entry.optJSONArray("assets");
                if (assets == null) continue;
                for (int j = 0; j < assets.length(); j++) {
                    JSONObject asset = assets.optJSONObject(j);
                    if (asset == null) continue;
                    String name = asset.optString("name");
                    String url = asset.optString("browser_download_url");
                    String digest = DevelopmentReleasePolicy.digestHex(
                            asset.optString("digest"));
                    if (digest != null
                            && DevelopmentReleasePolicy.isExpectedApk(tag, name)
                            && DevelopmentReleasePolicy.isTrustedAssetUrl(url)) {
                        best = new Release(code, name, url, digest);
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
                    .setMessage("A verified RoamGlyph development update is ready. "
                            + "Stop exploration before updating so the running GPS "
                            + "session is not interrupted. Then check again.")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle("Update available: dev." + release.code)
                .setMessage("Download and install " + release.name
                        + "?\n\nYour local exploration history and settings "
                        + "are preserved. Android will ask you to confirm installation.")
                .setPositiveButton("Download", (dialog, which) -> download(release))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void download(Release release) {
        if (!busy.compareAndSet(false, true)) {
            toast("Another update operation is in progress.");
            return;
        }
        cancelled.set(false);
        ProgressBar progress = new ProgressBar(activity, null,
                android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        int inset = (int) (24 * activity.getResources()
                .getDisplayMetrics().density + 0.5f);
        progress.setPadding(inset, inset, inset, inset);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Downloading verified update")
                .setView(progress)
                .setNegativeButton("Cancel", (d, which) -> cancelled.set(true))
                .setCancelable(false)
                .create();
        dialog.show();

        executor.execute(() -> {
            File candidate = null;
            try {
                File directory = new File(activity.getCacheDir(), "updates");
                if (!directory.isDirectory() && !directory.mkdirs()) {
                    throw new IllegalStateException("Could not create update cache");
                }
                candidate = new File(directory, "roamglyph-update.apk");
                if (candidate.exists() && !candidate.delete()) {
                    throw new IllegalStateException("Could not replace cached update");
                }
                HttpURLConnection connection = open(release.url);
                try {
                    long length = connection.getContentLengthLong();
                    if (length > MAX_APK_BYTES) throw new IllegalStateException(
                            "APK exceeds download size limit");
                    MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                    long total = 0;
                    try (InputStream input = connection.getInputStream();
                         FileOutputStream output = new FileOutputStream(candidate)) {
                        byte[] chunk = new byte[65536];
                        int n;
                        while ((n = input.read(chunk)) != -1) {
                            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                                throw new InterruptedException("Download cancelled");
                            }
                            total += n;
                            if (total > MAX_APK_BYTES) throw new IllegalStateException(
                                    "APK exceeds download size limit");
                            output.write(chunk, 0, n);
                            sha256.update(chunk, 0, n);
                            if (length > 0) {
                                int percent = (int) Math.min(100, total * 100 / length);
                                onUi(() -> {
                                    progress.setIndeterminate(false);
                                    progress.setProgress(percent);
                                });
                            }
                        }
                        output.getFD().sync();
                    }
                    if (total == 0 || (length > 0 && total != length)) {
                        throw new IllegalStateException("Incomplete APK download");
                    }
                    String actual = hex(sha256.digest());
                    if (!release.digest.equals(actual)) {
                        throw new SecurityException("Downloaded APK SHA-256 mismatch");
                    }
                    verifyApkIdentity(candidate, release.code);
                } finally {
                    connection.disconnect();
                }

                File ready = candidate;
                onUi(() -> {
                    dialog.dismiss();
                    showInstallPrompt(ready);
                });
            } catch (Exception error) {
                if (candidate != null) candidate.delete();
                boolean wasCancelled = cancelled.get();
                onUi(() -> {
                    dialog.dismiss();
                    if (!wasCancelled) new AlertDialog.Builder(activity)
                            .setTitle("Update download failed")
                            .setMessage(error.getMessage())
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });
            } finally {
                busy.set(false);
            }
        });
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
            throw new SecurityException("APK has an unexpected signing identity");
        }
        byte[] certDigest = MessageDigest.getInstance("SHA-256")
                .digest(signatures[0].toByteArray());
        if (!pm.hasSigningCertificate(activity.getPackageName(), certDigest,
                PackageManager.CERT_INPUT_SHA256)) {
            throw new SecurityException("APK is signed with an incompatible certificate");
        }
    }

    private void showInstallPrompt(File file) {
        if (tracking.getAsBoolean()) {
            new AlertDialog.Builder(activity)
                    .setTitle("Update downloaded")
                    .setMessage("Stop exploration before installing the update, "
                            + "then open Check for updates again.")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            pendingInstall = file;
            return;
        }
        pendingInstall = file;
        beginInstall(file);
    }

    private void beginInstall(File file) {
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity)
                    .setTitle("Allow updates from RoamGlyph")
                    .setMessage("Android needs one-time permission to install "
                            + "APK updates from RoamGlyph. Enable 'Allow from "
                            + "this source' and return to the app.")
                    .setPositiveButton("Open settings", (d, which) -> {
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
        install.setDataAndType(uri, "application/vnd.android.package-archive");
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(install);
        } catch (Exception error) {
            new AlertDialog.Builder(activity)
                    .setTitle("Cannot open Android installer")
                    .setMessage(error.getMessage())
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
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
            if (!activity.isFinishing() && !activity.isDestroyed()) action.run();
        });
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }

    public void close() {
        cancelled.set(true);
        executor.shutdownNow();
    }
}
