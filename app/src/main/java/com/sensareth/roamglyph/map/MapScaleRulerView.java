package com.sensareth.roamglyph.map;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

/**
 * Compact vertical map scale shown at the lower left, like a native map ruler.
 * Uses a screen-projection sample near its own latitude, not a fixed
 * meters-per-pixel guess, so metric labels remain meaningful while panning.
 */
public final class MapScaleRulerView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private String distanceLabel = "—";
    private float segmentPx;
    private boolean ready;
    private boolean layoutAllowed = true;

    public MapScaleRulerView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setVisibility(INVISIBLE);
    }

    /**
     * Hide the ruler entirely when a narrow/split-screen window has no
     * unobstructed space between the status card and bottom buttons.
     */
    public void setLayoutAllowed(boolean allowed) {
        if (layoutAllowed == allowed) return;
        layoutAllowed = allowed;
        setVisibility(!layoutAllowed ? GONE : ready ? VISIBLE : INVISIBLE);
    }

    public boolean isLayoutAllowed() { return layoutAllowed; }

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
        float maxHeight = Math.max(1f, getHeight() - 24f * density);
        segmentPx = Math.min(maxHeight, candidate);
        String label = MapScaleMath.label(distance);
        if (ready && distanceLabel.equals(label)
                && Math.abs(segmentPx - lastWidthPx) < 0.75f) return;
        lastWidthPx = segmentPx;
        distanceLabel = label;
        ready = true;
        if (layoutAllowed) setVisibility(VISIBLE);
        setContentDescription("Map scale " + label);
        invalidate();
    }

    private float lastWidthPx;

    /** The exact local X at which the vertical bar is painted and projected. */
    public float distanceBarX() {
        return getWidth() - 11f * density;
    }

    /** The exact local Y at which the vertical bar starts. */
    public float distanceBarTopY() {
        return 12f * density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!ready || !layoutAllowed) return;
        float d = density;
        float x = distanceBarX();
        float start = distanceBarTopY();
        float end = start + segmentPx;
        float tick = 5f * d;

        // Subtle bright halo for readability above any map background, with
        // a thin dark core; no opaque rectangular panel blocking the map.
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setStrokeCap(Paint.Cap.SQUARE);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4f * d);
        paint.setColor(0xEFFFFFFF);
        canvas.drawLine(x, start, x, end, paint);
        canvas.drawLine(x - tick, start, x + 1f * d, start, paint);
        canvas.drawLine(x - tick, end, x + 1f * d, end, paint);

        paint.setStrokeWidth(1.7f * d);
        paint.setColor(0xE5202124);
        canvas.drawLine(x, start, x, end, paint);
        canvas.drawLine(x - tick, start, x + 1f * d, start, paint);
        canvas.drawLine(x - tick, end, x + 1f * d, end, paint);

        float labelY = Math.max(16f * d, Math.min(getHeight() - 6f * d,
                (start + end) * 0.5f + 4f * d));
        paint.setTextSize(13f * d);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3.5f * d);
        paint.setColor(Color.WHITE);
        canvas.drawText(distanceLabel, 3f * d, labelY, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF202124);
        canvas.drawText(distanceLabel, 3f * d, labelY, paint);
    }
}
