package com.rabeto.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.crypto.KeyAgreement;

/**
 * Crypto.java was NOT modified in Phase A. These tests exist to pin its
 * behaviour so future phases cannot quietly regress it.
 */
public class CryptoTest {

    /** RFC 5869 test case 1. If this breaks, the KDF is wrong, not the caller. */
    @Test
    public void hkdfMatchesRfc5869Vector() throws Exception {
        byte[] ikm = new byte[22];
        Arrays.fill(ikm, (byte) 0x0b);
        byte[] salt = new byte[13];
        for (int i = 0; i < salt.length; i++) salt[i] = (byte) i;
        byte[] info = new byte[10];
        for (int i = 0; i < info.length; i++) info[i] = (byte) (0xf0 + i);

        byte[] okm = Crypto.hkdfSha256(ikm, salt, info, 42);

        String expected = "3cb25f25faacd57a90434f64d0362f2a"
                + "2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
                + "34007208d5b887185865";
        assertEquals(expected, hex(okm));
    }

    @Test
    public void sessionKeyIsSymmetricForBothPeers() throws Exception {
        TestKeys a = new TestKeys();
        TestKeys b = new TestKeys();

        byte[] sharedA = ecdh(a, b);
        byte[] sharedB = ecdh(b, a);
        assertArrayEquals(sharedA, sharedB);

        byte[] keyA = Crypto.deriveSessionKey(sharedA, a.id, b.id);
        byte[] keyB = Crypto.deriveSessionKey(sharedB, b.id, a.id);

        // The KDF sorts the identities, so peer order cannot desynchronise keys.
        assertArrayEquals(keyA, keyB);
        assertEquals(32, keyA.length);
    }

    @Test
    public void roundTripsWithMatchingAad() throws Exception {
        byte[] key = Crypto.randomBytes(32);
        byte[] aad = "v6|m-1|alice|bob".getBytes(StandardCharsets.UTF_8);

        String blob = Crypto.encrypt(key, "سلام دنیا", aad);
        assertNotNull(blob);
        assertTrue(blob.contains(":"));
        assertEquals("سلام دنیا", Crypto.decrypt(key, blob, aad));
    }

    @Test
    public void tamperedAadFailsDecryption() throws Exception {
        byte[] key = Crypto.randomBytes(32);
        String blob = Crypto.encrypt(key, "secret",
                "header-a".getBytes(StandardCharsets.UTF_8));
        assertNull(Crypto.decrypt(key, blob,
                "header-b".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void wrongKeyFailsCleanlyInsteadOfThrowing() throws Exception {
        byte[] key = Crypto.randomBytes(32);
        byte[] other = Crypto.randomBytes(32);
        String blob = Crypto.encrypt(key, "secret", null);
        assertNull(Crypto.decrypt(other, blob, null));
    }

    @Test
    public void ivIsNeverReused() throws Exception {
        byte[] key = Crypto.randomBytes(32);
        String a = Crypto.encrypt(key, "same", null);
        String b = Crypto.encrypt(key, "same", null);
        assertFalse(a.equals(b));
    }

    @Test
    public void malformedBlobsReturnNull() throws Exception {
        byte[] key = Crypto.randomBytes(32);
        assertNull(Crypto.decrypt(key, "no-separator", null));
        assertNull(Crypto.decrypt(key, ":", null));
        assertNull(Crypto.decrypt(key, "AAAA:", null));
        assertNull(Crypto.decrypt(key, "!!!!:!!!!", null));
    }

    private static byte[] ecdh(TestKeys mine, TestKeys theirs) throws Exception {
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(mine.pair.getPrivate());
        ka.doPhase(theirs.pair.getPublic(), true);
        return ka.generateSecret();
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) sb.append(String.format("%02x", b[i] & 0xff));
        return sb.toString();
    }
}
