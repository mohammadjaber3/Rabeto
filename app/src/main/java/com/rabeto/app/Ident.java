package com.rabeto.app;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.security.KeyStore;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * هویت امن Rabeto.
 *
 * کلید خصوصی داخل Android Keystore ساخته و نگهداری می‌شود
 * و قابل استخراج به صورت bytes نیست.
 *
 * کلید عمومی آزادانه قابل انتشار است و برای:
 * - شناسه کاربر
 * - امضای پیام
 * - توافق کلید ECDH
 * استفاده می‌شود.
 */
public class Ident {

    private static final String KEY_ALIAS = "rabeto_identity_ec_v1";

    private boolean ok;
    private PrivateKey priv;
    private PublicKey pub;
    private String pubB64 = "";
    private String id = "";

    private final SecureRandom rnd = new SecureRandom();
    private final Map<String, byte[]> secretCache =
            new HashMap<String, byte[]>();

    public Ident(SharedPreferences prefs) {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);

            if (!ks.containsAlias(KEY_ALIAS)) {
                createIdentityKey();
            }

            KeyStore.Entry entry = ks.getEntry(KEY_ALIAS, null);

            if (!(entry instanceof KeyStore.PrivateKeyEntry)) {
                throw new Exception("Rabeto identity key is not a private key entry");
            }

            KeyStore.PrivateKeyEntry privateEntry =
                    (KeyStore.PrivateKeyEntry) entry;

            priv = privateEntry.getPrivateKey();
            pub = privateEntry.getCertificate().getPublicKey();

            if (priv == null || pub == null) {
                throw new Exception("Rabeto identity keys are unavailable");
            }

            pubB64 = Base64.encodeToString(
                    pub.getEncoded(),
                    Base64.NO_WRAP
            );

            id = idFor(pubB64);
            ok = id.length() > 0;

        } catch (Throwable t) {
            ok = false;
            priv = null;
            pub = null;
            pubB64 = "";
            id = "";
        }
    }

    /**
     * ساخت کلید EC داخل Android Keystore.
     *
     * PURPOSE_SIGN:
     *   برای امضای پیام‌ها
     *
     * PURPOSE_AGREE_KEY:
     *   برای ECDH و ساخت کلید مشترک
     */
    private static void createIdentityKey() throws Exception {

        KeyPairGenerator generator =
                KeyPairGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_EC,
                        "AndroidKeyStore"
                );

        KeyGenParameterSpec spec =
                new KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_SIGN
                                | KeyProperties.PURPOSE_AGREE_KEY
                )
                        .setAlgorithmParameterSpec(
                                new ECGenParameterSpec("secp256r1")
                        )
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build();

        generator.initialize(spec);
        generator.generateKeyPair();
    }

    public boolean ok() {
        return ok;
    }

    public String id() {
        return id;
    }

    public String pubKey() {
        return pubB64;
    }

    public String code() {
        return codeFor(pubB64);
    }

    // -------------------------------------------------------------
    // fingerprint
    // -------------------------------------------------------------

    private static byte[] hash(String pkB64) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(
                            Base64.decode(
                                    pkB64,
                                    Base64.NO_WRAP
                            )
                    );
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * شناسه کاربر:
     * ۱۰ رقم hexadecimal از اثرانگشت کلید عمومی.
     */
    public static String idFor(String pkB64) {

        byte[] h = hash(pkB64);

        if (h == null || h.length < 16) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < 5; i++) {
            sb.append(
                    String.format(
                            Locale.US,
                            "%02x",
                            h[i] & 0xff
                    )
            );
        }

        return sb.toString();
    }

    /**
     * کد تأیید انسانی:
     * ۸ رقم برای مقایسه حضوری یا تلفنی.
     */
    public static String codeFor(String pkB64) {

        byte[] h = hash(pkB64);

        if (h == null || h.length < 16) {
            return "00000000";
        }

        long v = 0;

        for (int i = 5; i < 11; i++) {
            v = (v << 8) | (h[i] & 0xffL);
        }

        v = v % 100000000L;

        String s = String.format(
                Locale.US,
                "%08d",
                v
        );

        return s.substring(0, 4)
                + " "
                + s.substring(4);
    }

    // -------------------------------------------------------------
    // signatures
    // -------------------------------------------------------------

    public String sign(String data) {

        if (!ok || priv == null || data == null) {
            return "";
        }

        try {

            Signature signature =
                    Signature.getInstance("SHA256withECDSA");

            signature.initSign(priv);

            signature.update(
                    data.getBytes(StandardCharsets.UTF_8)
            );

            return Base64.encodeToString(
                    signature.sign(),
                    Base64.NO_WRAP
            );

        } catch (Throwable t) {
            return "";
        }
    }

    public static boolean verify(
            String pkB64,
            String data,
            String sigB64
    ) {

        if (pkB64 == null
                || sigB64 == null
                || data == null
                || pkB64.length() == 0
                || sigB64.length() == 0) {

            return false;
        }

        try {

            KeyFactory kf =
                    KeyFactory.getInstance("EC");

            PublicKey pk =
                    kf.generatePublic(
                            new X509EncodedKeySpec(
                                    Base64.decode(
                                            pkB64,
                                            Base64.NO_WRAP
                                    )
                            )
                    );

            Signature signature =
                    Signature.getInstance("SHA256withECDSA");

            signature.initVerify(pk);

            signature.update(
                    data.getBytes(StandardCharsets.UTF_8)
            );

            return signature.verify(
                    Base64.decode(
                            sigB64,
                            Base64.NO_WRAP
                    )
            );

        } catch (Throwable t) {
            return false;
        }
    }

    // -------------------------------------------------------------
    // ECDH
    // -------------------------------------------------------------

    private byte[] secret(String pkB64) {

        if (pkB64 == null || pkB64.length() == 0) {
            return null;
        }

        byte[] cached = secretCache.get(pkB64);

        if (cached != null) {
            return cached;
        }

        try {

            KeyFactory kf =
                    KeyFactory.getInstance("EC");

            PublicKey theirs =
                    kf.generatePublic(
                            new X509EncodedKeySpec(
                                    Base64.decode(
                                            pkB64,
                                            Base64.NO_WRAP
                                    )
                            )
                    );

            KeyAgreement agreement =
                    KeyAgreement.getInstance("ECDH");

            agreement.init(priv);

            agreement.doPhase(theirs, true);

            byte[] raw =
                    agreement.generateSecret();

            /*
             * فعلاً برای سازگاری با پروتکل فعلی:
             * SHA-256(ECDH shared secret)
             *
             * در مرحله بعدی پروتکل رمزنگاری را به HKDF
             * با context/version مشخص ارتقا می‌دهیم.
             */
            byte[] key =
                    MessageDigest.getInstance("SHA-256")
                            .digest(raw);

            secretCache.put(pkB64, key);

            return key;

        } catch (Throwable t) {
            return null;
        }
    }

    // -------------------------------------------------------------
    // AES-GCM
    // -------------------------------------------------------------

    /**
     * خروجی:
     *
     * ivBase64:ciphertextBase64
     */
    public String encrypt(
            String theirPubB64,
            String plain
    ) {

        if (!ok || plain == null) {
            return "";
        }

        byte[] key = secret(theirPubB64);

        if (key == null) {
            return "";
        }

        try {

            byte[] iv = new byte[12];
            rnd.nextBytes(iv);

            Cipher cipher =
                    Cipher.getInstance(
                            "AES/GCM/NoPadding"
                    );

            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, iv)
            );

            byte[] ciphertext =
                    cipher.doFinal(
                            plain.getBytes(StandardCharsets.UTF_8)
                    );

            return Base64.encodeToString(
                            iv,
                            Base64.NO_WRAP
                    )
                    + ":"
                    + Base64.encodeToString(
                            ciphertext,
                            Base64.NO_WRAP
                    );

        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * ورودی:
     *
     * ivBase64:ciphertextBase64
     *
     * خروجی:
     * متن اصلی یا null.
     */
    public String decrypt(
            String theirPubB64,
            String blob
    ) {

        if (!ok || blob == null) {
            return null;
        }

        int separator = blob.indexOf(':');

        if (separator < 1) {
            return null;
        }

        byte[] key = secret(theirPubB64);

        if (key == null) {
            return null;
        }

        try {

            byte[] iv =
                    Base64.decode(
                            blob.substring(0, separator),
                            Base64.NO_WRAP
                    );

            byte[] ciphertext =
                    Base64.decode(
                            blob.substring(separator + 1),
                            Base64.NO_WRAP
                    );

            if (iv.length != 12) {
                return null;
            }

            Cipher cipher =
                    Cipher.getInstance(
                            "AES/GCM/NoPadding"
                    );

            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, iv)
            );

            byte[] plain =
                    cipher.doFinal(ciphertext);

            return new String(
                    plain,
                    StandardCharsets.UTF_8
            );

        } catch (Throwable t) {
            return null;
        }
    }
}
