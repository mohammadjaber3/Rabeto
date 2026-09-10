package com.rabeto.app;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-peer session-key cache. The identity private key stays inside Ident/Keystore.
 * This phase is intentionally not advertised as forward secrecy; ephemeral/ratcheting
 * sessions are a separate protocol phase.
 */
public final class SessionManager {
    private static final int MAX_SESSIONS = 2048;

    private final Ident identity;
    private final String localId;
    private final Map<String, byte[]> sessions = new HashMap<String, byte[]>();

    public SessionManager(Ident identity) {
        this.identity = identity;
        this.localId = identity.id();
    }

    public synchronized boolean establish(String peerId, String peerPublicKey) {
        if (!identity.ok() || peerId == null || peerPublicKey == null) return false;
        if (!peerId.equals(Ident.idFor(peerPublicKey))) return false;
        try {
            byte[] shared = identity.sharedSecretForSession(peerPublicKey);
            byte[] key = Crypto.deriveSessionKey(shared, localId, peerId);
            Arrays.fill(shared, (byte) 0);
            byte[] old = sessions.put(peerId, key);
            if (old != null) Arrays.fill(old, (byte) 0);
            trim();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public synchronized String encrypt(String peerId, String plaintext, String aad) {
        byte[] key = sessions.get(peerId);
        if (key == null || plaintext == null) return "";
        try {
            return Crypto.encrypt(key, plaintext,
                    aad == null ? null : aad.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return "";
        }
    }

    public synchronized String decrypt(String peerId, String blob, String aad) {
        byte[] key = sessions.get(peerId);
        if (key == null || blob == null) return null;
        try {
            return Crypto.decrypt(key, blob,
                    aad == null ? null : aad.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return null;
        }
    }

    public synchronized void remove(String peerId) {
        byte[] old = sessions.remove(peerId);
        if (old != null) Arrays.fill(old, (byte) 0);
    }

    public synchronized void clear() {
        for (byte[] key : sessions.values()) Arrays.fill(key, (byte) 0);
        sessions.clear();
    }

    public synchronized boolean has(String peerId) {
        return sessions.containsKey(peerId);
    }

    public synchronized int size() {
        return sessions.size();
    }

    private void trim() {
        while (sessions.size() > MAX_SESSIONS) {
            String first = sessions.keySet().iterator().next();
            byte[] key = sessions.remove(first);
            if (key != null) Arrays.fill(key, (byte) 0);
        }
    }
}
