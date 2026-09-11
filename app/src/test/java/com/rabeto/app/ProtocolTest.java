package com.rabeto.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

/**
 * Protocol v6 rules, tested as adversarial input rather than happy path.
 * Every one of these cases is a way a hostile peer could try to get something
 * past the validator.
 *
 * Pure JVM. android.util.Base64 is provided by a class in the test source set,
 * so the suite needs no emulator, no Robolectric and no network.
 */
public class ProtocolTest {

    private TestKeys alice;
    private TestKeys bob;
    private long now;

    @Before
    public void setUp() throws Exception {
        alice = new TestKeys();
        bob = new TestKeys();
        now = System.currentTimeMillis();
    }

    private JSONObject envelope(String to, int enc, String text) throws Exception {
        JSONObject e = new JSONObject();
        e.put("t", "msg");
        e.put("v", Protocol.VERSION);
        e.put("id", "m-1");
        e.put("from", alice.id);
        e.put("name", "Alice");
        e.put("to", to);
        e.put("kind", Protocol.KIND_TEXT);
        e.put("ts", now);
        e.put("enc", enc);
        e.put("nonce", Protocol.newNonce());
        e.put("text", text);
        e.put("data", "");
        e.put("pk", alice.publicKeyB64);
        e.put("epk", alice.ecdhPublicKeyB64);
        e.put("ttl", Protocol.MAX_TTL);
        e.put("hops", 0);
        e.put("sig", alice.sign(Protocol.canonicalMessage(e)));
        return e;
    }

    @Test
    public void acceptsWellFormedEncryptedDirectMessage() throws Exception {
        JSONObject e = envelope(bob.id, 1, "sealed-blob");
        assertTrue(Protocol.validEnvelope(e, now));
        assertTrue(Ident.verify(alice.publicKeyB64,
                Protocol.canonicalMessage(e), e.getString("sig")));
    }

    @Test
    public void rejectsDirectMessageWithoutEcdhKey() throws Exception {
        JSONObject e = envelope(bob.id, 1, "sealed-blob");
        e.remove("epk");
        assertFalse(Protocol.validEnvelope(e, now));
    }

    @Test
    public void rejectsPlaintextDirectMessage() throws Exception {
        // The v5 downgrade hole: enc=0 addressed to a specific identity.
        JSONObject e = envelope(bob.id, 0, "hello in the clear");
        assertFalse(Protocol.validEnvelope(e, now));
    }

    @Test
    public void allowsPlaintextBroadcastOnly() throws Exception {
        JSONObject e = envelope(Protocol.BROADCAST, 0, "hello everyone");
        assertTrue(Protocol.validEnvelope(e, now));
    }

    @Test
    public void rejectsOldProtocolVersion() throws Exception {
        JSONObject e = envelope(bob.id, 1, "x");
        e.put("v", 5);
        assertFalse(Protocol.validEnvelope(e, now));
    }

    @Test
    public void rejectsForgedSenderId() throws Exception {
        JSONObject e = envelope(bob.id, 1, "x");
        e.put("from", bob.id); // claims Bob while presenting Alice's key
        assertFalse(Protocol.validEnvelope(e, now));
    }

    @Test
    public void rejectsUnknownKind() throws Exception {
        JSONObject e = envelope(bob.id, 1, "x");
        e.put("kind", "definitely-not-a-kind");
        assertFalse(Protocol.validEnvelope(e, now));
    }

    @Test
    public void rejectsMissingNonce() throws Exception {
        JSONObject e = envelope(bob.id, 1, "x");
        e.put("nonce", "");
        assertFalse(Protocol.validEnvelope(e, now));
    }

    @Test
    public void rejectsTtlRefreshAttack() throws Exception {
        // A relay tries to reset the budget so the packet circulates forever.
        JSONObject e = envelope(bob.id, 1, "x");
        e.put("ttl", Protocol.MAX_TTL);
        e.put("hops", 5);
        assertFalse(Protocol.validEnvelope(e, now));
    }

    @Test
    public void acceptsHonestlyDecrementedRelay() throws Exception {
        JSONObject e = envelope(bob.id, 1, "x");
        e.put("ttl", Protocol.MAX_TTL - 3);
        e.put("hops", 3);
        assertTrue(Protocol.validEnvelope(e, now));
        // Routing fields are outside the signature, so relaying keeps it valid.
        assertTrue(Ident.verify(alice.publicKeyB64,
                Protocol.canonicalMessage(e), e.getString("sig")));
    }

    @Test
    public void rejectsFutureTimestampBeyondSkew() throws Exception {
        JSONObject e = envelope(bob.id, 1, "x");
        e.put("ts", now + Protocol.MAX_CLOCK_SKEW_MS + 60_000L);
        assertFalse(Protocol.validEnvelope(e, now));
    }

    @Test
    public void rejectsExpiredMessage() throws Exception {
        JSONObject e = envelope(bob.id, 1, "x");
        e.put("ts", now - Protocol.MESSAGE_TTL_MS - 1000L);
        assertFalse(Protocol.validEnvelope(e, now));
    }

    @Test
    public void tamperingWithBodyBreaksSignature() throws Exception {
        JSONObject e = envelope(bob.id, 1, "original");
        e.put("text", "tampered");
        assertFalse(Ident.verify(alice.publicKeyB64,
                Protocol.canonicalMessage(e), e.getString("sig")));
    }

    @Test
    public void nonceMakesIdenticalMessagesDistinct() throws Exception {
        JSONObject a = envelope(bob.id, 1, "same text");
        JSONObject b = envelope(bob.id, 1, "same text");
        assertNotEquals(Protocol.canonicalMessage(a), Protocol.canonicalMessage(b));
    }

    @Test
    public void canonicalFormIsLengthPrefixedNotConcatenated() throws Exception {
        // "ab" + "c..." must not canonicalise the same as "a" + "bc...".
        // Both copies are cloned from ONE envelope so the nonce, id and
        // timestamp are identical: the field split is the only difference, which
        // is what makes this a real test of the length prefixing.
        JSONObject base = envelope(bob.id, 1, "x");

        JSONObject a = new JSONObject(base.toString());
        a.put("name", "ab");
        a.put("to", "c" + bob.id.substring(1));

        JSONObject b = new JSONObject(base.toString());
        b.put("name", "a");
        b.put("to", "bc" + bob.id.substring(2));

        assertNotEquals(Protocol.canonicalMessage(a), Protocol.canonicalMessage(b));
    }

    @Test
    public void rejectsAckCarryingAttachmentData() throws Exception {
        JSONObject e = envelope(bob.id, 1, "mid");
        e.put("kind", Protocol.KIND_ACK);
        e.put("data", "smuggled payload");
        assertFalse(Protocol.validEnvelope(e, now));
    }

    @Test
    public void rejectsOversizePacket() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Protocol.MAX_PACKET_BYTES + 16; i++) sb.append('a');
        assertFalse(Protocol.validPacketSize(sb.toString().getBytes()));
        assertFalse(Protocol.validPacketSize(new byte[0]));
        assertFalse(Protocol.validPacketSize(null));
    }

    // ------------------------------------------------------- key discovery rules
    // Phase A: an ECDH key may ride along on a broadcast so peers can learn it
    // without a surviving handshake, and it is optional everywhere it is not
    // required for encryption. Broadcast must never depend on it: that
    // dependency is exactly what commit cb06858 broke and c72d0ef repaired.

    private JSONObject hello(TestKeys who, String epk) throws Exception {
        JSONObject o = new JSONObject();
        o.put("t", "hello");
        o.put("v", Protocol.VERSION);
        o.put("id", who.id);
        o.put("name", "tester");
        o.put("av", "");
        o.put("pk", who.publicKeyB64);
        o.put("epk", epk);
        o.put("nonce", Protocol.newNonce());
        o.put("sig", who.sign(Protocol.canonicalHello(o)));
        return o;
    }

    @Test
    public void keyDiscoveryRules_helloIsValidWithEcdhKey() throws Exception {
        JSONObject o = hello(alice, alice.ecdhPublicKeyB64);
        assertTrue(Protocol.validHello(o));
        assertTrue(Ident.verify(alice.publicKeyB64,
                Protocol.canonicalHello(o), o.getString("sig")));
    }

    @Test
    public void keyDiscoveryRules_helloIsValidWithoutEcdhKey() throws Exception {
        // A device whose key agreement is unavailable must still be able to
        // announce itself, otherwise it cannot be seen, cannot relay, and
        // cannot take part in broadcast at all.
        JSONObject o = hello(alice, "");
        assertTrue(Protocol.validHello(o));
    }

    @Test
    public void keyDiscoveryRules_helloRejectsOversizeEcdhKey() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Protocol.MAX_PUBLIC_KEY_CHARS + 1; i++) sb.append('A');
        assertFalse(Protocol.validHello(hello(alice, sb.toString())));
    }

    @Test
    public void keyDiscoveryRules_helloRejectsForgedIdentity() throws Exception {
        JSONObject o = hello(alice, alice.ecdhPublicKeyB64);
        o.put("id", bob.id);
        assertFalse(Protocol.validHello(o));
    }

    @Test
    public void keyDiscoveryRules_broadcastMayCarryAnEcdhKey() throws Exception {
        JSONObject e = envelope(Protocol.BROADCAST, 0, "hello everyone");
        assertTrue(e.getString("epk").length() > 0);
        assertTrue(Protocol.validEnvelope(e, now));
    }

    @Test
    public void keyDiscoveryRules_broadcastWorksWithoutAnEcdhKey() throws Exception {
        // The cb06858 regression, guarded from the other side.
        JSONObject e = envelope(Protocol.BROADCAST, 0, "hello everyone");
        e.put("epk", "");
        e.put("sig", alice.sign(Protocol.canonicalMessage(e)));
        assertTrue(Protocol.validEnvelope(e, now));
        assertTrue(Ident.verify(alice.publicKeyB64,
                Protocol.canonicalMessage(e), e.getString("sig")));
    }

    @Test
    public void keyDiscoveryRules_broadcastRejectsOversizeEcdhKey() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Protocol.MAX_PUBLIC_KEY_CHARS + 1; i++) sb.append('A');
        JSONObject e = envelope(Protocol.BROADCAST, 0, "x");
        e.put("epk", sb.toString());
        e.put("sig", alice.sign(Protocol.canonicalMessage(e)));
        assertFalse(Protocol.validEnvelope(e, now));
    }

    @Test
    public void keyDiscoveryRules_directMessageStillRequiresEncryption() throws Exception {
        // Nothing above may be read as permission to send a private message
        // in the clear.
        assertFalse(Protocol.validEnvelope(envelope(bob.id, 0, "clear"), now));
    }
}
