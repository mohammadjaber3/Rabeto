package com.rabeto.app;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;

/**
 * Test-only identity. Real identities live in the Android Keystore, which does
 * not exist on the JVM, so tests use a plain JCE EC keypair. Ident.idFor,
 * Ident.verify, Protocol and Crypto are all static/JCE and behave identically.
 */
public final class TestKeys {

    public final KeyPair pair;
    public final KeyPair ecdhPair;
    public final String publicKeyB64;
    public final String ecdhPublicKeyB64;
    public final String id;

    public TestKeys() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        pair = g.generateKeyPair();
        ecdhPair = g.generateKeyPair();
        publicKeyB64 = Base64.encodeToString(
                pair.getPublic().getEncoded(), Base64.NO_WRAP);
        ecdhPublicKeyB64 = Base64.encodeToString(
                ecdhPair.getPublic().getEncoded(), Base64.NO_WRAP);
        id = Ident.idFor(publicKeyB64);
    }

    public String sign(String data) throws Exception {
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(pair.getPrivate());
        s.update(data.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(s.sign(), Base64.NO_WRAP);
    }
}
