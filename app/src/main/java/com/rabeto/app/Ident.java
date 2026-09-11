package com.rabeto.app;

import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Rabeto identity boundary.
 *
 * Signing and ECDH are deliberately separate cryptographic roles.
 * - Signing key: Android Keystore, used for identity and signatures.
 * - ECDH key: Android Keystore on API 31+, or an EC software key encrypted at
 *   rest by an AES key held in Android Keystore on API 24-30.
 *
 * The legacy v1 key is accepted as a signing key when it already exists so an
 * existing API 31+ installation does not unnecessarily change its identity.
 */
public class Ident {
    private static final String SIGN_ALIAS = "rabeto_identity_sign_v2";
    private static final String LEGACY_ALIAS = "rabeto_identity_ec_v1";
    private static final String ECDH_ALIAS = "rabeto_identity_ecdh_v2";
    private static final String WRAP_ALIAS = "rabeto_identity_ecdh_wrap_v2";

    private static final String PREF_ECDH_PUBLIC = "identity_ecdh_public_v2";
    private static final String PREF_ECDH_PRIVATE = "identity_ecdh_private_v2";
    private static final String PREF_ECDH_IV = "identity_ecdh_iv_v2";

    private final SharedPreferences prefs;
    private final SecureRandom rnd = new SecureRandom();
    private final Map<String, byte[]> secretCache = new HashMap<String, byte[]>();

    private boolean ok;
    private boolean ecdhOk;
    private PrivateKey signingPriv;
    private PublicKey signingPub;
    private PrivateKey ecdhPriv;
    private PublicKey ecdhPub;
    private String pubB64 = "";
    private String ecdhPubB64 = "";
    private String id = "";

    public Ident(SharedPreferences prefs) {
        this.prefs = prefs;
        try {
            loadOrCreateSigningKey();
            if (signingPriv == null || signingPub == null) {
                throw new Exception("signing identity unavailable");
            }
            pubB64 = Base64.encodeToString(signingPub.getEncoded(), Base64.NO_WRAP);
            id = idFor(pubB64);
            ok = id.length() > 0;
        } catch (Throwable t) {
            ok = false;
            signingPriv = null;
            signingPub = null;
            pubB64 = "";
            id = "";
        }

        // ECDH failure must never destroy the signing identity. Broadcasts only
        // require signing. Direct encrypted sessions additionally require ECDH.
        try {
            loadOrCreateEcdhKey();
            ecdhOk = ecdhPriv != null && ecdhPub != null;
            if (ecdhOk) {
                ecdhPubB64 = Base64.encodeToString(ecdhPub.getEncoded(), Base64.NO_WRAP);
            }
        } catch (Throwable t) {
            ecdhOk = false;
            ecdhPriv = null;
            ecdhPub = null;
            ecdhPubB64 = "";
        }
    }

    private void loadOrCreateSigningKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);

        String alias = null;
        if (ks.containsAlias(SIGN_ALIAS)) {
            alias = SIGN_ALIAS;
        } else if (ks.containsAlias(LEGACY_ALIAS)) {
            // Preserve an already-created Phase A identity on API 31+.
            alias = LEGACY_ALIAS;
        } else {
            createSigningKey();
            alias = SIGN_ALIAS;
        }

        KeyStore.Entry entry = ks.getEntry(alias, null);
        if (!(entry instanceof KeyStore.PrivateKeyEntry)) {
            throw new Exception("Rabeto signing key is not a private key entry");
        }
        KeyStore.PrivateKeyEntry pe = (KeyStore.PrivateKeyEntry) entry;
        signingPriv = pe.getPrivateKey();
        signingPub = pe.getCertificate().getPublicKey();
    }

    private static void createSigningKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                SIGN_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build();
        generator.initialize(spec);
        generator.generateKeyPair();
    }

    private void loadOrCreateEcdhKey() throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (!ks.containsAlias(ECDH_ALIAS)) {
                KeyPairGenerator generator = KeyPairGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
                KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                        ECDH_ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
                        .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                        .build();
                generator.initialize(spec);
                generator.generateKeyPair();
            }
            KeyStore.Entry entry = ks.getEntry(ECDH_ALIAS, null);
            if (!(entry instanceof KeyStore.PrivateKeyEntry)) {
                throw new Exception("Rabeto ECDH key is not a private key entry");
            }
            KeyStore.PrivateKeyEntry pe = (KeyStore.PrivateKeyEntry) entry;
            ecdhPriv = pe.getPrivateKey();
            ecdhPub = pe.getCertificate().getPublicKey();
            return;
        }

        // Android 11 and older: PURPOSE_AGREE_KEY does not exist in the API.
        // Keep the ECDH private key encrypted at rest with an AES-GCM key in
        // Android Keystore. The raw EC private key is never written plaintext.
        KeyGenerator kg = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(WRAP_ALIAS)) {
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                    WRAP_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build();
            kg.init(spec);
            kg.generateKey();
        }

        String publicB64 = prefs.getString(PREF_ECDH_PUBLIC, "");
        String privateB64 = prefs.getString(PREF_ECDH_PRIVATE, "");
        String ivB64 = prefs.getString(PREF_ECDH_IV, "");

        if (publicB64.length() == 0 || privateB64.length() == 0 || ivB64.length() == 0) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair pair = generator.generateKeyPair();
            byte[][] wrapped = wrap(pair.getPrivate().getEncoded());
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(PREF_ECDH_PUBLIC,
                    Base64.encodeToString(pair.getPublic().getEncoded(), Base64.NO_WRAP));
            editor.putString(PREF_ECDH_IV,
                    Base64.encodeToString(wrapped[0], Base64.NO_WRAP));
            editor.putString(PREF_ECDH_PRIVATE,
                    Base64.encodeToString(wrapped[1], Base64.NO_WRAP));
            if (!editor.commit()) throw new Exception("could not persist ECDH key");
            publicB64 = Base64.encodeToString(pair.getPublic().getEncoded(), Base64.NO_WRAP);
            privateB64 = Base64.encodeToString(wrapped[1], Base64.NO_WRAP);
            ivB64 = Base64.encodeToString(wrapped[0], Base64.NO_WRAP);
        }

        byte[] publicBytes = Base64.decode(publicB64, Base64.NO_WRAP);
        byte[] privateBytes = unwrap(
                Base64.decode(ivB64, Base64.NO_WRAP),
                Base64.decode(privateB64, Base64.NO_WRAP));
        KeyFactory kf = KeyFactory.getInstance("EC");
        ecdhPub = kf.generatePublic(new X509EncodedKeySpec(publicBytes));
        ecdhPriv = kf.generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
    }

    private byte[][] wrap(byte[] plain) throws Exception {
        SecretKey key = getWrapKey();
        byte[] iv = new byte[12];
        rnd.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        return new byte[][] { iv, cipher.doFinal(plain) };
    }

    private byte[] unwrap(byte[] iv, byte[] ciphertext) throws Exception {
        if (iv == null || iv.length != 12) throw new Exception("invalid ECDH IV");
        SecretKey key = getWrapKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        return cipher.doFinal(ciphertext);
    }

    private SecretKey getWrapKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) ks.getEntry(WRAP_ALIAS, null);
        if (entry == null) throw new Exception("ECDH wrapping key unavailable");
        return entry.getSecretKey();
    }

    public boolean ok() { return ok; }
    public boolean ecdhOk() { return ecdhOk; }
    public String id() { return id; }
    public String pubKey() { return pubB64; }
    public String ecdhPubKey() { return ecdhPubB64; }

    byte[] sharedSecretForSession(String peerEcdhPublicKeyB64) throws Exception {
        if (!ok || !ecdhOk || ecdhPriv == null) throw new Exception("ECDH unavailable");
        PublicKey peer = KeyFactory.getInstance("EC").generatePublic(
                new X509EncodedKeySpec(Base64.decode(peerEcdhPublicKeyB64, Base64.NO_WRAP)));
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(ecdhPriv);
        agreement.doPhase(peer, true);
        return agreement.generateSecret();
    }

    public String code() { return codeFor(pubB64); }

    private static byte[] hash(String pkB64) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(
                    Base64.decode(pkB64, Base64.NO_WRAP));
        } catch (Throwable t) { return null; }
    }

    public static String idFor(String pkB64) {
        byte[] h = hash(pkB64);
        if (h == null || h.length < 16) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            sb.append(String.format(Locale.US, "%02x", h[i] & 0xff));
        }
        return sb.toString();
    }

    public static String codeFor(String pkB64) {
        byte[] h = hash(pkB64);
        if (h == null || h.length < 16) return "00000000";
        long v = 0;
        for (int i = 5; i < 11; i++) v = (v << 8) | (h[i] & 0xffL);
        v %= 100000000L;
        String s = String.format(Locale.US, "%08d", v);
        return s.substring(0, 4) + " " + s.substring(4);
    }

    public String sign(String data) {
        if (!ok || signingPriv == null || data == null) return "";
        try {
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(signingPriv);
            signature.update(data.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(signature.sign(), Base64.NO_WRAP);
        } catch (Throwable t) { return ""; }
    }

    public static boolean verify(String pkB64, String data, String sigB64) {
        if (pkB64 == null || sigB64 == null || data == null
                || pkB64.length() == 0 || sigB64.length() == 0) return false;
        try {
            PublicKey pk = KeyFactory.getInstance("EC").generatePublic(
                    new X509EncodedKeySpec(Base64.decode(pkB64, Base64.NO_WRAP)));
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initVerify(pk);
            signature.update(data.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.decode(sigB64, Base64.NO_WRAP));
        } catch (Throwable t) { return false; }
    }

    private byte[] secret(String peerEcdhB64) {
        if (!ecdhOk || peerEcdhB64 == null || peerEcdhB64.length() == 0) return null;
        byte[] cached = secretCache.get(peerEcdhB64);
        if (cached != null) return cached;
        try {
            PublicKey theirs = KeyFactory.getInstance("EC").generatePublic(
                    new X509EncodedKeySpec(Base64.decode(peerEcdhB64, Base64.NO_WRAP)));
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(ecdhPriv);
            agreement.doPhase(theirs, true);
            byte[] raw = agreement.generateSecret();
            byte[] key = MessageDigest.getInstance("SHA-256").digest(raw);
            java.util.Arrays.fill(raw, (byte) 0);
            secretCache.put(peerEcdhB64, key);
            return key;
        } catch (Throwable t) { return null; }
    }

    public String encrypt(String peerEcdhB64, String plain) {
        if (!ok || !ecdhOk || plain == null) return "";
        byte[] key = secret(peerEcdhB64);
        if (key == null) return "";
        try {
            byte[] iv = new byte[12];
            rnd.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(iv, Base64.NO_WRAP) + ":"
                    + Base64.encodeToString(ciphertext, Base64.NO_WRAP);
        } catch (Throwable t) { return ""; }
    }

    public String decrypt(String peerEcdhB64, String blob) {
        if (!ok || !ecdhOk || blob == null) return null;
        int separator = blob.indexOf(':');
        if (separator < 1) return null;
        byte[] key = secret(peerEcdhB64);
        if (key == null) return null;
        try {
            byte[] iv = Base64.decode(blob.substring(0, separator), Base64.NO_WRAP);
            byte[] ciphertext = Base64.decode(blob.substring(separator + 1), Base64.NO_WRAP);
            if (iv.length != 12 || ciphertext.length < 16) return null;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Throwable t) { return null; }
    }
}
