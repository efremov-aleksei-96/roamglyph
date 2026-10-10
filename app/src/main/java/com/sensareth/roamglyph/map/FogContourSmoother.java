package com.sensareth.roamglyph.map;

import java.util.ArrayList;
import java.util.List;

/**
 * Visually straighten noisy H3 borders. Simplification is display-only:
 * the caller must clip the resulting curve against exact res-13 coverage.
 */
public final class FogContourSmoother {
    private FogContourSmoother() {}

    public static final class Vertex {
        public final float x;
        public final float y;

        public Vertex(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    /**
     * Closed-ring Ramer-Douglas-Peucker using two anchored open chains.
     * The initial anchor and the farthest other vertex stay fixed. Keeping
     * both chains avoids the usual seam artifact when simplifying a loop.
     */
    public static List<Vertex> simplifyClosed(List<Vertex> ring, float tolerancePx) {
        int n = ring.size();
        if (n < 4 || tolerancePx <= 0f) return ring;

        int farthest = 1;
        double maxDistance = -1;
        Vertex origin = ring.get(0);
        for (int i = 1; i < n; i++) {
            Vertex p = ring.get(i);
            double d = squared(origin.x - p.x) + squared(origin.y - p.y);
            if (d > maxDistance) {
                maxDistance = d;
                farthest = i;
            }
        }
        if (farthest == n - 1 || farthest == 1) {
            // Both chains still have legal endpoints; avoid degeneracy.
            farthest = Math.max(2, n / 2);
        }

        List<Vertex> first = simplifyChain(ring.subList(0, farthest + 1), tolerancePx);
        List<Vertex> second = new ArrayList<>(n - farthest + 1);
        second.addAll(ring.subList(farthest, n));
        second.add(ring.get(0));
        second = simplifyChain(second, tolerancePx);

        List<Vertex> result = new ArrayList<>(first.size() + second.size() - 2);
        result.addAll(first);
        for (int i = 1; i < second.size() - 1; i++) result.add(second.get(i));
        // A highly simplified isolated cell must never collapse to a line.
        return result.size() >= 3 ? result : ring;
    }

    private static List<Vertex> simplifyChain(List<Vertex> chain, float tolerance) {
        int n = chain.size();
        if (n < 3) return chain;
        boolean[] keep = new boolean[n];
        keep[0] = true;
        keep[n - 1] = true;
        int[] stack = new int[n * 2];
        int count = 0;
        stack[count++] = 0;
        stack[count++] = n - 1;
        double threshold = (double) tolerance * tolerance;
        while (count > 0) {
            int end = stack[--count];
            int start = stack[--count];
            Vertex a = chain.get(start);
            Vertex b = chain.get(end);
            double biggest = threshold;
            int selected = -1;
            for (int i = start + 1; i < end; i++) {
                Vertex p = chain.get(i);
                double dist = distanceToSegmentSquared(p, a, b);
                if (dist > biggest) {
                    biggest = dist;
                    selected = i;
                }
            }
            if (selected >= 0) {
                keep[selected] = true;
                stack[count++] = start;
                stack[count++] = selected;
                stack[count++] = selected;
                stack[count++] = end;
            }
        }
        List<Vertex> result = new ArrayList<>();
        for (int i = 0; i < n; i++) if (keep[i]) result.add(chain.get(i));
        return result;
    }

    private static double squared(double x) {
        return x * x;
    }

    private static double distanceToSegmentSquared(Vertex p, Vertex a, Vertex b) {
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double length = squared(dx) + squared(dy);
        if (length < 1e-12) return squared(p.x - a.x) + squared(p.y - a.y);
        double t = Math.max(0, Math.min(1,
                ((p.x - a.x) * dx + (p.y - a.y) * dy) / length));
        double x = a.x + t * dx;
        double y = a.y + t * dy;
        return squared(p.x - x) + squared(p.y - y);
    }
}
