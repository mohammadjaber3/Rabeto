package com.rabeto.app.mesh;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Network simulation without any phones.
 *
 * The user asked how Rabeto behaves at scale. You cannot answer that with two
 * devices on a desk, and you cannot answer it by reading the code. This harness
 * models the exact forwarding rules the core implements (TTL decrement, hop
 * count, dedup on first sight, per-peer rate limiting) over synthetic
 * topologies, so a routing regression fails on a laptop in milliseconds.
 *
 * It deliberately models the RULES, not the Android classes. When Phase B
 * replaces flooding with a real router, this test is the yardstick that proves
 * the new router is better rather than just different.
 */
public class MeshFloodSimulationTest {

    private static final int MAX_TTL = 8;
    private static final int MAX_HOPS = 16;

    private static final class Node {
        final int index;
        final SeenCache seen = new SeenCache(4000);
        final RateLimiter limiter = new RateLimiter(200, 1L, 100_000_000L, 4096);
        final List<Integer> delivered = new ArrayList<Integer>();

        Node(int index) {
            this.index = index;
        }
    }

    private static final class Packet {
        final int id;
        final int ttl;
        final int hops;

        Packet(int id, int ttl, int hops) {
            this.id = id;
            this.ttl = ttl;
            this.hops = hops;
        }
    }

    private Node[] nodes;
    private boolean[][] adjacency;
    private int transmissions;
    private int maxHopsObserved;

    private void build(int n, boolean[][] adj) {
        nodes = new Node[n];
        for (int i = 0; i < n; i++) nodes[i] = new Node(i);
        adjacency = adj;
        transmissions = 0;
        maxHopsObserved = 0;
    }

    /** Mirrors RabetoCore.handleIncoming + forward(). */
    private void deliver(int to, int from, Packet p) {
        Node node = nodes[to];

        if (!node.limiter.allow("sender-" + from, 512, 1L)) return;
        if (p.ttl + p.hops > MAX_TTL) return;      // anti TTL-refresh
        if (!node.seen.remember("m-" + p.id)) return;

        node.delivered.add(Integer.valueOf(p.id));
        if (p.hops > maxHopsObserved) maxHopsObserved = p.hops;

        if (p.ttl <= 0 || p.hops >= MAX_HOPS) return;

        for (int peer = 0; peer < nodes.length; peer++) {
            if (peer == to || peer == from) continue;
            if (!adjacency[to][peer]) continue;
            transmissions++;
            deliver(peer, to, new Packet(p.id, p.ttl - 1, p.hops + 1));
        }
    }

    private static boolean[][] ring(int n) {
        boolean[][] a = new boolean[n][n];
        for (int i = 0; i < n; i++) {
            int next = (i + 1) % n;
            a[i][next] = true;
            a[next][i] = true;
        }
        return a;
    }

    private static boolean[][] chain(int n) {
        boolean[][] a = new boolean[n][n];
        for (int i = 0; i + 1 < n; i++) {
            a[i][i + 1] = true;
            a[i + 1][i] = true;
        }
        return a;
    }

    private static boolean[][] dense(int n, long seed, double p) {
        Random r = new Random(seed);
        boolean[][] a = new boolean[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                boolean link = r.nextDouble() < p;
                a[i][j] = link;
                a[j][i] = link;
            }
        }
        // Guarantee connectivity so the test measures flooding, not luck.
        for (int i = 0; i + 1 < n; i++) {
            a[i][i + 1] = true;
            a[i + 1][i] = true;
        }
        return a;
    }

    private int deliveredCount() {
        int c = 0;
        for (int i = 0; i < nodes.length; i++) if (!nodes[i].delivered.isEmpty()) c++;
        return c;
    }

    @Test
    public void reachesEveryNodeInsideTtlBudget() {
        build(12, ring(12));
        deliver(0, -1, new Packet(1, MAX_TTL, 0));
        // Ring of 12: the furthest node is 6 hops away, well inside TTL 8.
        assertEquals(12, deliveredCount());
        assertTrue("hops must never exceed the TTL budget",
                maxHopsObserved <= MAX_TTL);
    }

    @Test
    public void everyNodeAcceptsAPacketExactlyOnce() {
        build(20, dense(20, 42L, 0.25));
        deliver(0, -1, new Packet(7, MAX_TTL, 0));
        for (int i = 0; i < nodes.length; i++) {
            assertTrue("node " + i + " accepted a duplicate",
                    nodes[i].delivered.size() <= 1);
        }
    }

    @Test
    public void floodingTerminatesAndDoesNotLoopForever() {
        build(30, dense(30, 7L, 0.35));
        deliver(0, -1, new Packet(3, MAX_TTL, 0));
        // Without dedup this is unbounded. With dedup it is bounded by edges.
        assertTrue("flooding blew up: " + transmissions + " transmissions",
                transmissions < 30 * 30 * 4);
    }

    @Test
    public void ttlLimitsReachOnALongChain() {
        // This is the honest scaling limit of flooding with TTL 8: a 40-device
        // chain cannot be crossed. Store-and-forward, not a bigger TTL, is the
        // answer, and Phase B routing is what makes that efficient.
        build(40, chain(40));
        deliver(0, -1, new Packet(9, MAX_TTL, 0));
        assertEquals(MAX_TTL + 1, deliveredCount());
        assertTrue(deliveredCount() < 40);
    }

    @Test
    public void rateLimitContainsAFlooder() {
        build(6, dense(6, 1L, 0.8));

        // Node 0 dumps 5000 distinct, individually valid messages at node 1.
        // ttl+hops stays inside the budget, so nothing is rejected by the TTL
        // rule: the limiter is the only thing standing between one bad actor and
        // 5000 signature verifications on every phone in range.
        for (int i = 0; i < 5000; i++) {
            deliver(1, 0, new Packet(i, MAX_TTL - 1, 1));
        }

        int accepted = nodes[1].delivered.size();
        assertTrue("limiter did not contain the flood: " + accepted, accepted < 5000);
        assertTrue("limiter rejected everything, so the test proves nothing",
                accepted > 0);
        // Bucket capacity is 200 in this harness and time never advances, so the
        // node performs at most 200 signature verifications instead of 5000.
        // It is fewer than 200 in practice, because duplicates that loop back
        // through the mesh also spend tokens: the limiter is charged before the
        // dedup check, deliberately, so a replay flood cannot be made free by
        // reusing message ids.
        assertTrue("flood cost exceeded the bucket: " + accepted, accepted <= 200);
    }

    @Test
    public void hopCounterNeverExceedsHardCeiling() {
        build(25, dense(25, 99L, 0.5));
        deliver(0, -1, new Packet(11, MAX_TTL, 0));
        assertTrue(maxHopsObserved <= MAX_HOPS);
    }
}
