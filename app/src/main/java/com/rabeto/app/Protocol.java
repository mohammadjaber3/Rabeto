package com.rabeto.app;

import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Rabeto wire protocol, version 6. All network input is untrusted.
 *
 * Changes from v5 (Phase A):
 *
 *  1. A directed message MUST be encrypted. `enc=0` is now only legal for
 *     `to="*"` broadcasts, which are public by definition. This closes the
 *     downgrade hole where a peer could ask us to accept plaintext.
 *  2. Every envelope carries a random `nonce` that is covered by the
 *     signature. Two envelopes with identical content are no longer
 *     bit-identical, so a captured packet cannot be replayed under a fresh id.
 *  3. `ttl + hops <= MAX_TTL` is enforced. Previously a relay could reset
 *     `ttl` to 8 forever and keep a packet circulating in the mesh.
 *  4. `kind` is a closed whitelist instead of any 64-char string.
 *  5. Clock skew tolerance was raised from 10 minutes to 2 hours. An offline
 *     mesh has no NTP; strict skew was silently dropping valid traffic from
 *     phones with a drifted clock. Replay safety comes from the nonce and the
 *     seen-cache, not from a tight clock.
 *
 * The session KDF labels in Crypto.java intentionally stay at "v5": they name
 * the key-derivation scheme, not the wire format. Crypto.java is unchanged.
 */
public final class Protocol {

    public static final int VERSION = 6;

    public static final int MAX_TTL = 8;
    public static final int MAX_HOPS = 16;

    public static final long MAX_CLOCK_SKEW_MS = 2L * 60 * 60 * 1000L;
    public static final long MESSAGE_TTL_MS = 5L * 24 * 60 * 60 * 1000L;

    public static final int MAX_PACKET_BYTES = 256 * 1024;
    public static final int MAX_TEXT_CHARS = 32 * 1024;
    public static final int MAX_DATA_CHARS = 180 * 1024;

    public static final int MAX_ID_CHARS = 128;
    public static final int MAX_NAME_CHARS = 128;
    public static final int MAX_KIND_CHARS = 64;
    public static final int MAX_PUBLIC_KEY_CHARS = 4096;
    public static final int MAX_SIGNATURE_CHARS = 4096;
    public static final int MAX_NONCE_CHARS = 64;

    public static final int NONCE_BYTES = 16;

    public static final String BROADCAST = "*";

    public static final String KIND_TEXT = "text";
    public static final String KIND_ACK = "ack";
    public static final String KIND_READ = "read";
    public static final String KIND_PROFILE = "profile";
    public static final String KIND_PING = "ping";
    public static final String KIND_FILE = "file";
    public static final String KIND_VOICE = "voice";

    private static final Set<String> KINDS;

    static {
        Set<String> kinds = new HashSet<String>(Arrays.asList(
                KIND_TEXT, KIND_ACK, KIND_READ, KIND_PROFILE,
                KIND_PING, KIND_FILE, KIND_VOICE));
        KINDS = Collections.unmodifiableSet(kinds);
    }

    private Protocol() {}

    /** Fresh per-envelope replay nonce. */
    public static String newNonce() {
        return Base64.encodeToString(
                Crypto.randomBytes(NONCE_BYTES),
                Base64.NO_WRAP | Base64.URL_SAFE);
    }

    public static boolean isKnownKind(String kind) {
        return kind != null && KINDS.contains(kind);
    }

    /**
     * Canonical signing material for an identity announcement.
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
            writeString(d, e.optString("nonce", ""));

            d.flush();
            return Base64.encodeToString(b.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Canonical signing material for a message.
     * Mutable routing fields (ttl/hops) are intentionally excluded so a relay
     * can decrement them without invalidating the signature.
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
            writeString(out, e.optString("nonce", ""));
            writeString(out, e.optString("text", ""));
            writeString(out, e.optString("data", ""));
            writeString(out, e.optString("pk", ""));

            out.flush();

            return Base64.encodeToString(
                    bytes.toByteArray(),
                    Base64.NO_WRAP | Base64.URL_SAFE);
        } catch (Exception ex) {
            return "";
        }
    }

    /**
     * Additional authenticated data bound into AES-GCM. Any tampering with the
     * envelope header makes decryption fail rather than silently succeed.
     */
    public static String aad(JSONObject e) {
        return e.optInt("v") + "|" + e.optString("id") + "|"
                + e.optString("from") + "|" + e.optString("to") + "|"
                + e.optString("kind") + "|" + e.optLong("ts") + "|"
                + e.optInt("enc") + "|" + e.optString("nonce") + "|"
                + e.optString("pk");
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
        String nonce = e.optString("nonce", "");

        if (!bounded(id, 1, MAX_ID_CHARS)
                || !bounded(from, 1, MAX_ID_CHARS)
                || !(BROADCAST.equals(to) || bounded(to, 1, MAX_ID_CHARS))
                || !bounded(kind, 1, MAX_KIND_CHARS)
                || !bounded(pk, 1, MAX_PUBLIC_KEY_CHARS)
                || !bounded(sig, 1, MAX_SIGNATURE_CHARS)
                || !bounded(nonce, 8, MAX_NONCE_CHARS)) {
            return false;
        }

        // Only kinds this version understands are relayed or stored.
        if (!KINDS.contains(kind)) return false;

        // A sender cannot claim an identity that its public key does not hash to.
        try {
            if (!from.equals(Ident.idFor(pk))) return false;
        } catch (Throwable t) {
            return false;
        }

        // Nobody may address themselves as the mesh broadcast id.
        if (BROADCAST.equals(from)) return false;

        long ts = e.optLong("ts", 0L);
        if (ts <= 0L) return false;
        if (ts > now + MAX_CLOCK_SKEW_MS) return false;
        if (now - ts > MESSAGE_TTL_MS) return false;

        int ttl = e.optInt("ttl", -1);
        int hops = e.optInt("hops", -1);

        if (ttl < 0 || ttl > MAX_TTL || hops < 0 || hops > MAX_HOPS) return false;

        // Anti TTL-refresh: the remaining budget plus the distance already
        // travelled can never exceed the original budget.
        if (ttl + hops > MAX_TTL) return false;

        int enc = e.optInt("enc", -1);
        if (enc != 0 && enc != 1) return false;

        // Fail closed: a directed message is always end-to-end encrypted.
        // Plaintext is legal only for an explicit public broadcast.
        if (!BROADCAST.equals(to) && enc != 1) return false;

        String text = e.optString("text", "");
        String data = e.optString("data", "");

        if (text.length() > MAX_TEXT_CHARS || data.length() > MAX_DATA_CHARS) {
            return false;
        }

        // An ACK carries only the acknowledged message id.
        if (KIND_ACK.equals(kind) && data.length() > 0) return false;

        try {
            return e.toString().getBytes(StandardCharsets.UTF_8).length
                    <= MAX_PACKET_BYTES;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Validate an identity announcement. */
    public static boolean validHello(JSONObject o) {
        if (o == null || !"hello".equals(o.optString("t"))) return false;
        if (o.optInt("v", -1) != VERSION) return false;

        String id = o.optString("id", "");
        String name = o.optString("name", "");
        String pk = o.optString("pk", "");
        String sig = o.optString("sig", "");
        String nonce = o.optString("nonce", "");

        if (!bounded(id, 1, MAX_ID_CHARS)
                || !bounded(name, 1, MAX_NAME_CHARS)
                || !bounded(pk, 1, MAX_PUBLIC_KEY_CHARS)
                || !bounded(sig, 1, MAX_SIGNATURE_CHARS)
                || !bounded(nonce, 8, MAX_NONCE_CHARS)
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
     * Length-prefix strings using a 32-bit byte length so the canonical
     * encoding stays deterministic for fields larger than 64 KiB.
     */
    private static void writeString(DataOutputStream out, String value)
            throws Exception {
        byte[] b = value == null
                ? new byte[0]
                : value.getBytes(StandardCharsets.UTF_8);

        out.writeInt(b.length);
        out.write(b);
    }

    private static boolean bounded(String s, int min, int max) {
        return s != null
                && s.length() >= min
                && s.length() <= max;
    }
}
