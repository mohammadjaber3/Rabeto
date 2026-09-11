package com.rabeto.app.mesh;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure JVM. No Android, no Robolectric, runs in milliseconds. */
public class SeenCacheTest {

    @Test
    public void remembersFirstSightOnly() {
        SeenCache c = new SeenCache(10);
        assertTrue(c.remember("a"));
        assertFalse(c.remember("a"));
        assertTrue(c.seen("a"));
        assertFalse(c.seen("b"));
    }

    @Test
    public void ignoresEmptyIds() {
        SeenCache c = new SeenCache(10);
        assertFalse(c.remember(null));
        assertFalse(c.remember(""));
        assertEquals(0, c.size());
    }

    @Test
    public void staysBounded() {
        SeenCache c = new SeenCache(100);
        for (int i = 0; i < 5000; i++) c.remember("m-" + i);
        assertEquals(100, c.size());
    }

    @Test
    public void evictionIsAccessOrderNotInsertionOrder() {
        // This is the bug that was in the old LinkedHashSet implementation:
        // an id still actively circulating got evicted just because it was old,
        // which let the same packet be re-flooded.
        SeenCache c = new SeenCache(3);
        c.remember("a");
        c.remember("b");
        c.remember("c");
        c.seen("a");          // touch a
        c.remember("d");      // evicts the least recently used, which is b
        assertTrue(c.seen("a"));
        assertFalse(c.seen("b"));
        assertTrue(c.seen("c"));
        assertTrue(c.seen("d"));
    }
}
