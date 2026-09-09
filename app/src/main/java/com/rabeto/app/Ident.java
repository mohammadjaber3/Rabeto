package com.rabeto.app;

import android.content.SharedPreferences;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
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
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * هویت کاربر در رابطو.
 * هر گوشی در اولین اجرا یک «جفت کلید» می‌سازد (کلید خصوصی که هیچکس نمی‌د��ند، کلید عمومی که همه می‌دانند).
 * پیام خصوصی با کلید مشترک (ECDH) رمز می‌شود => گوشی‌های واسطه نمی‌توانند بخوانند.
 */
public class Ident {
    private boolean ok;
    private PrivateKey priv;
    private PublicKey pub;
    private String pubB64 = "";
    private String id = "";
    private final SecureRandom rnd = new SecureRandom();
    private final Map<String, byte[]> secretCache = new HashMap<String, byte[]>();

    public Ident(SharedPreferences prefs) {
        try {
            String p = prefs.getString("k.priv", null);
            String q = prefs.getString("k.pub", null);
            if (p != null && q != null) {
                KeyFactory kf = KeyFactory.getInstance("EC");
                priv = kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(p, Base64.NO_WRAP)));
                pub = kf.generatePublic(new X509EncodedKeySpec(Base64.decode(q, Base64.NO_WRAP)));
            } else {
                KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
                g.initialize(new ECGenParameterSpec("secp256r1"));
                KeyPair kp = g.generateKeyPair();
                priv = kp.getPrivate();
                pub = kp.getPublic();
                prefs.edit()
                        .putString("k.priv", Base64.encodeToString(priv.getEncoded(), Base64.NO_WRAP))
                        .putString("k.pub", Base64.encodeToString(pub.getEncoded(), Base64.NO_WRAP))
                        .apply();
            }
            pubB64 = Base64.encodeToString(pub.getEncoded(), Base64.NO_WRAP);
            id = idFor(pubB64);
            ok = (id != null && id.length() > 0);
        } catch (Throwable t) {
            ok = false;
        }
    }

    public boolean ok() { return ok; }
    public String id() { return id; }
    public String pubKey() { return pubB64; }
    public String code() { return codeFor(pubB64); }

    // ------------------------------------------------------------- fingerprint
    private static byte[] hash(String pkB64) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(Base64.decode(pkB64, Base64.NO_WRAP));
        } catch (Throwable t) {
            return null;
        }
    }

    /** شناسهٔ کاربر = ۱۰ رقم شانزده‌شانزدهی از اثرانگشت کلید عمومی */
    public static String idFor(String pkB64) {
        byte[] h = hash(pkB64);
        if (h == null || h.length < 16) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) sb.append(String.format(Locale.US, "%02x", h[i] & 0xff));
        return sb.toString();
    }

    /** کد تأیید انسانی: ۸ رقم، برای خواندن پشت تلفن یا رو در رو */
    public static String codeFor(String pkB64) {
        byte[] h = hash(pkB64);
        if (h == null || h.length < 16) return "00000000";
        long v = 0;
        for (int i = 5; i < 11; i++) v = (v << 8) | (h[i] & 0xffL);
        v = v % 100000000L;
        String s = String.format(Locale.US, "%08d", v);
        return s.substring(0, 4) + " " + s.substring(4);
    }

    // ------------------------------------------------------------- signatures
    public String sign(String data) {
        if (!ok) return "";
        try {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initSign(priv);
            s.update(data.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(s.sign(), Base64.NO_WRAP);
        } catch (Throwable t) {
            return "";
        }
    }

    public static boolean verify(String pkB64, String data, String sigB64) {
        if (pkB64 == null || sigB64 == null || pkB64.length() == 0 || sigB64.length() == 0) return false;
        try {
            KeyFactory kf = KeyFactory.getInstance("EC");
            PublicKey pk = kf.generatePublic(new X509EncodedKeySpec(Base64.decode(pkB64, Base64.NO_WRAP)));
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initVerify(pk);
            s.update(data.getBytes(StandardCharsets.UTF_8));
            return s.verify(Base64.decode(sigB64, Base64.NO_WRAP));
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------- encryption
    private byte[] secret(String pkB64) {
        byte[] c = secretCache.get(pkB64);
        if (c != null) return c;
        try {
            KeyFactory kf = KeyFactory.getInstance("EC");
            PublicKey theirs = kf.generatePublic(new X509EncodedKeySpec(Base64.decode(pkB64, Base64.NO_WRAP)));
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(priv);
            ka.doPhase(theirs, true);
            byte[] raw = ka.generateSecret();
            byte[] key = MessageDigest.getInstance("SHA-256").digest(raw);
            secretCache.put(pkB64, key);
            return key;
        } catch (Throwable t) {
            return null;
        }
    }

    /** خروجی: "ivBase64:ciphertextBase64"  — یا رشتهٔ خالی اگر نشد */
    public String encrypt(String theirPubB64, String plain) {
        if (!ok || plain == null) return "";
        byte[] key = secret(theirPubB64);
        if (key == null) return "";
        try {
            byte[] iv = new byte[12];
            rnd.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP);
        } catch (Throwable t) {
            return "";
        }
    }

    /** ورودی: "ivBase64:ciphertextBase64" — خروجی متن، یا null اگر باز نشد */
    public String decrypt(String theirPubB64, String blob) {
        if (!ok || blob == null) return null;
        int i = blob.indexOf(':');
        if (i < 1) return null;
        byte[] key = secret(theirPubB64);
        if (key == null) return null;
        try {
            byte[] iv = Base64.decode(blob.substring(0, i), Base64.NO_WRAP);
            byte[] ct = Base64.decode(blob.substring(i + 1), Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
