package com.sensareth.roamglyph.map;

import java.util.Queue;
import java.util.Set;
import java.util.function.Function;

/**
 * Give currently visible geographic tiles precedence over older prefetch work.
 * Called by a single consumer; producers may concurrently append and clear.
 * A concurrent removal is allowed to fail, in which case scanning continues.
 */
final class FogTileQueuePriority {
    private FogTileQueuePriority() {}

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
