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
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Rabeto identity boundary.
 *
 * Signing and ECDH are deliberately separate cryptographic roles.
 * - Signing key: Android Keystore, used for identity and signatures.
 * - ECDH key: Android Keystore on API 31+ (PURPOSE_AGREE_KEY), or an EC
 *   software key encrypted at rest by an AES key held in Android Keystore on
 *   API 24-30, where PURPOSE_AGREE_KEY does not exist.
 *
 * Two provider rules are not optional and were the cause of the Phase A
 * private-messaging failure:
 *
 *  1. A PURPOSE_AGREE_KEY private key never leaves the Keystore, so the key
 *     agreement MUST run inside the Keystore provider:
 *         KeyAgreement.getInstance("ECDH", "AndroidKeyStore")
 *     The default provider cannot see the key material and rejects the key.
 *     A software key is the opposite case and must use the default provider.
 *
 *  2. KeyGenParameterSpec.setRandomizedEncryptionRequired() defaults to TRUE,
 *     and with it the Keystore REJECTS a caller-supplied AES-GCM IV. So the
 *     wrapping cipher must let the Keystore choose the IV and read it back
 *     with Cipher.getIV(). We do not weaken the key to allow our own IV.
 *
 * ecdhOk() now means "key agreement was proven to work in this process", not
 * "a key object exists". The constructor runs one throwaway agreement to
 * establish that. A silent lie here is worse than a clean failure, because
 * every private session depends on it.
 *
 * No secret material is ever logged or exposed. ecdhDetail() carries only an
 * exception type and stage name for diagnostics.
 */
public class Ident {
    private static final String SIGN_ALIAS = "rabeto_identity_sign_v2";
    private static final String LEGACY_ALIAS = "rabeto_identity_ec_v1";
    private static final String ECDH_ALIAS = "rabeto_identity_ecdh_v2";
    private static final String WRAP_ALIAS = "rabeto_identity_ecdh_wrap_v2";

    private static final String PREF_ECDH_PUBLIC = "identity_ecdh_public_v2";
    private static final String PREF_ECDH_PRIVATE = "identity_ecdh_private_v2";
    private static final String PREF_ECDH_IV = "identity_ecdh_iv_v2";

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final int GCM_IV_BYTES = 12;

    private final SharedPreferences prefs;

    private boolean ok;
    private boolean ecdhOk;
    /** True when the ECDH private key lives in the Keystore and cannot be exported. */
    private boolean ecdhInKeystore;
    private String ecdhDetail = "";

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
        String stage = "init";
        try {
            stage = "generate";
            loadOrCreateEcdhKey();
            if (ecdhPriv == null || ecdhPub == null) {
                throw new Exception("ECDH keypair unavailable");
            }
            // Prove the key can actually perform an agreement before anybody
            // relies on it. This is what turns a broken provider choice into a
            // reportable fact instead of a message stuck on a clock icon.
            stage = "selftest";
            selfTestAgreement();
            ecdhPubB64 = Base64.encodeToString(ecdhPub.getEncoded(), Base64.NO_WRAP);
            ecdhOk = true;
            ecdhDetail = ecdhInKeystore ? "keystore" : "software";
        } catch (Throwable t) {
            ecdhOk = false;
            ecdhPriv = null;
            ecdhPub = null;
            ecdhPubB64 = "";
            ecdhDetail = stage + ":" + t.getClass().getSimpleName();
        }
    }

    // ------------------------------------------------------------------- signing

    private void loadOrCreateSigningKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);

        String alias;
        if (ks.containsAlias(SIGN_ALIAS)) {
            alias = SIGN_ALIAS;
        } else if (ks.containsAlias(LEGACY_ALIAS)) {
            // Preserve an already-created Phase A identity.
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
                KeyProperties.KEY_ALGORITHM_EC, KEYSTORE);
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                SIGN_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build();
        generator.initialize(spec);
        generator.generateKeyPair();
    }

    // ---------------------------------------------------------------------- ECDH

    private void loadOrCreateEcdhKey() throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            loadOrCreateKeystoreEcdhKey();
            return;
        }
        loadOrCreateSoftwareEcdhKey();
    }

    /** API 31+: PURPOSE_AGREE_KEY exists, the private key never leaves the TEE. */
    private void loadOrCreateKeystoreEcdhKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);

        if (!ks.containsAlias(ECDH_ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC, KEYSTORE);
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
        ecdhInKeystore = true;
    }

    /**
     * API 24-30: PURPOSE_AGREE_KEY does not exist in the platform, so the EC
     * private key is a software key. It is never written in plaintext: it is
     * sealed with AES-GCM under a Keystore-resident wrapping key.
     */
    private void loadOrCreateSoftwareEcdhKey() throws Exception {
        ensureWrapKey();

        String publicB64 = prefs.getString(PREF_ECDH_PUBLIC, "");
        String privateB64 = prefs.getString(PREF_ECDH_PRIVATE, "");
        String ivB64 = prefs.getString(PREF_ECDH_IV, "");

        if (publicB64.length() == 0 || privateB64.length() == 0 || ivB64.length() == 0) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair pair = generator.generateKeyPair();

            byte[] encodedPrivate = pair.getPrivate().getEncoded();
            if (encodedPrivate == null) throw new Exception("EC private key is not exportable");

            byte[][] sealed = wrap(encodedPrivate);
            Arrays.fill(encodedPrivate, (byte) 0);

            publicB64 = Base64.encodeToString(pair.getPublic().getEncoded(), Base64.NO_WRAP);
            ivB64 = Base64.encodeToString(sealed[0], Base64.NO_WRAP);
            privateB64 = Base64.encodeToString(sealed[1], Base64.NO_WRAP);

            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(PREF_ECDH_PUBLIC, publicB64);
            editor.putString(PREF_ECDH_IV, ivB64);
            editor.putString(PREF_ECDH_PRIVATE, privateB64);
            if (!editor.commit()) throw new Exception("could not persist ECDH key");
        }

        byte[] publicBytes = Base64.decode(publicB64, Base64.NO_WRAP);
        byte[] privateBytes = unwrap(
                Base64.decode(ivB64, Base64.NO_WRAP),
                Base64.decode(privateB64, Base64.NO_WRAP));

        KeyFactory kf = KeyFactory.getInstance("EC");
        ecdhPub = kf.generatePublic(new X509EncodedKeySpec(publicBytes));
        ecdhPriv = kf.generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
        Arrays.fill(privateBytes, (byte) 0);
        ecdhInKeystore = false;
    }

    private void ensureWrapKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(WRAP_ALIAS)) return;

        KeyGenerator kg = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(
                WRAP_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        kg.generateKey();
    }

    /**
     * Seal the software EC private key.
     *
     * The Keystore chooses the IV. Passing our own would throw
     * "Caller-provided IV not permitted", because randomized encryption is
     * required by default. Reading the IV back is the supported pattern and
     * keeps IND-CPA intact.
     */
    private byte[][] wrap(byte[] plain) throws Exception {
        SecretKey key = getWrapKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] ciphertext = cipher.doFinal(plain);
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != GCM_IV_BYTES) {
            throw new Exception("unexpected GCM IV length");
        }
        return new byte[][] { iv, ciphertext };
    }

    private byte[] unwrap(byte[] iv, byte[] ciphertext) throws Exception {
        if (iv == null || iv.length != GCM_IV_BYTES) throw new Exception("invalid ECDH IV");
        SecretKey key = getWrapKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        return cipher.doFinal(ciphertext);
    }

    private SecretKey getWrapKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) ks.getEntry(WRAP_ALIAS, null);
        if (entry == null) throw new Exception("ECDH wrapping key unavailable");
        return entry.getSecretKey();
    }

    /**
     * One key agreement against a throwaway peer key, at startup.
     *
     * Cost: a single P-256 operation. Value: ecdhOk() stops lying. Without this
     * the app reported "connected - encrypted" while every session silently
     * failed to establish.
     */
    private void selfTestAgreement() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair probe = generator.generateKeyPair();

        byte[] shared = agree(probe.getPublic());
        if (shared == null || shared.length == 0) throw new Exception("empty ECDH output");
        Arrays.fill(shared, (byte) 0);
    }

    /**
     * Raw ECDH. The provider must match where the private key lives: the
     * Keystore provider for a non-exportable Keystore key, the default provider
     * for a software key. Getting this wrong is an InvalidKeyException, which is
     * exactly what was breaking every private session.
     */
    private byte[] agree(PublicKey peerPublic) throws Exception {
        if (ecdhPriv == null) throw new Exception("no ECDH private key");
        if (peerPublic == null) throw new Exception("no peer ECDH key");

        KeyAgreement agreement = ecdhInKeystore
                ? KeyAgreement.getInstance("ECDH", KEYSTORE)
                : KeyAgreement.getInstance("ECDH");
        agreement.init(ecdhPriv);
        agreement.doPhase(peerPublic, true);
        return agreement.generateSecret();
    }

    private static PublicKey decodeEcPublic(String b64) throws Exception {
        if (b64 == null || b64.length() == 0) throw new Exception("empty EC public key");
        return KeyFactory.getInstance("EC").generatePublic(
                new X509EncodedKeySpec(Base64.decode(b64, Base64.NO_WRAP)));
    }

    /**
     * Shared secret for a session. Raw ECDH output: the caller MUST run it
     * through Crypto.deriveSessionKey (HKDF) and must never use it as a key.
     */
    byte[] sharedSecretForSession(String peerEcdhPublicKeyB64) throws Exception {
        if (!ok || !ecdhOk) throw new Exception("ECDH unavailable");
        return agree(decodeEcPublic(peerEcdhPublicKeyB64));
    }

    // -------------------------------------------------------------------- getters

    public boolean ok() { return ok; }
    public boolean ecdhOk() { return ecdhOk; }
    /** Diagnostic only: backing store name, or stage:ExceptionType. No secrets. */
    public String ecdhDetail() { return ecdhDetail; }
    public String id() { return id; }
    public String pubKey() { return pubB64; }
    public String ecdhPubKey() { return ecdhPubB64; }
    public String code() { return codeFor(pubB64); }

    // ----------------------------------------------------------------- identifiers

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

    // ------------------------------------------------------------------ signatures

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
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initVerify(decodeEcPublic(pkB64));
            signature.update(data.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.decode(sigB64, Base64.NO_WRAP));
        } catch (Throwable t) { return false; }
    }
}
