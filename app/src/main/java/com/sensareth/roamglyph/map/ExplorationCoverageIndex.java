package com.sensareth.roamglyph.map;

import androidx.annotation.NonNull;

import com.uber.h3core.H3Core;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Overlay-thread-owned cache of explored H3 cells at the resolutions used by the
 * adaptive fog renderer. Exact res-13 data remains authoritative.
 */
public final class ExplorationCoverageIndex {
    private static final int SOURCE_RESOLUTION = 13;
    private static final int MAX_CACHED_RESOLUTIONS = 4;

    private final Set<String> exactCells = new HashSet<>();
    private final LinkedHashMap<Integer, Set<String>> parentCache =
            new LinkedHashMap<>(8, 0.75f, true);

    public void replaceAll(@NonNull Collection<String> cells) {
        exactCells.clear();
        exactCells.addAll(cells);
        parentCache.clear();
    }

    public void addAll(
            @NonNull H3Core h3,
            @NonNull Collection<String> cells
    ) {
        if (cells.isEmpty()) return;

        Set<String> added = new HashSet<>();
        for (String cell : cells) {
            if (exactCells.add(cell)) {
                added.add(cell);
            }
        }
        if (added.isEmpty()) return;

        for (Map.Entry<Integer, Set<String>> entry : parentCache.entrySet()) {
            int resolution = entry.getKey();
            Set<String> parents = entry.getValue();

            for (String cell : added) {
                try {
                    parents.add(h3.cellToParentAddress(cell, resolution));
                } catch (RuntimeException ignored) {
                    // Corrupt legacy/import data must not break the map.
                }
            }
        }
    }

    @NonNull
    public Set<String> cellsAtResolution(
            @NonNull H3Core h3,
            int resolution
    ) {
        if (resolution == SOURCE_RESOLUTION) {
            return exactCells;
        }

        Set<String> cached = parentCache.get(resolution);
        if (cached != null) return cached;

        Set<String> parents = new HashSet<>();
        for (String cell : exactCells) {
            try {
                parents.add(h3.cellToParentAddress(cell, resolution));
            } catch (RuntimeException ignored) {
                // Corrupt legacy/import data must not break the map.
            }
        }

        parentCache.put(resolution, parents);
        trimCache();
        return parents;
    }

    public int exactCellCount() {
        return exactCells.size();
    }

    private void trimCache() {
        while (parentCache.size() > MAX_CACHED_RESOLUTIONS) {
            Iterator<Map.Entry<Integer, Set<String>>> iterator =
                    parentCache.entrySet().iterator();
            if (!iterator.hasNext()) return;
            iterator.next();
            iterator.remove();
        }
    }
}
