package com.rabeto.app.transport;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * BLE hands you an arbitrary chunk stream, not messages. Tested against
 * fragmentation, coalescing, partial frames and hostile length headers.
 * Pure JVM: no Android, no Robolectric, no network.
 */
public class FramingTest {

    @Test
    public void reassemblesAcrossWorstCaseBleChunks() {
        byte[] payload = new byte[900];
        new Random(1).nextBytes(payload);
        byte[] framed = Framing.frame(payload);

        Framing.Reassembler r = new Framing.Reassembler();
        List<byte[]> got = new ArrayList<byte[]>();

        // 20 bytes is the payload you get on a default 23-byte BLE MTU.
        List<byte[]> chunks = Framing.chunks(framed, 20);
        assertTrue(chunks.size() > 40);
        for (int i = 0; i < chunks.size(); i++) got.addAll(r.accept(chunks.get(i)));

        assertEquals(1, got.size());
        assertArrayEquals(payload, got.get(0));
        assertEquals(0, r.buffered());
    }

    @Test
    public void splitsCoalescedFrames() {
        byte[] a = "first".getBytes();
        byte[] b = "second".getBytes();

        byte[] fa = Framing.frame(a);
        byte[] fb = Framing.frame(b);
        byte[] both = new byte[fa.length + fb.length];
        System.arraycopy(fa, 0, both, 0, fa.length);
        System.arraycopy(fb, 0, both, fa.length, fb.length);

        List<byte[]> got = new Framing.Reassembler().accept(both);
        assertEquals(2, got.size());
        assertArrayEquals(a, got.get(0));
        assertArrayEquals(b, got.get(1));
    }

    @Test
    public void waitsForAnIncompleteFrame() {
        byte[] framed = Framing.frame("hello world".getBytes());
        Framing.Reassembler r = new Framing.Reassembler();
        assertTrue(r.accept(Arrays.copyOfRange(framed, 0, 6)).isEmpty());
        assertEquals(1, r.accept(Arrays.copyOfRange(framed, 6, framed.length)).size());
    }

    @Test
    public void handlesAHeaderSplitAcrossChunks() {
        byte[] framed = Framing.frame("payload".getBytes());
        Framing.Reassembler r = new Framing.Reassembler();
        assertTrue(r.accept(new byte[]{framed[0], framed[1]}).isEmpty());
        assertEquals(1, r.accept(Arrays.copyOfRange(framed, 2, framed.length)).size());
    }

    @Test
    public void dropsHostileLengthHeaderInsteadOfBufferingForever() {
        // A peer claims a 2 GiB frame. We must resynchronise, not allocate.
        byte[] evil = new byte[]{(byte) 0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff, 1, 2, 3};
        Framing.Reassembler r = new Framing.Reassembler();
        assertTrue(r.accept(evil).isEmpty());
        assertEquals(0, r.buffered());

        // The stream recovers for the next well-formed frame.
        assertEquals(1, r.accept(Framing.frame("ok".getBytes())).size());
    }

    @Test
    public void dropsZeroAndNegativeLengthHeaders() {
        assertTrue(new Framing.Reassembler().accept(new byte[]{0, 0, 0, 0}).isEmpty());
        assertTrue(new Framing.Reassembler()
                .accept(new byte[]{(byte) 0xff, 0, 0, 0}).isEmpty());
    }

    @Test
    public void rejectsFramesOverTheCeiling() {
        byte[] tooBig = Framing.frame(new byte[Framing.MAX_FRAME_BYTES + 1]);
        assertTrue(new Framing.Reassembler().accept(tooBig).isEmpty());
    }

    @Test
    public void hexRoundTripsIdentityFingerprint() {
        String id = "a1b2c3d4e5";
        byte[] bytes = Framing.hexToBytes(id);
        assertEquals(5, bytes.length);
        assertEquals(id, Framing.bytesToHex(bytes, 5));
        assertEquals("", Framing.bytesToHex(null, 5));
        assertEquals(0, Framing.hexToBytes(null).length);
    }
}
