package com.rabeto.app.mesh;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Per-identity token bucket, plus a byte budget.
 *
 * Without this, one malicious or buggy peer can flood the mesh: every packet it
 * sends is verified (an ECDSA verify costs real CPU), cached, and re-broadcast
 * to every neighbour. That is an amplification attack against the whole network
 * and a battery attack against every phone in range.
 *
 * Pure Java on purpose so the mesh simulation test can drive it on the JVM.
 */
public final class RateLimiter {

    private final int capacity;
    private final long refillIntervalMs;
    private final long byteBudgetPerMinute;

    private final Map<String, Bucket> buckets = new HashMap<String, Bucket>();
    private final int maxTracked;

    public RateLimiter() {
        this(40, 500L, 512L * 1024L, 2048);
    }

    public RateLimiter(int capacity, long refillIntervalMs,
                       long byteBudgetPerMinute, int maxTracked) {
        this.capacity = capacity;
        this.refillIntervalMs = refillIntervalMs;
        this.byteBudgetPerMinute = byteBudgetPerMinute;
        this.maxTracked = maxTracked;
    }

    /**
     * @return true when the packet may be processed.
     */
    public synchronized boolean allow(String peerId, int packetBytes, long now) {
        if (peerId == null || peerId.length() == 0) return false;

        Bucket b = buckets.get(peerId);
        if (b == null) {
            if (buckets.size() >= maxTracked) evictOldest(now);
            b = new Bucket(capacity, now);
            buckets.put(peerId, b);
        }

        long elapsed = now - b.lastRefill;
        if (elapsed >= refillIntervalMs) {
            long gained = elapsed / refillIntervalMs;
            b.tokens = (int) Math.min(capacity, b.tokens + gained);
            b.lastRefill = now;
        }

        if (now - b.windowStart >= 60_000L) {
            b.windowStart = now;
            b.bytesInWindow = 0L;
        }

        if (b.tokens <= 0) return false;
        if (b.bytesInWindow + packetBytes > byteBudgetPerMinute) return false;

        b.tokens--;
        b.bytesInWindow += packetBytes;
        b.lastSeen = now;
        return true;
    }

    public synchronized void forget(String peerId) {
        buckets.remove(peerId);
    }

    public synchronized int tracked() {
        return buckets.size();
    }

    private void evictOldest(long now) {
        String oldestKey = null;
        long oldest = Long.MAX_VALUE;
        Iterator<Map.Entry<String, Bucket>> it = buckets.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Bucket> e = it.next();
            if (e.getValue().lastSeen < oldest) {
                oldest = e.getValue().lastSeen;
                oldestKey = e.getKey();
            }
        }
        if (oldestKey != null) buckets.remove(oldestKey);
    }

    private static final class Bucket {
        int tokens;
        long lastRefill;
        long lastSeen;
        long windowStart;
        long bytesInWindow;

        Bucket(int tokens, long now) {
            this.tokens = tokens;
            this.lastRefill = now;
            this.lastSeen = now;
            this.windowStart = now;
            this.bytesInWindow = 0L;
        }
    }
}
