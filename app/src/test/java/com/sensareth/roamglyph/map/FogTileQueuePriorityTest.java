package com.sensareth.roamglyph.map;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Pure JVM tests for camera-first work selection. */
public final class FogTileQueuePriorityTest {
    private static FogWorldTileScheme.Key key(int x) {
        return new FogWorldTileScheme.Key(17, x, 10);
    }

    @Test public void newVisibleTilesOvertakeOldPrefetch() {
        Queue<FogWorldTileScheme.Key> jobs = new ConcurrentLinkedQueue<>();
        jobs.add(key(1)); jobs.add(key(2)); jobs.add(key(3));
        assertEquals(key(3), FogTileQueuePriority.pollVisibleFirst(
                jobs, new HashSet<>(Arrays.asList(key(3))), x -> x));
        assertEquals(key(1), FogTileQueuePriority.pollVisibleFirst(
                jobs, Collections.emptySet(), x -> x));
        assertEquals(key(2), FogTileQueuePriority.pollVisibleFirst(
                jobs, Collections.emptySet(), x -> x));
        assertNull(FogTileQueuePriority.pollVisibleFirst(
                jobs, Collections.emptySet(), x -> x));
    }

    @Test public void preservesFifoWithinVisibleGroup() {
        Queue<FogWorldTileScheme.Key> jobs = new ConcurrentLinkedQueue<>();
        jobs.add(key(1)); jobs.add(key(2)); jobs.add(key(3)); jobs.add(key(4));
        HashSet<FogWorldTileScheme.Key> visible =
                new HashSet<>(Arrays.asList(key(2), key(4)));
        assertEquals(key(2), FogTileQueuePriority.pollVisibleFirst(jobs, visible, x -> x));
        assertEquals(key(4), FogTileQueuePriority.pollVisibleFirst(jobs, visible, x -> x));
        assertEquals(key(1), FogTileQueuePriority.pollVisibleFirst(jobs, visible, x -> x));
        assertEquals(key(3), FogTileQueuePriority.pollVisibleFirst(jobs, visible, x -> x));
    }

    @Test public void prunesStaleWorkButKeepsVisibleAndPrefetchJobs() {
        Queue<FogWorldTileScheme.Key> jobs = new ConcurrentLinkedQueue<>();
        jobs.add(key(1)); jobs.add(key(2)); jobs.add(key(3)); jobs.add(key(4));
        ArrayList<FogWorldTileScheme.Key> removed = new ArrayList<>();
        int count = FogTileQueuePriority.pruneObsolete(
                jobs, new HashSet<>(Arrays.asList(key(2), key(4))),
                x -> x, removed::add);
        assertEquals(2, count);
        assertEquals(Arrays.asList(key(1), key(3)), removed);
        assertEquals(key(2), jobs.poll());
        assertEquals(key(4), jobs.poll());
        assertNull(jobs.poll());
    }

    @Test public void pruningAllStaleJobsDoesNotPreventLaterVisibleWork() {
        Queue<FogWorldTileScheme.Key> jobs = new ConcurrentLinkedQueue<>();
        jobs.add(key(1)); jobs.add(key(3));
        HashSet<FogWorldTileScheme.Key> pending =
                new HashSet<>(Arrays.asList(key(1), key(3)));
        int removed = FogTileQueuePriority.pruneObsolete(
                jobs, Collections.singleton(key(7)), x -> x, pending::remove);
        assertEquals(2, removed);
        assertTrue(pending.isEmpty());
        assertNull(jobs.poll());
        assertTrue(pending.add(key(7)));
        jobs.add(key(7));
        assertEquals(key(7), FogTileQueuePriority.pollVisibleFirst(
                jobs, Collections.singleton(key(7)), x -> x));
    }

    @Test public void haloPrefetchWaitsForVisibleTilesButNeverBlocksVisible() {
        assertTrue(FogTileQueuePriority.deferPrefetch(true, true, false));
        assertTrue(!FogTileQueuePriority.deferPrefetch(true, true, true));
        assertTrue(!FogTileQueuePriority.deferPrefetch(true, false, false));
        assertTrue(!FogTileQueuePriority.deferPrefetch(false, true, false));
    }

    @Test public void emptyQueueReturnsNull() {
        Queue<FogWorldTileScheme.Key> jobs = new ConcurrentLinkedQueue<>();
        assertNull(FogTileQueuePriority.pollVisibleFirst(
                jobs, Collections.singleton(key(1)), x -> x));
    }
}
