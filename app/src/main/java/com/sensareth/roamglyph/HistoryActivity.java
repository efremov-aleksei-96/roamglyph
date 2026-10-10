package com.sensareth.roamglyph;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.sensareth.roamglyph.data.ExplorationRepository;
import com.sensareth.roamglyph.data.HistoryStatsSnapshot;
import com.sensareth.roamglyph.data.SessionEntity;
import com.sensareth.roamglyph.history.GpxExporter;
import com.sensareth.roamglyph.history.HistoryFormatter;

import java.io.OutputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class HistoryActivity extends AppCompatActivity {
    private static final int RECENT_SESSION_LIMIT = 100;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private LinearLayout content;
    private ProgressBar progress;
    private ExplorationRepository repository;
    private SessionEntity pendingGpxSession;
    private long pendingGpxThroughTimestampMs;

    private final ActivityResultLauncher<String> exportGpxLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.CreateDocument("application/gpx+xml"),
                    uri -> {
                        SessionEntity session = pendingGpxSession;
                        long throughTimestampMs = pendingGpxThroughTimestampMs;
                        pendingGpxSession = null;
                        pendingGpxThroughTimestampMs = 0L;

                        if (uri != null && session != null) {
                            writeSessionGpx(uri, session, throughTimestampMs);
                        }
                    }
            );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repository = new ExplorationRepository(this);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadHistory();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF111315);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), dp(8), dp(16), dp(8));
        toolbar.setBackgroundColor(0xFF1B1D20);

        TextView back = new TextView(this);
        back.setText(R.string.history_back);
        back.setTextColor(Color.WHITE);
        back.setTextSize(24f);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription(getString(R.string.history_back_description));
        back.setOnClickListener(v -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView title = new TextView(this);
        title.setText(R.string.history_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(20f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
        titleLp.setMargins(dp(8), 0, 0, 0);
        toolbar.addView(title, titleLp);

        root.addView(
                toolbar,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );

        progress = new ProgressBar(this);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(
                dp(36),
                dp(36)
        );
        progressLp.gravity = Gravity.CENTER_HORIZONTAL;
        progressLp.setMargins(0, dp(24), 0, dp(12));
        root.addView(progress, progressLp);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(32));
        scroll.addView(
                content,
                new ScrollView.LayoutParams(
                        ScrollView.LayoutParams.MATCH_PARENT,
                        ScrollView.LayoutParams.WRAP_CONTENT
                )
        );

        root.addView(
                scroll,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                )
        );

        setContentView(root);
    }

    private void loadHistory() {
        progress.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                HistoryStatsSnapshot snapshot = repository.loadHistoryStats(
                        RECENT_SESSION_LIMIT,
                        System.currentTimeMillis()
                );
                runOnUiThread(() -> render(snapshot));
            } catch (RuntimeException error) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    content.removeAllViews();
                    TextView message = bodyText();
                    message.setText(R.string.history_load_failed);
                    content.addView(message);
                });
            }
        });
    }

    private void render(@NonNull HistoryStatsSnapshot snapshot) {
        if (isDestroyed()) return;

        progress.setVisibility(View.GONE);
        content.removeAllViews();

        addSectionTitle(R.string.history_overview);

        LinearLayout overview = card();
        addMetric(
                overview,
                R.string.history_explored,
                String.format(
                        Locale.getDefault(),
                        "%,d · %s",
                        snapshot.visitedCells,
                        HistoryFormatter.area(snapshot.visitedCells, Locale.getDefault())
                )
        );
        addMetric(
                overview,
                R.string.history_discoveries,
                String.format(Locale.getDefault(), "%,d", snapshot.discoveries)
        );
        addMetric(
                overview,
                R.string.history_sessions,
                String.format(Locale.getDefault(), "%,d", snapshot.sessions)
        );
        addMetric(
                overview,
                R.string.history_distance,
                HistoryFormatter.distance(snapshot.distanceM, Locale.getDefault())
        );
        addMetric(
                overview,
                R.string.history_tracked_time,
                HistoryFormatter.duration(snapshot.trackedDurationMs)
        );
        addMetric(
                overview,
                R.string.history_accepted_fixes,
                String.format(Locale.getDefault(), "%,d", snapshot.acceptedPoints)
        );
        addMetric(
                overview,
                R.string.history_gps_points,
                String.format(Locale.getDefault(), "%,d", snapshot.gpsPoints)
        );
        content.addView(overview);

        addSectionTitle(R.string.history_recent_sessions);

        if (snapshot.recentSessions.isEmpty()) {
            TextView empty = bodyText();
            empty.setText(R.string.history_no_sessions);
            empty.setPadding(dp(4), dp(8), dp(4), dp(8));
            content.addView(empty);
            return;
        }

        long nowMs = System.currentTimeMillis();
        DateFormat dateFormat = DateFormat.getDateTimeInstance(
                DateFormat.MEDIUM,
                DateFormat.SHORT,
                Locale.getDefault()
        );

        for (SessionEntity session : snapshot.recentSessions) {
            content.addView(sessionCard(session, dateFormat, nowMs));
        }
    }

    @NonNull
    private LinearLayout sessionCard(
            @NonNull SessionEntity session,
            @NonNull DateFormat dateFormat,
            long nowMs
    ) {
        LinearLayout card = card();
        card.setOnClickListener(v -> showSessionDetails(session, dateFormat, nowMs));

        TextView date = new TextView(this);
        date.setText(dateFormat.format(new Date(session.startedAtMs)));
        date.setTextColor(Color.WHITE);
        date.setTextSize(16f);
        date.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(date);

        long endMs = session.endedAtMs == null ? nowMs : session.endedAtMs;
        long durationMs = Math.max(0L, endMs - session.startedAtMs);

        TextView summary = bodyText();
        summary.setPadding(0, dp(6), 0, 0);
        summary.setText(getString(
                R.string.history_session_summary,
                HistoryFormatter.distance(session.distanceM, Locale.getDefault()),
                HistoryFormatter.duration(durationMs),
                session.newCells
        ));
        card.addView(summary);

        TextView details = mutedText();
        details.setPadding(0, dp(4), 0, 0);
        details.setText(getString(
                session.endedAtMs == null
                        ? R.string.history_session_active
                        : R.string.history_session_complete,
                session.acceptedPoints
        ));
        card.addView(details);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(lp);
        return card;
    }

    private void showSessionDetails(
            @NonNull SessionEntity session,
            @NonNull DateFormat dateFormat,
            long nowMs
    ) {
        long endMs = session.endedAtMs == null ? nowMs : session.endedAtMs;
        long durationMs = Math.max(0L, endMs - session.startedAtMs);

        String ended = session.endedAtMs == null
                ? getString(R.string.history_in_progress)
                : dateFormat.format(new Date(session.endedAtMs));

        String message = getString(
                R.string.history_session_details,
                ended,
                HistoryFormatter.duration(durationMs),
                HistoryFormatter.distance(session.distanceM, Locale.getDefault()),
                session.newCells,
                session.acceptedPoints,
                session.source
        );

        androidx.appcompat.app.AlertDialog.Builder builder =
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(dateFormat.format(new Date(session.startedAtMs)))
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null);

        if (session.acceptedPoints > 0L) {
            builder.setNeutralButton(
                    R.string.history_export_gpx,
                    (dialog, which) -> requestGpxExport(session)
            );
        }

        builder.show();
    }

    private void requestGpxExport(@NonNull SessionEntity session) {
        if (session.acceptedPoints <= 0L) {
            android.widget.Toast.makeText(
                    this,
                    R.string.history_gpx_no_points,
                    android.widget.Toast.LENGTH_SHORT
            ).show();
            return;
        }

        long throughTimestampMs = System.currentTimeMillis();
        pendingGpxSession = session;
        pendingGpxThroughTimestampMs = throughTimestampMs;

        String stamp = new SimpleDateFormat(
                "yyyyMMdd-HHmm",
                Locale.US
        ).format(new Date(session.startedAtMs));

        exportGpxLauncher.launch("roamglyph-session-" + stamp + ".gpx");
    }

    private void writeSessionGpx(
            @NonNull Uri uri,
            @NonNull SessionEntity session,
            long throughTimestampMs
    ) {
        executor.execute(() -> {
            try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                if (output == null) {
                    throw new IllegalStateException("No GPX output stream");
                }

                String label = new SimpleDateFormat(
                        "yyyy-MM-dd HH:mm",
                        Locale.getDefault()
                ).format(new Date(session.startedAtMs));

                GpxExporter.ExportResult result = GpxExporter.exportSession(
                        output,
                        repository,
                        session,
                        throughTimestampMs,
                        getString(R.string.history_gpx_track_name, label)
                );

                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    android.widget.Toast.makeText(
                            this,
                            getString(
                                    R.string.history_gpx_export_success,
                                    result.points,
                                    result.segments
                            ),
                            android.widget.Toast.LENGTH_LONG
                    ).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    android.widget.Toast.makeText(
                            this,
                            R.string.history_gpx_export_failed,
                            android.widget.Toast.LENGTH_LONG
                    ).show();
                });
            }
        });
    }

    private void addSectionTitle(int textRes) {
        TextView title = new TextView(this);
        title.setText(textRes);
        title.setTextColor(Color.WHITE);
        title.setTextSize(18f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(dp(4), dp(8), dp(4), dp(10));
        content.addView(title);
    }

    @NonNull
    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF202327);
        background.setCornerRadius(dp(14));
        card.setBackground(background);
        card.setElevation(dp(2));
        return card;
    }

    private void addMetric(
            @NonNull LinearLayout card,
            int labelRes,
            @NonNull String value
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(5), 0, dp(5));

        TextView label = bodyText();
        label.setText(labelRes);
        row.addView(
                label,
                new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                )
        );

        TextView amount = new TextView(this);
        amount.setText(value);
        amount.setTextColor(Color.WHITE);
        amount.setTextSize(15f);
        amount.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        amount.setGravity(Gravity.END);
        row.addView(amount);

        card.addView(row);
    }

    @NonNull
    private TextView bodyText() {
        TextView view = new TextView(this);
        view.setTextColor(0xFFE0E3E7);
        view.setTextSize(15f);
        return view;
    }

    @NonNull
    private TextView mutedText() {
        TextView view = new TextView(this);
        view.setTextColor(0xFFAEB4BA);
        view.setTextSize(13f);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
