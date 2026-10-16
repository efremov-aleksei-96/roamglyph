package com.sensareth.roamglyph.map;

import android.graphics.Path;
import com.sensareth.roamglyph.data.GpsPointEntity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable accepted-GPS snapshot. Never invent bridges across sessions or gaps. */
public final class FogGpsCorridors {
    private static final long MAX_GAP_MS = 30_000;
    private static final double MAX_SEGMENT_METERS = 85.0;
    private static final double EARTH_RADIUS = 6_371_000.0;
    private final List<Fix> fixes;

    private static final class Fix {
        final String session; final long time;
        final double lat, lng;
        Fix(GpsPointEntity point) {
            session = point.sessionId; time = point.timestampMs;
            lat = point.latitude; lng = point.longitude;
        }
    }

    private FogGpsCorridors(List<Fix> list) {
        fixes = Collections.unmodifiableList(list);
    }

    public static FogGpsCorridors empty() { return new FogGpsCorridors(Collections.emptyList()); }

    public static FogGpsCorridors from(List<GpsPointEntity> points) {
        ArrayList<Fix> accepted = new ArrayList<>();
        for (GpsPointEntity p : points) {
            if (p.acceptedForExploration && Double.isFinite(p.latitude)
                    && Double.isFinite(p.longitude) && Math.abs(p.latitude) <= 85
                    && Math.abs(p.longitude) <= 180) accepted.add(new Fix(p));
        }
        accepted.sort((a,b) -> {
            int c = a.session.compareTo(b.session);
            return c != 0 ? c : Long.compare(a.time,b.time);
        });
        return new FogGpsCorridors(accepted);
    }

    public boolean isEmpty() { return fixes.isEmpty(); }

    public Path tilePath(FogWorldTileScheme.Key key) {
        Path path = new Path();
        Fix previous = null;
        final double border = 32.0;
        for (Fix current : fixes) {
            double x = FogWorldTileScheme.localX(current.lng,key);
            double y = FogWorldTileScheme.localY(current.lat,key);
            boolean near = x >= -border && x <= FogWorldTileScheme.TILE_PX+border
                    && y >= -border && y <= FogWorldTileScheme.TILE_PX+border;
            if (near) {
                path.addCircle((float)x,(float)y,0.001f,Path.Direction.CW);
            }
            if (previous != null && previous.session.equals(current.session)
                    && current.time >= previous.time
                    && current.time - previous.time <= MAX_GAP_MS
                    && distance(previous,current) <= MAX_SEGMENT_METERS) {
                float px = FogWorldTileScheme.localX(previous.lng,key);
                float py = FogWorldTileScheme.localY(previous.lat,key);
                // Include segments that cross a tile even when endpoints are outside.
                if (Math.max(px,(float)x) >= -border && Math.min(px,(float)x)
                        <= FogWorldTileScheme.TILE_PX+border
                        && Math.max(py,(float)y) >= -border && Math.min(py,(float)y)
                        <= FogWorldTileScheme.TILE_PX+border) {
                    path.moveTo(px,py);
                    path.lineTo((float)x,(float)y);
                }
            }
            previous=current;
        }
        return path;
    }

    private static double distance(Fix a,Fix b) {
        double dLat=Math.toRadians(b.lat-a.lat),dLon=Math.toRadians(b.lng-a.lng);
        double hav=Math.pow(Math.sin(dLat/2),2)+Math.cos(Math.toRadians(a.lat))
                *Math.cos(Math.toRadians(b.lat))*Math.pow(Math.sin(dLon/2),2);
        return 2*EARTH_RADIUS*Math.asin(Math.min(1,Math.sqrt(hav)));
    }
}
