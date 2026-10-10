package com.sensareth.roamglyph.map;

import android.graphics.Paint;
import android.graphics.Path;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.sensareth.roamglyph.data.GpsPointEntity;

import java.util.List;

/**
 * Creates a round, geographic corridor from accepted local GPS samples.
 *
 * Each accepted fix creates an 11m-radius round footprint. Sequential
 * points from the same session form round-ended joins only when they are
 * close in time and space. No H3 cell edges are used for this silhouette.
 *
 * The fog renderer ALWAYS masks this path against exact stored H3 cells:
 * route interpolation cannot reveal additional unvisited geography.
 */
public final class FogGpsCorridorBuilder {
    private static final double ROUTE_RADIUS_M = 11.0;

    private FogGpsCorridorBuilder() {}

    /**
     * @return smooth filled path in tile-local world pixel coordinates, or
     *         null if too few / invalid points exist for a meaningful track
     */
    @Nullable
    public static Path build(@NonNull List<GpsPointEntity> points,
                             @NonNull FogWorldTileScheme.Key key,
                             float diameterMetersPerPixel) {
        if (points.isEmpty() || !Float.isFinite(diameterMetersPerPixel)
                || diameterMetersPerPixel <= 0) return null;

        float radius = (float) (ROUTE_RADIUS_M / diameterMetersPerPixel);
        Path path = new Path();
        Path centerline = new Path();
        GpsPointEntity previous = null;
        int included = 0;

        for (GpsPointEntity point : points) {
            if (!point.acceptedForExploration ||
                    !Double.isFinite(point.latitude) ||
                    !Double.isFinite(point.longitude)) continue;
            float x = FogWorldTileScheme.localX(point.longitude, key);
            float y = FogWorldTileScheme.localY(point.latitude, key);
            if (!Float.isFinite(x) || !Float.isFinite(y)) continue;
            if (x < -512 || x > FogWorldTileScheme.TILE_PX + 512
                    || y < -512 || y > FogWorldTileScheme.TILE_PX + 512) {
                previous = null;
                continue;
            }

            path.addCircle(x, y, radius, Path.Direction.CW);
            boolean joined = previous != null &&
                    GpsCorridorJoinPolicy.shouldConnect(
                            previous.sessionId, previous.timestampMs,
                            previous.latitude, previous.longitude,
                            point.sessionId, point.timestampMs,
                            point.latitude, point.longitude);
            if (joined) {
                centerline.moveTo(
                        FogWorldTileScheme.localX(previous.longitude, key),
                        FogWorldTileScheme.localY(previous.latitude, key));
                centerline.lineTo(x, y);
            }
            previous = point;
            included++;
        }

        if (included == 0) return null;
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(radius * 2f);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        Path strokeFill = new Path();
        stroke.getFillPath(centerline, strokeFill);
        path.addPath(strokeFill);
        return path;
    }
}
