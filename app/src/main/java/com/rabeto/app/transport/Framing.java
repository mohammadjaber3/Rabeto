package com.rabeto.app.transport;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Length-prefixed framing over a chunk-oriented link.
 *
 * BLE, and later Wi-Fi Direct or a serial link, do not deliver messages: they
 * deliver an arbitrary stream of chunks whose boundaries mean nothing. Getting
 * reassembly wrong is the classic reason a hand-rolled transport silently
 * corrupts or drops data.
 *
 * Deliberately dependency-free: no Android imports, so the whole thing is unit
 * tested on the JVM in milliseconds with no emulator and no Robolectric.
 */
public final class Framing {

    public static final int HEADER_BYTES = 4;

    /** Ceiling per reassembly buffer, so a peer cannot exhaust our heap. */
    public static final int MAX_FRAME_BYTES = 64 * 1024;

    private Framing() {}

    /** Prefix a payload with its 32-bit big-endian length. */
    public static byte[] frame(byte[] payload) {
        if (payload == null) return new byte[0];
        int n = payload.length;
        byte[] out = new byte[HEADER_BYTES + n];
        out[0] = (byte) ((n >>> 24) & 0xff);
        out[1] = (byte) ((n >>> 16) & 0xff);
        out[2] = (byte) ((n >>> 8) & 0xff);
        out[3] = (byte) (n & 0xff);
        System.arraycopy(payload, 0, out, HEADER_BYTES, n);
        return out;
    }

    /** Split a framed buffer into link-sized chunks. */
    public static List<byte[]> chunks(byte[] framed, int chunkSize) {
        List<byte[]> out = new ArrayList<byte[]>();
        if (framed == null || framed.length == 0) return out;
        int size = chunkSize < 1 ? 1 : chunkSize;
        int offset = 0;
        while (offset < framed.length) {
            int n = Math.min(size, framed.length - offset);
            out.add(Arrays.copyOfRange(framed, offset, offset + n));
            offset += n;
        }
        return out;
    }

    public static byte[] hexToBytes(String hex) {
        if (hex == null) return new byte[0];
        int n = hex.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            try {
                out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            } catch (Throwable t) {
                return Arrays.copyOf(out, i);
            }
        }
        return out;
    }

    public static String bytesToHex(byte[] bytes, int count) {
        if (bytes == null) return "";
        int n = Math.min(count, bytes.length);
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) {
            sb.append(String.format(Locale.US, "%02x", bytes[i] & 0xff));
        }
        return sb.toString();
    }

    /** Stateful reassembler. One instance per link. */
    public static final class Reassembler {

        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        /** @return every complete frame this chunk finished off. */
        public List<byte[]> accept(byte[] chunk) {
            List<byte[]> out = new ArrayList<byte[]>();
            if (chunk == null || chunk.length == 0) return out;
            buffer.write(chunk, 0, chunk.length);

            while (true) {
                byte[] all = buffer.toByteArray();
                if (all.length < HEADER_BYTES) return out;

                int length = ((all[0] & 0xff) << 24)
                        | ((all[1] & 0xff) << 16)
                        | ((all[2] & 0xff) << 8)
                        | (all[3] & 0xff);

                // A hostile or desynchronised peer must not be able to make us
                // buffer forever: drop the stream and resynchronise.
                if (length <= 0 || length > MAX_FRAME_BYTES) {
                    buffer.reset();
                    return out;
                }
                if (all.length < HEADER_BYTES + length) return out;

                out.add(Arrays.copyOfRange(all, HEADER_BYTES, HEADER_BYTES + length));

                byte[] rest = Arrays.copyOfRange(all, HEADER_BYTES + length, all.length);
                buffer.reset();
                buffer.write(rest, 0, rest.length);
            }
        }

        public void reset() {
            buffer.reset();
        }

        public int buffered() {
            return buffer.size();
        }
    }
}
