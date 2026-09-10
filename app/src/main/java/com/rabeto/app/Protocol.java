package com.rabeto.app;

import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;

/** Versioned wire-protocol validation. All network input is untrusted. */
public final class Protocol {
    public static final int VERSION = 5;

    public static final int MAX_TTL = 8;
    public static final int MAX_HOPS = 16;

    public static final long MAX_CLOCK_SKEW_MS = 10 * 60 * 1000L;
    public static final long MESSAGE_TTL_MS = 5L * 24 * 60 * 60 * 1000L;

    public static final int MAX_PACKET_BYTES = 256 * 1024;
    public static final int MAX_TEXT_CHARS = 32 * 1024;
    public static final int MAX_DATA_CHARS = 180 * 1024;

    public static final int MAX_ID_CHARS = 128;
    public static final int MAX_NAME_CHARS = 128;
    public static final int MAX_KIND_CHARS = 64;
    public static final int MAX_PUBLIC_KEY_CHARS = 4096;
    public static final int MAX_SIGNATURE_CHARS = 4096;

    private Protocol() {}

    /**
     * Canonical signing material for an identity announcement.
     * Identity profile fields are signed so peers can authenticate
     * the announced public key, name, and avatar before trusting them.
     * Length-prefixing prevents delimiter/escaping ambiguity.
     */
    public static String canonicalHello(JSONObject e) {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(b);

            d.writeInt(e.optInt("v", -1));
            writeString(d, e.optString("id", ""));
            writeString(d, e.optString("name", ""));
            writeString(d, e.optString("av", ""));
            writeString(d, e.optString("pk", ""));

            d.flush();
            return Base64.encodeToString(b.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Canonical signing material for a message.
     * Mutable routing fields such as ttl/hops are intentionally excluded.
     */
    public static String canonicalMessage(JSONObject e) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(512);
            DataOutputStream out = new DataOutputStream(bytes);

            out.writeInt(e.optInt("v", -1));
            writeString(out, e.optString("id", ""));
            writeString(out, e.optString("from", ""));
            writeString(out, e.optString("name", ""));
            writeString(out, e.optString("to", ""));
            writeString(out, e.optString("kind", ""));
            out.writeLong(e.optLong("ts", 0L));
            out.writeInt(e.optInt("enc", 0));
            writeString(out, e.optString("text", ""));
            writeString(out, e.optString("data", ""));
            writeString(out, e.optString("pk", ""));

            out.flush();

            return Base64.encodeToString(
                    bytes.toByteArray(),
                    Base64.NO_WRAP | Base64.URL_SAFE
            );
        } catch (Exception ex) {
            return "";
        }
    }

    /**
     * Validate an incoming message envelope before authentication,
     * decryption, caching, or forwarding.
     */
    public static boolean validEnvelope(JSONObject e, long now) {
        if (e == null || !"msg".equals(e.optString("t"))) return false;
        if (e.optInt("v", -1) != VERSION) return false;

        String id = e.optString("id", "");
        String from = e.optString("from", "");
        String to = e.optString("to", "");
        String kind = e.optString("kind", "");
        String pk = e.optString("pk", "");
        String sig = e.optString("sig", "");

        if (!bounded(id, 1, MAX_ID_CHARS)
                || !bounded(from, 1, MAX_ID_CHARS)
                || !("*".equals(to) || bounded(to, 1, MAX_ID_CHARS))
                || !bounded(kind, 1, MAX_KIND_CHARS)
                || !bounded(pk, 1, MAX_PUBLIC_KEY_CHARS)
                || !bounded(sig, 1, MAX_SIGNATURE_CHARS)) {
            return false;
        }

        try {
            if (!from.equals(Ident.idFor(pk))) return false;
        } catch (Throwable t) {
            return false;
        }

        long ts = e.optLong("ts", 0L);
        if (ts <= 0L) return false;

        // Reject messages too far in the future.
        if (ts > now + MAX_CLOCK_SKEW_MS) return false;

        // Reject messages older than the five-day delivery window.
        if (now - ts > MESSAGE_TTL_MS) return false;

        int ttl = e.optInt("ttl", -1);
        int hops = e.optInt("hops", -1);

        if (ttl < 0 || ttl > MAX_TTL || hops < 0 || hops > MAX_HOPS) {
            return false;
        }

        int enc = e.optInt("enc", -1);
        if (enc != 0 && enc != 1) return false;

        String text = e.optString("text", "");
        String data = e.optString("data", "");

        if (text.length() > MAX_TEXT_CHARS
                || data.length() > MAX_DATA_CHARS) {
            return false;
        }

        try {
            return e.toString().getBytes(StandardCharsets.UTF_8).length
                    <= MAX_PACKET_BYTES;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Validate an identity announcement.
     */
    public static boolean validHello(JSONObject o) {
        if (o == null || !"hello".equals(o.optString("t"))) return false;
        if (o.optInt("v", -1) != VERSION) return false;

        String id = o.optString("id", "");
        String name = o.optString("name", "");
        String pk = o.optString("pk", "");
        String sig = o.optString("sig", "");

        if (!bounded(id, 1, MAX_ID_CHARS)
                || !bounded(name, 1, MAX_NAME_CHARS)
                || !bounded(pk, 1, MAX_PUBLIC_KEY_CHARS)
                || !bounded(sig, 1, MAX_SIGNATURE_CHARS)
                || o.optString("av", "").length() > MAX_DATA_CHARS) {
            return false;
        }

        try {
            return id.equals(Ident.idFor(pk));
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean validPacketSize(byte[] bytes) {
        return bytes != null
                && bytes.length > 0
                && bytes.length <= MAX_PACKET_BYTES;
    }

    /**
     * Length-prefix strings using a 32-bit byte length.
     * This supports fields larger than 64 KiB while keeping
     * canonical encoding deterministic.
     */
    private static void writeString(DataOutputStream out, String value)
            throws Exception {
        byte[] b = value == null
                ? new byte[0]
                : value.getBytes(StandardCharsets.UTF_8);

        if (b.length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("field too large");
        }

        out.writeInt(b.length);
        out.write(b);
    }

    private static boolean bounded(String s, int min, int max) {
        return s != null
                && s.length() >= min
                && s.length() <= max;
    }
}
