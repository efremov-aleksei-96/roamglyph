package com.sensareth.roamglyph.map;

import java.util.Queue;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Consumer;

/**
 * Give currently visible geographic tiles precedence over older prefetch work.
 * Called by a single consumer; producers may concurrently append and clear.
 * A concurrent removal is allowed to fail, in which case scanning continues.
 */
final class FogTileQueuePriority {
    private FogTileQueuePriority() {}

    /**
     * Prune obsolete queued work as soon as the camera changes, not only
     * after the worker dequeues it. This is called by the UI demand producer.
     *
     * queue.remove(job) must succeed before releasing the key from the
     * producer-owned pending set. If the worker already took the job, only
     * its normal completion can release pending and there is no duplicate
     * build for the same key.
     *
     * The currently wanted jobs, including prefetch, must remain queued.
     */
    static <T> int pruneObsolete(Queue<T> queue,
                                 Set<FogWorldTileScheme.Key> wanted,
                                 Function<T, FogWorldTileScheme.Key> keyOf,
                                 Consumer<T> removed) {
        int count = 0;
        for (T job : queue) {
            if (!wanted.contains(keyOf.apply(job)) && queue.remove(job)) {
                removed.accept(job);
                count++;
            }
        }
        return count;
    }

    /** Only defer halo requests while a screen-visible tile needs a build. */
    static boolean deferPrefetch(boolean enabled, boolean visibleMissing,
                                 boolean isVisible) {
        return enabled && visibleMissing && !isVisible;
    }

    static <T> T pollVisibleFirst(Queue<T> queue,
                                  Set<FogWorldTileScheme.Key> visible,
                                  Function<T, FogWorldTileScheme.Key> keyOf) {
        for (T job : queue) {
            if (visible.contains(keyOf.apply(job)) && queue.remove(job)) {
                return job;
            }
        }
        return queue.poll();
    }
}
