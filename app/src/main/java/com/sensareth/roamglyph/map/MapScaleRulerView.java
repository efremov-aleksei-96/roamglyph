package com.sensareth.roamglyph.map;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

/**
 * Compact, non-interactive scale bar drawn above the map/fog.
 * Uses a screen-projection sample near its own latitude, not a fixed
 * meters-per-pixel guess, so metric labels remain meaningful while panning.
 */
public final class MapScaleRulerView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private String distanceLabel = "—";
    private float segmentPx;
    private boolean ready;

    public MapScaleRulerView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setVisibility(INVISIBLE);
    }

    /**
     * @param referenceMeters ground distance between projection sample points
     * @param referencePixels physical screen-space pixel gap of those points
     */
    public void update(double referenceMeters, float referencePixels) {
        double distance = MapScaleMath.niceDistanceMeters(referenceMeters);
        if (!Double.isFinite(distance) || referencePixels <= 0f
                || !Float.isFinite(referencePixels)) return;
        float candidate = (float) (referencePixels * distance / referenceMeters);
        if (!Float.isFinite(candidate) || candidate < 1f) return;
        float maxWidth = Math.max(1f, getWidth() - 24f * density);
        segmentPx = Math.min(maxWidth, candidate);
        String label = MapScaleMath.label(distance);
        if (ready && distanceLabel.equals(label)
                && Math.abs(segmentPx - lastWidthPx) < 0.75f) return;
        lastWidthPx = segmentPx;
        distanceLabel = label;
        ready = true;
        setVisibility(VISIBLE);
        setContentDescription("Map scale " + label);
        invalidate();
    }

    private float lastWidthPx;

    /** Exact local y coordinate of the painted horizontal scale bar. */
    public float distanceBarCenterY() {
        return Math.min(getHeight() - 11f * density, 37f * density);
    }

    /** Exact local x coordinate of the painted scale bar's left endpoint. */
    public float distanceBarLeftX() {
        return 12f * density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!ready) return;
        float d = density;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xCC202124);
        canvas.drawRoundRect(0f, 0f, getWidth(), getHeight(),
                9f * d, 9f * d, paint);

        paint.setColor(Color.WHITE);
        paint.setTextSize(13f * d);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        canvas.drawText(distanceLabel, 12f * d, 19f * d, paint);

        paint.setStrokeWidth(2f * d);
        paint.setStrokeCap(Paint.Cap.SQUARE);
        float x = distanceBarLeftX();
        float y = distanceBarCenterY();
        canvas.drawLine(x, y, x + segmentPx, y, paint);
        canvas.drawLine(x, y - 5f * d, x, y + 1f * d, paint);
        canvas.drawLine(x + segmentPx, y - 5f * d,
                x + segmentPx, y + 1f * d, paint);
    }
}
