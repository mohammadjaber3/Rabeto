package com.rabeto.app;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Small, audited-by-use cryptographic building blocks; no protocol state lives here. */
public final class Crypto {
    private static final int IV_BYTES = 12;
    private static final int KEY_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Crypto() {}

    /** RFC 5869 HKDF-SHA-256. */
    public static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int length)
            throws Exception {
        if (ikm == null || ikm.length == 0 || length < 0 || length > 255 * 32) {
            throw new IllegalArgumentException("invalid HKDF input");
        }
        byte[] actualSalt = salt == null ? new byte[32] : salt;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(actualSalt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);

        byte[] out = new byte[length];
        byte[] previous = new byte[0];
        int offset = 0;
        for (int counter = 1; offset < length; counter++) {
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(previous);
            if (info != null) mac.update(info);
            mac.update((byte) counter);
            previous = mac.doFinal();
            int n = Math.min(previous.length, length - offset);
            System.arraycopy(previous, 0, out, offset, n);
            offset += n;
        }
        return out;
    }

    public static byte[] deriveSessionKey(byte[] sharedSecret, String localId, String peerId)
            throws Exception {
        if (sharedSecret == null || localId == null || peerId == null) {
            throw new IllegalArgumentException("invalid session inputs");
        }
        String a = localId.compareTo(peerId) <= 0 ? localId : peerId;
        String b = localId.compareTo(peerId) <= 0 ? peerId : localId;
        byte[] salt = MessageDigest.getInstance("SHA-256")
                .digest(("Rabeto/session/v5/salt/" + a + "/" + b)
                        .getBytes(StandardCharsets.UTF_8));
        byte[] info = ("Rabeto/session/v5/key/" + a + "/" + b)
                .getBytes(StandardCharsets.UTF_8);
        return hkdfSha256(sharedSecret, salt, info, KEY_BYTES);
    }

    public static String encrypt(byte[] key, String plaintext, byte[] aad) throws Exception {
        if (key == null || key.length != KEY_BYTES || plaintext == null) {
            throw new IllegalArgumentException("invalid encryption input");
        }
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, iv));
        if (aad != null) cipher.updateAAD(aad);
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(iv, Base64.NO_WRAP | Base64.URL_SAFE) + ":"
                + Base64.encodeToString(ciphertext, Base64.NO_WRAP | Base64.URL_SAFE);
    }

    public static String decrypt(byte[] key, String blob, byte[] aad) throws Exception {
        if (key == null || key.length != KEY_BYTES || blob == null) return null;
        int separator = blob.indexOf(':');
        if (separator <= 0 || separator == blob.length() - 1) return null;
        try {
            byte[] iv = Base64.decode(blob.substring(0, separator), Base64.NO_WRAP | Base64.URL_SAFE);
            byte[] ciphertext = Base64.decode(blob.substring(separator + 1), Base64.NO_WRAP | Base64.URL_SAFE);
            if (iv.length != IV_BYTES || ciphertext.length < 16) return null;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, iv));
            if (aad != null) cipher.updateAAD(aad);
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static byte[] randomBytes(int length) {
        if (length < 0 || length > 1024) throw new IllegalArgumentException("invalid random length");
        byte[] out = new byte[length];
        RANDOM.nextBytes(out);
        return out;
    }

    public static String randomId(String prefix) {
        if (prefix == null || prefix.length() == 0) prefix = "m";
        return prefix + "-" + Base64.encodeToString(randomBytes(18), Base64.NO_WRAP | Base64.URL_SAFE);
    }
}
