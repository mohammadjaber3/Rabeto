package com.rabeto.app.mesh;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded deduplication window for message ids, with real LRU eviction.
 *
 * The previous implementation used LinkedHashSet and evicted with
 * iterator().next(), which is insertion order, not access order. That is
 * usually fine for dedup, but it also silently forgot ids that were still
 * actively circulating in the mesh, causing the same packet to be re-flooded.
 * LinkedHashMap in access-order mode fixes that in eight lines.
 */
public final class SeenCache {

    private final Map<String, Boolean> map;

    public SeenCache(final int maxEntries) {
        this.map = new LinkedHashMap<String, Boolean>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > maxEntries;
            }
        };
    }

    public synchronized boolean seen(String id) {
        return id != null && map.containsKey(id);
    }

    /** @return true if this id is new. */
    public synchronized boolean remember(String id) {
        if (id == null || id.length() == 0) return false;
        return map.put(id, Boolean.TRUE) == null;
    }

    public synchronized int size() {
        return map.size();
    }

    public synchronized void clear() {
        map.clear();
    }
}
