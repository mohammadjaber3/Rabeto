package com.rabeto.app.mesh;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RateLimiterTest {

    @Test
    public void allowsABurstThenThrottles() {
        RateLimiter r = new RateLimiter(5, 1000L, 1_000_000L, 100);
        long t = 1_000_000L;
        for (int i = 0; i < 5; i++) assertTrue(r.allow("peer", 100, t));
        assertFalse(r.allow("peer", 100, t));
    }

    @Test
    public void refillsOverTime() {
        RateLimiter r = new RateLimiter(2, 1000L, 1_000_000L, 100);
        long t = 1_000_000L;
        assertTrue(r.allow("peer", 10, t));
        assertTrue(r.allow("peer", 10, t));
        assertFalse(r.allow("peer", 10, t));
        assertTrue(r.allow("peer", 10, t + 1000L));
    }

    @Test
    public void enforcesByteBudget() {
        RateLimiter r = new RateLimiter(1000, 1L, 1000L, 100);
        long t = 1_000_000L;
        assertTrue(r.allow("peer", 600, t));
        assertFalse(r.allow("peer", 600, t));   // over 1000 bytes/min
        assertTrue(r.allow("peer", 600, t + 61_000L)); // new window
    }

    @Test
    public void oneFloodingPeerDoesNotStarveOthers() {
        RateLimiter r = new RateLimiter(3, 10_000L, 1_000_000L, 100);
        long t = 1_000_000L;
        for (int i = 0; i < 3; i++) assertTrue(r.allow("flooder", 10, t));
        assertFalse(r.allow("flooder", 10, t));
        // A well-behaved peer is completely unaffected.
        assertTrue(r.allow("honest", 10, t));
    }

    @Test
    public void staysBoundedUnderIdentitySpam() {
        RateLimiter r = new RateLimiter(5, 1000L, 1_000_000L, 50);
        long t = 1_000_000L;
        for (int i = 0; i < 5000; i++) r.allow("peer-" + i, 10, t + i);
        assertTrue(r.tracked() <= 50);
    }

    @Test
    public void rejectsEmptyPeer() {
        RateLimiter r = new RateLimiter();
        assertFalse(r.allow(null, 10, 1L));
        assertFalse(r.allow("", 10, 1L));
        assertEquals(0, r.tracked());
    }
}
