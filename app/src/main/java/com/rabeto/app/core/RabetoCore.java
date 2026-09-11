package com.rabeto.app.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.rabeto.app.Crypto;
import com.rabeto.app.Ident;
import com.rabeto.app.Protocol;
import com.rabeto.app.SessionManager;
import com.rabeto.app.mesh.RateLimiter;
import com.rabeto.app.mesh.SeenCache;
import com.rabeto.app.storage.MessageStore;
import com.rabeto.app.storage.RoomMessageStore;
import com.rabeto.app.storage.StoredMessage;
import com.rabeto.app.transport.BleTransport;
import com.rabeto.app.transport.NearbyTransport;
import com.rabeto.app.transport.TransportEvents;
import com.rabeto.app.transport.TransportManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Rabeto engine.
 *
 * This is Mesh.java with two things surgically removed: the WebView and the
 * Activity. It now knows nothing about how it is displayed and nothing about
 * which radio carries its bytes. It talks to a TransportManager below and emits
 * CoreEvents above.
 *
 * That single change is what makes offline delivery possible at all: the engine
 * lives in a foreground Service, so discovery, relaying and store-and-forward
 * keep running when the user closes the app.
 *
 * Threading: transport callbacks arrive on transport threads; every mutation of
 * shared state happens inside synchronized blocks on this object. Storage is
 * always asynchronous and never touched from a callback thread.
 */
public final class RabetoCore implements TransportEvents {

    private static final String TAG = "RabetoCore";
    private static final int SEEN_MAX = 4000;
    private static final int RELAY_MAX_ROWS = 4000;
    private static final int FLUSH_BATCH = 120;

    private final Context ctx;
    private final CoreEvents events;
    private final SharedPreferences prefs;

    private final Ident ident;
    private final SessionManager sessions;
    private final MessageStore store;
    private final TransportManager transports;
    private final NearbyTransport nearby;
    private final BleTransport ble;

    private final SeenCache seen = new SeenCache(SEEN_MAX);
    private final RateLimiter limiter = new RateLimiter();

    /** authenticated peerId -> linkId */
    private final Map<String, String> peerLinks = new LinkedHashMap<String, String>();
    /** linkId -> authenticated peerId */
    private final Map<String, String> linkPeers = new LinkedHashMap<String, String>();

    private final Map<String, String> pubKeys = new HashMap<String, String>();
    private final Map<String, String> ecdhPubKeys = new HashMap<String, String>();
    private final Map<String, String> names = new HashMap<String, String>();
    private final Map<String, String> avatars = new HashMap<String, String>();

    private String myId;
    private String myName;
    private String myAvatar;
    private boolean running = false;

    public RabetoCore(Context context, CoreEvents events) {
        this.ctx = context.getApplicationContext();
        this.events = events;
        this.prefs = ctx.getSharedPreferences("rabeto", Context.MODE_PRIVATE);
        this.ident = new Ident(prefs);
        this.sessions = new SessionManager(ident);
        this.store = new RoomMessageStore(ctx);

        this.transports = new TransportManager(ctx, this);
        this.nearby = new NearbyTransport(ctx, transports);
        this.ble = new BleTransport(ctx, transports);

        transports.register(nearby);
        // BLE is new code on a fragile stack. Opt-in until it is proven on
        // hardware, so it cannot regress the working Nearby path.
        if (prefs.getBoolean("bleEnabled", false)) {
            transports.register(ble);
        }

        if (ident.ok()) {
            myId = ident.id();
        } else {
            // Identity is broken (no Keystore, locked device policy...). We still
            // boot so the user sees an error instead of a blank screen, but every
            // send path checks ident.ok() and refuses.
            myId = prefs.getString("fallbackId", null);
            if (myId == null) {
                myId = Long.toHexString(System.nanoTime()).substring(0, 10);
                prefs.edit().putString("fallbackId", myId).apply();
            }
        }

        myName = prefs.getString("myName", "کاربر " + myId.substring(0, 3));
        myAvatar = prefs.getString("myAvatar", "");

        loadState();
        nearby.setFastMode(prefs.getBoolean("fastMode", false));
        transports.setLocalTag(myId, myName);
    }

    // ------------------------------------------------------------------ lifecycle

    public synchronized void start() {
        if (running) return;
        running = true;
        transports.setLocalTag(myId, myName);
        transports.start();
        emitStatus();
    }

    public synchronized void stop() {
        running = false;
        transports.stop();
        sessions.clear();
        peerLinks.clear();
        linkPeers.clear();
        emitStatus();
    }

    public void close() {
        stop();
        store.close();
    }

    public boolean isRunning() {
        return running;
    }

    /** Periodic maintenance. Driven by the Service, never by the UI. */
    public void tick() {
        final long now = System.currentTimeMillis();

        store.markExpired(now, null);
        store.deleteExpiredRelay(now, null);
        store.trimRelay(RELAY_MAX_ROWS, null);

        flushQueue(MessageStore.ROLE_OUTBOX, now);
        flushQueue(MessageStore.ROLE_RELAY, now);

        emitStatus();
    }

    // -------------------------------------------------------------- transport in

    @Override
    public void onLinkUp(String transport, String linkId, String advertisedTag) {
        // The advertised tag is UNAUTHENTICATED. We do not trust it, we do not
        // persist it, and we do not create a contact from it. It only tells us
        // to introduce ourselves.
        sendHello(linkId);
        emitStatus();
    }

    @Override
    public void onLinkDown(String transport, String linkId) {
        synchronized (this) {
            String peer = linkPeers.remove(linkId);
            if (peer != null && linkId.equals(peerLinks.get(peer))) {
                peerLinks.remove(peer);
            }
        }
        emitStatus();
    }

    @Override
    public void onLinkQuality(String transport, String linkId, String quality) {
        emitStatus();
    }

    @Override
    public void onTransportState(String transport, boolean running, String detail) {
        try {
            JSONObject o = new JSONObject();
            o.put("transport", transport);
            o.put("running", running);
            o.put("detail", detail);
            events.onCoreEvent("transport", o);
        } catch (Throwable ignored) {}
        emitStatus();
    }

    @Override
    public void onPacket(String transport, String linkId, byte[] packet) {
        if (!Protocol.validPacketSize(packet)) return;
        try {
            JSONObject o = new JSONObject(new String(packet, StandardCharsets.UTF_8));
            String t = o.optString("t", "msg");
            if ("hello".equals(t)) handleHello(linkId, o);
            else if ("msg".equals(t)) handleIncoming(linkId, o, packet.length);
        } catch (Throwable e) {
            Log.w(TAG, "bad packet on " + linkId);
        }
    }

    // ------------------------------------------------------------------ handshake

    private void sendHello(String linkId) {
        try {
            if (!ident.ok() || !ident.ecdhOk()) return;

            JSONObject o = new JSONObject();
            o.put("t", "hello");
            o.put("v", Protocol.VERSION);
            o.put("id", myId);
            o.put("name", myName);
            o.put("av", myAvatar);
            o.put("pk", ident.pubKey());
            o.put("epk", ident.ecdhPubKey());
            o.put("nonce", Protocol.newNonce());
            o.put("sig", ident.sign(Protocol.canonicalHello(o)));

            byte[] packet = o.toString().getBytes(StandardCharsets.UTF_8);
            if (!Protocol.validPacketSize(packet)) return;

            transports.send(linkId, packet);
        } catch (Throwable ignored) {}
    }

    private void handleHello(String linkId, JSONObject o) {
        if (!Protocol.validHello(o)) return;

        String uid = o.optString("id", "");
        String nm = o.optString("name", uid);
        String pk = o.optString("pk", "");
        String epk = o.optString("epk", "");
        String sig = o.optString("sig", "");

        if (uid.length() == 0 || uid.equals(myId)) return;

        // Authenticate the announcement before trusting or persisting anything.
        if (!Ident.verify(pk, Protocol.canonicalHello(o), sig)) return;

        int keyState = learnKey(uid, pk, epk);
        if (keyState == -1) return;

        if (keyState == -2) {
            sessions.remove(uid);
            emitSecurity("identity_changed", uid);
            return;
        }

        if (keyState != 1 || !sessions.establish(uid, epk)) return;

        synchronized (this) {
            names.put(uid, nm);
            String av = o.optString("av", "");
            if (av.length() > 0) avatars.put(uid, av);
            peerLinks.put(uid, linkId);
            linkPeers.put(linkId, uid);
        }

        saveState();
        emitStatus();
        emitContacts();

        // Authenticated and keyed. Only now is it safe to hand this peer our
        // queued traffic.
        flushToLink(linkId);
    }

    /**
     * Trust on first use. The key is pinned; a later change is a loud warning,
     * never a silent swap.
     *
     * @return 1 known/learned, 0 empty, -1 forged id, -2 key changed
     */
    private int learnKey(String uid, String pk, String epk) {
        if (pk == null || pk.length() == 0 || epk == null || epk.length() == 0) return 0;
        if (!uid.equals(Ident.idFor(pk))) return -1;
        synchronized (this) {
            String hadPk = pubKeys.get(uid);
            String hadEpk = ecdhPubKeys.get(uid);
            if (hadPk == null || hadEpk == null) {
                pubKeys.put(uid, pk);
                ecdhPubKeys.put(uid, epk);
                return 1;
            }
            if (!hadPk.equals(pk) || !hadEpk.equals(epk)) return -2;
        }
        return 1;
    }

    // -------------------------------------------------------------------- inbound

    private void handleIncoming(String fromLink, JSONObject env, int packetBytes) {
        long now = System.currentTimeMillis();

        if (!Protocol.validEnvelope(env, now)) return;

        String mid = env.optString("id", "");
        String from = env.optString("from", "");
        String pk = env.optString("pk", "");
        String epk = env.optString("epk", "");

        if (seen.seen(mid)) return;

        // Cheap checks first, then rate limit, then the expensive signature
        // verify. Doing this in the wrong order is how a flood attack turns into
        // a CPU and battery attack.
        if (!limiter.allow(from, packetBytes, now)) {
            Log.w(TAG, "rate limited " + from);
            return;
        }

        if (!Ident.verify(pk, Protocol.canonicalMessage(env), env.optString("sig", ""))) {
            return;
        }

        int keyState = Protocol.BROADCAST.equals(env.optString("to", ""))
                ? learnBroadcastKey(from, pk, epk)
                : learnKey(from, pk, epk);
        if (keyState == -1) return;
        if (keyState == -2) {
            sessions.remove(from);
            emitSecurity("identity_changed", from);
            return;
        }
        if (keyState != 1) return;
        if (!Protocol.BROADCAST.equals(env.optString("to", ""))
                && !sessions.establish(from, epk)) return;

        // Only authenticated traffic enters replay state or the relay cache.
        if (!seen.remember(mid)) return;

        boolean isNewContact;
        synchronized (this) {
            isNewContact = !names.containsKey(from);
            String nm = env.optString("name", "");
            if (nm.length() > 0 && !from.equals(myId)) names.put(from, nm);
            if (fromLink != null && !linkPeers.containsKey(fromLink)) {
                linkPeers.put(fromLink, from);
                peerLinks.put(from, fromLink);
            }
        }
        if (isNewContact) {
            saveState();
            emitContacts();
        }

        String to = env.optString("to", Protocol.BROADCAST);
        String kind = env.optString("kind", "");
        boolean direct = myId.equals(to);
        boolean broadcast = Protocol.BROADCAST.equals(to);
        boolean forMe = direct || broadcast;

        String plain = null;
        if (direct && env.optInt("enc", 0) == 1) {
            plain = sessions.decrypt(from, env.optString("text", ""), Protocol.aad(env));
            if (plain == null) {
                // Authenticated sender, undecryptable body: almost always a stale
                // session after a key change. Drop it and force a re-handshake
                // rather than showing the user a broken message.
                sessions.remove(from);
                emitSecurity("session_stale", from);
                return;
            }
        } else if (broadcast) {
            plain = env.optString("text", "");
        }

        // Protocol control traffic never becomes conversation history.
        if (direct && Protocol.KIND_ACK.equals(kind)) {
            handleAck(plain);
            return;
        }
        if (direct && Protocol.KIND_READ.equals(kind)) {
            handleRead(plain);
            return;
        }

        if (forMe && !myId.equals(from)) {
            persist(env, plain, MessageStore.ROLE_INBOX, MessageStore.STATUS_DELIVERED);
            emitMessage(env, plain, from, to, false);

            if (direct) {
                // Delivery is confirmed by us, to the sender, encrypted.
                sendControl(from, Protocol.KIND_ACK, mid);
            }
        }

        // Traffic for somebody else: carry it. This is store-and-forward, and it
        // is the only reason an offline recipient can ever be reached.
        if (!myId.equals(from) && !direct && !broadcast) {
            persist(env, null, MessageStore.ROLE_RELAY, MessageStore.STATUS_QUEUED);
        }

        // Forward anything not addressed exclusively to us.
        if (!direct) forward(env, fromLink);
    }

    private void handleAck(final String messageId) {
        if (messageId == null || messageId.length() == 0) return;
        store.transitionStatus(messageId, MessageStore.ROLE_OUTBOX,
                MessageStore.STATUS_QUEUED, MessageStore.STATUS_DELIVERED,
                new MessageStore.ResultCallback<Boolean>() {
                    @Override
                    public void onResult(Boolean ok) {
                        emitStatusChange(messageId, MessageStore.STATUS_DELIVERED);
                    }
                });
    }

    private void handleRead(final String messageId) {
        if (messageId == null || messageId.length() == 0) return;

        // Normally read follows delivered. But a read receipt can overtake an
        // ACK on a lossy mesh, so we also accept the queued -> read jump instead
        // of dropping the receipt and leaving a single tick forever.
        store.transitionStatus(messageId, MessageStore.ROLE_OUTBOX,
                MessageStore.STATUS_DELIVERED, MessageStore.STATUS_READ,
                new MessageStore.ResultCallback<Boolean>() {
                    @Override
                    public void onResult(Boolean ok) {
                        if (ok != null && ok.booleanValue()) {
                            emitStatusChange(messageId, MessageStore.STATUS_READ);
                            return;
                        }
                        store.transitionStatus(messageId, MessageStore.ROLE_OUTBOX,
                                MessageStore.STATUS_QUEUED, MessageStore.STATUS_READ,
                                new MessageStore.ResultCallback<Boolean>() {
                                    @Override
                                    public void onResult(Boolean second) {
                                        emitStatusChange(messageId,
                                                MessageStore.STATUS_READ);
                                    }
                                });
                    }
                });
    }

    /**
     * Broadcasts authenticate the signing identity and distribute the
     * public ECDH key needed for later private-message sessions.
     *
     * Both keys are public and the envelope signature binds them together.
     */
    private int learnBroadcastKey(String uid, String pk, String epk) {
        if (pk == null || pk.length() == 0
                || epk == null || epk.length() == 0
                || !uid.equals(Ident.idFor(pk))) {
            return -1;
        }

        synchronized (this) {
            String hadPk = pubKeys.get(uid);
            String hadEpk = ecdhPubKeys.get(uid);

            if (hadPk == null || hadEpk == null) {
                pubKeys.put(uid, pk);
                ecdhPubKeys.put(uid, epk);
                return 1;
            }

            if (!hadPk.equals(pk) || !hadEpk.equals(epk)) return -2;
        }

        return 1;
    }

    // ------------------------------------------------------------------- outbound

    /**
     * Queue a text message.
     *
     * @return the message id, or a draft id when the recipient's key is not
     *         known yet, or "" on hard failure.
     */
    public String sendText(String to, String text) {
        if (to == null || to.length() == 0 || text == null || text.length() == 0) return "";
        if (text.length() > Protocol.MAX_TEXT_CHARS) return "";
        if (!ident.ok()) {
            emitSecurity("identity_unavailable", myId);
            return "";
        }

        if (Protocol.BROADCAST.equals(to)) {
            return sendEnvelope(to, Protocol.KIND_TEXT, text, false);
        }

        String theirKey;
        String theirEpk;
        synchronized (this) {
            theirKey = pubKeys.get(to);
            theirEpk = ecdhPubKeys.get(to);
        }

        if (theirKey == null || theirKey.length() == 0
                || theirEpk == null || theirEpk.length() == 0) {
            // Never degrade a private message to plaintext. Park it locally and
            // materialise it once both identity keys are available.
            return saveDraft(to, text);
        }

        return sendEnvelope(to, Protocol.KIND_TEXT, text, true);
    }

    public String sendBroadcast(String text) {
        return sendText(Protocol.BROADCAST, text);
    }

    /** Encrypted control message: ack, read receipt. Never stored as history. */
    private void sendControl(String to, String kind, String payload) {
        if (to == null || to.length() == 0) return;
        synchronized (this) {
            if (!pubKeys.containsKey(to)) return;
        }
        try {
            JSONObject env = buildEnvelope(to, kind, payload, true);
            if (env == null) return;
            route(env, null);
        } catch (Throwable ignored) {}
    }

    private String sendEnvelope(String to, String kind, String text, boolean encrypt) {
        try {
            JSONObject env = buildEnvelope(to, kind, text, encrypt);
            if (env == null) return "";

            String mid = env.optString("id", "");
            seen.remember(mid);
            persist(env, text, MessageStore.ROLE_OUTBOX, MessageStore.STATUS_QUEUED);
            route(env, null);
            emitMessage(env, text, myId, to, true);
            return mid;
        } catch (Throwable t) {
            Log.w(TAG, "send failed: " + t.getMessage());
            return "";
        }
    }

    /**
     * Build a signed envelope. Returns null instead of degrading to plaintext:
     * a private message that cannot be encrypted must not be sent at all.
     */
    private JSONObject buildEnvelope(String to, String kind, String text, boolean encrypt)
            throws Exception {

        JSONObject e = new JSONObject();
        String mid = Crypto.randomId(myId);
        String body = text == null ? "" : text;

        e.put("t", "msg");
        e.put("v", Protocol.VERSION);
        e.put("id", mid);
        e.put("from", myId);
        e.put("name", myName);
        e.put("to", to);
        e.put("kind", kind);
        e.put("ts", System.currentTimeMillis());
        e.put("enc", 0);
        e.put("nonce", Protocol.newNonce());
        e.put("text", body);
        e.put("data", "");
        e.put("pk", ident.pubKey());
        e.put("epk", ident.ecdhPubKey());
        e.put("ttl", Protocol.MAX_TTL);
        e.put("hops", 0);

        if (encrypt && !Protocol.BROADCAST.equals(to)) {
            String theirEpk;
            synchronized (this) {
                theirEpk = ecdhPubKeys.get(to);
            }
            if (theirEpk == null || theirEpk.length() == 0) return null;
            if (!sessions.has(to) && !sessions.establish(to, theirEpk)) return null;

            e.put("enc", 1);
            String aad = Protocol.aad(e);
            String sealed = sessions.encrypt(to, body, aad);
            if (sealed == null || sealed.length() == 0) return null;
            e.put("text", sealed);
        }

        e.put("sig", ident.sign(Protocol.canonicalMessage(e)));
        if (e.optString("sig", "").length() == 0) return null;

        // Never emit a packet our own validator would reject.
        if (!Protocol.validEnvelope(e, System.currentTimeMillis())) {
            Log.w(TAG, "refusing to send an envelope that fails local validation");
            return null;
        }
        return e;
    }

    /**
     * Routing, phase A: unicast over the peer's own link when we have one,
     * flood otherwise. This is already better than blind flooding for the common
     * "two phones in a room" case, and it is the seam where Phase B plugs in
     * neighbour tables and link-quality metrics.
     */
    private int route(JSONObject env, String skipLink) {
        byte[] packet;
        try {
            packet = env.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return 0;
        }
        if (!Protocol.validPacketSize(packet)) return 0;

        String to = env.optString("to", Protocol.BROADCAST);
        if (!Protocol.BROADCAST.equals(to)) {
            String link;
            synchronized (this) {
                link = peerLinks.get(to);
            }
            if (link != null && transports.send(link, packet)) return 1;
        }
        return transports.broadcast(packet, skipLink);
    }

    private void forward(JSONObject env, String fromLink) {
        int ttl = env.optInt("ttl", 0);
        int hops = env.optInt("hops", 0);
        if (ttl <= 0 || hops >= Protocol.MAX_HOPS) return;

        try {
            JSONObject fwd = new JSONObject(env.toString());
            fwd.put("ttl", ttl - 1);
            fwd.put("hops", hops + 1);
            route(fwd, fromLink);
        } catch (Throwable ignored) {}
    }

    // ---------------------------------------------------------------- persistence

    private void persist(JSONObject env, String plainText, int role, int status) {
        if (env == null) return;

        String mid = env.optString("id", "");
        if (mid.length() == 0) return;

        long createdAt = env.optLong("ts", System.currentTimeMillis());
        long expiresAt = createdAt + Protocol.MESSAGE_TTL_MS;

        StoredMessage row = new StoredMessage(
                mid,
                env.optString("from", ""),
                env.optString("to", ""),
                env.toString(),
                createdAt,
                expiresAt,
                role,
                status);

        // Relay rows stay opaque on purpose: we carry strangers' traffic, we do
        // not keep a readable copy of it.
        row.plainText = role == MessageStore.ROLE_RELAY ? null : plainText;

        store.put(row, null);
    }

    private String saveDraft(String to, String text) {
        String draftId = Crypto.randomId("d");
        long now = System.currentTimeMillis();

        JSONObject draft = new JSONObject();
        try {
            draft.put("t", "draft");
            draft.put("id", draftId);
            draft.put("from", myId);
            draft.put("to", to);
            draft.put("kind", Protocol.KIND_TEXT);
            draft.put("ts", now);
        } catch (Throwable ignored) {}

        StoredMessage row = new StoredMessage(
                draftId, myId, to, draft.toString(),
                now, now + Protocol.MESSAGE_TTL_MS,
                MessageStore.ROLE_OUTBOX, MessageStore.STATUS_QUEUED);
        row.plainText = text;

        store.put(row, null);
        emitDraft(draftId, to, text, now);
        return draftId;
    }

    /**
     * Retry loop for anything still queued.
     *
     * A transport hand-off is an attempt, never a delivery. Rows stay queued
     * until an authenticated ACK moves them, which is exactly why the store
     * tracks attempts and backoff separately from status.
     */
    private void flushQueue(final int role, final long now) {
        store.active(role, now, FLUSH_BATCH,
                new MessageStore.ResultCallback<List<StoredMessage>>() {
                    @Override
                    public void onResult(List<StoredMessage> rows) {
                        if (rows == null || rows.isEmpty()) return;
                        for (int i = 0; i < rows.size(); i++) {
                            sendStored(rows.get(i), null);
                        }
                    }
                });
    }

    private void flushToLink(final String linkId) {
        if (linkId == null || linkId.length() == 0) return;
        final long now = System.currentTimeMillis();

        MessageStore.ResultCallback<List<StoredMessage>> cb =
                new MessageStore.ResultCallback<List<StoredMessage>>() {
                    @Override
                    public void onResult(List<StoredMessage> rows) {
                        if (rows == null) return;
                        for (int i = 0; i < rows.size(); i++) {
                            sendStored(rows.get(i), linkId);
                        }
                    }
                };

        store.active(MessageStore.ROLE_OUTBOX, now, FLUSH_BATCH, cb);
        store.active(MessageStore.ROLE_RELAY, now, FLUSH_BATCH, cb);
    }

    private void sendStored(StoredMessage row, String preferredLink) {
        if (row == null || row.envelopeJson == null) return;

        try {
            JSONObject env = new JSONObject(row.envelopeJson);

            // A parked draft becomes a real message as soon as we learn the key.
            if ("draft".equals(env.optString("t"))) {
                materialiseDraft(row, env);
                return;
            }

            long now = System.currentTimeMillis();
            if (!Protocol.validEnvelope(env, now)) {
                store.delete(row.messageId, row.role, null);
                return;
            }
            if (env.optInt("ttl", 0) <= 0
                    || env.optInt("hops", 0) >= Protocol.MAX_HOPS) {
                return;
            }

            byte[] packet = env.toString().getBytes(StandardCharsets.UTF_8);
            if (!Protocol.validPacketSize(packet)) return;

            boolean sent;
            if (preferredLink != null) {
                sent = transports.send(preferredLink, packet);
            } else {
                sent = route(env, null) > 0;
            }

            if (sent) {
                store.recordAttempt(row.messageId, row.role,
                        System.currentTimeMillis(), null);
            }
        } catch (Throwable t) {
            Log.w(TAG, "stored send failed: " + t.getMessage());
        }
    }

    private void materialiseDraft(StoredMessage row, JSONObject draft) {
        final String to = draft.optString("to", "");
        final String text = row.plainText;
        if (to.length() == 0 || text == null || text.length() == 0) {
            store.delete(row.messageId, row.role, null);
            return;
        }

        synchronized (this) {
            if (!pubKeys.containsKey(to) || !ecdhPubKeys.containsKey(to)) return; // still waiting for the keys
        }

        final String draftId = row.messageId;
        String mid = sendEnvelope(to, Protocol.KIND_TEXT, text, true);
        if (mid.length() > 0) {
            store.delete(draftId, MessageStore.ROLE_OUTBOX, null);
            try {
                JSONObject o = new JSONObject();
                o.put("draftId", draftId);
                o.put("messageId", mid);
                events.onCoreEvent("draftSent", o);
            } catch (Throwable ignored) {}
        }
    }

    // -------------------------------------------------------------------- queries

    public JSONObject identity() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", myId);
            o.put("name", myName);
            o.put("avatar", myAvatar);
            o.put("code", ident.ok() ? ident.code() : "");
            o.put("secure", ident.ok());
            o.put("ecdh", ident.ecdhOk());
            o.put("protocol", Protocol.VERSION);
        } catch (Throwable ignored) {}
        return o;
    }

    public JSONObject status() {
        JSONObject o = new JSONObject();
        try {
            List<String> links = transports.links();
            JSONArray peers = new JSONArray();
            synchronized (this) {
                for (Map.Entry<String, String> e : peerLinks.entrySet()) {
                    JSONObject p = new JSONObject();
                    p.put("id", e.getKey());
                    p.put("name", names.containsKey(e.getKey())
                            ? names.get(e.getKey()) : e.getKey());
                    p.put("quality", transports.qualityOf(e.getValue()));
                    p.put("transport", e.getValue().split(":", 2)[0]);
                    peers.put(p);
                }
            }
            o.put("running", running);
            o.put("links", links.size());
            o.put("peers", peers);
            o.put("bluetooth", transports.bluetoothOn());
            o.put("wifi", transports.wifiOn());
            o.put("transports", new JSONArray(transports.runningTransports()));
            o.put("sessions", sessions.size());
            o.put("seen", seen.size());
        } catch (Throwable ignored) {}
        return o;
    }

    public JSONArray contacts() {
        JSONArray out = new JSONArray();
        synchronized (this) {
            for (Map.Entry<String, String> e : names.entrySet()) {
                if (myId.equals(e.getKey())) continue;
                try {
                    JSONObject c = new JSONObject();
                    c.put("id", e.getKey());
                    c.put("name", e.getValue());
                    c.put("avatar", avatars.containsKey(e.getKey())
                            ? avatars.get(e.getKey()) : "");
                    c.put("online", peerLinks.containsKey(e.getKey()));
                    c.put("keyed", pubKeys.containsKey(e.getKey())
                            && ecdhPubKeys.containsKey(e.getKey()));
                    String pk = pubKeys.get(e.getKey());
                    c.put("code", pk == null ? "" : Ident.codeFor(pk));
                    out.put(c);
                } catch (Throwable ignored) {}
            }
        }
        return out;
    }

    public void conversation(final String peerId, int limit,
                             final MessageStore.ResultCallback<JSONArray> callback) {
        store.conversation(peerId, limit,
                new MessageStore.ResultCallback<List<StoredMessage>>() {
                    @Override
                    public void onResult(List<StoredMessage> rows) {
                        callback.onResult(rowsToJson(rows));
                    }
                });
    }

    public void recent(int limit, final MessageStore.ResultCallback<JSONArray> callback) {
        store.recent(limit, new MessageStore.ResultCallback<List<StoredMessage>>() {
            @Override
            public void onResult(List<StoredMessage> rows) {
                callback.onResult(rowsToJson(rows));
            }
        });
    }

    private JSONArray rowsToJson(List<StoredMessage> rows) {
        JSONArray out = new JSONArray();
        if (rows == null) return out;
        for (int i = 0; i < rows.size(); i++) {
            StoredMessage r = rows.get(i);
            if (r == null) continue;
            try {
                JSONObject env = r.envelopeJson == null
                        ? new JSONObject() : new JSONObject(r.envelopeJson);
                JSONObject m = new JSONObject();
                m.put("id", r.messageId);
                m.put("from", r.senderId);
                m.put("to", r.recipientId);
                m.put("mine", myId.equals(r.senderId));
                m.put("text", r.plainText == null ? "" : r.plainText);
                m.put("kind", env.optString("kind", Protocol.KIND_TEXT));
                m.put("ts", r.createdAt);
                m.put("status", r.status);
                m.put("draft", "draft".equals(env.optString("t")));
                m.put("name", nameOf(r.senderId));
                out.put(m);
            } catch (Throwable ignored) {}
        }
        return out;
    }

    /**
     * Mark a thread read and tell the sender.
     *
     * A read receipt must reference the ids of the messages WE received, because
     * those are the ids sitting in the sender's outbox. Sending our own peer id
     * or our own message ids would produce receipts the other side can never
     * match, which is a silently broken feature rather than a missing one.
     *
     * Receipts are capped so opening a thread with a thousand unread messages
     * does not turn into a thousand packets on a Bluetooth link.
     */
    public void markThreadRead(final String peerId) {
        if (peerId == null || peerId.length() == 0) return;

        store.conversation(peerId, 200,
                new MessageStore.ResultCallback<List<StoredMessage>>() {
                    @Override
                    public void onResult(List<StoredMessage> rows) {
                        List<String> ids = new ArrayList<String>();
                        if (rows != null) {
                            for (int i = 0; i < rows.size(); i++) {
                                StoredMessage r = rows.get(i);
                                if (r == null) continue;
                                if (r.role != MessageStore.ROLE_INBOX) continue;
                                if (!peerId.equals(r.senderId)) continue;
                                if (r.status >= MessageStore.STATUS_READ) continue;
                                ids.add(r.messageId);
                            }
                        }

                        store.markThreadRead(peerId, null);

                        int cap = Math.min(ids.size(), 20);
                        for (int i = ids.size() - cap; i < ids.size(); i++) {
                            sendControl(peerId, Protocol.KIND_READ, ids.get(i));
                        }
                    }
                });
    }

    // -------------------------------------------------------------------- profile

    public void setProfile(String name, String avatar) {
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.length() == 0) return;
        if (cleanName.length() > Protocol.MAX_NAME_CHARS) {
            cleanName = cleanName.substring(0, Protocol.MAX_NAME_CHARS);
        }
        String cleanAvatar = avatar == null ? "" : avatar;
        if (cleanAvatar.length() > Protocol.MAX_DATA_CHARS) cleanAvatar = "";

        synchronized (this) {
            myName = cleanName;
            myAvatar = cleanAvatar;
        }
        prefs.edit().putString("myName", cleanName)
                .putString("myAvatar", cleanAvatar).apply();

        transports.setLocalTag(myId, cleanName);
        if (running) transports.restart();
        events.onCoreEvent("identity", identity());
    }

    public void setFastMode(boolean fast) {
        prefs.edit().putBoolean("fastMode", fast).apply();
        nearby.setFastMode(fast);
    }

    public boolean bleEnabled() {
        return prefs.getBoolean("bleEnabled", false);
    }

    /**
     * Toggling BLE re-registers a transport, so it takes effect on the next
     * service start. Returns true when a restart is required.
     */
    public boolean setBleEnabled(boolean enabled) {
        boolean was = bleEnabled();
        prefs.edit().putBoolean("bleEnabled", enabled).apply();
        return was != enabled;
    }

    public String verificationCode() {
        return ident.ok() ? ident.code() : "";
    }

    // ------------------------------------------------------------------ emitters

    private String nameOf(String id) {
        if (id == null) return "";
        if (id.equals(myId)) return myName;
        synchronized (this) {
            String n = names.get(id);
            return n == null ? id : n;
        }
    }

    private void emitStatus() {
        events.onCoreEvent("status", status());
    }

    private void emitContacts() {
        try {
            JSONObject o = new JSONObject();
            o.put("contacts", contacts());
            events.onCoreEvent("contacts", o);
        } catch (Throwable ignored) {}
    }

    private void emitMessage(JSONObject env, String plain, String from,
                             String to, boolean mine) {
        try {
            JSONObject m = new JSONObject();
            m.put("id", env.optString("id", ""));
            m.put("from", from);
            m.put("to", to);
            m.put("mine", mine);
            m.put("text", plain == null ? "" : plain);
            m.put("kind", env.optString("kind", Protocol.KIND_TEXT));
            m.put("ts", env.optLong("ts", System.currentTimeMillis()));
            m.put("name", nameOf(from));
            m.put("verified", true);
            m.put("encrypted", env.optInt("enc", 0) == 1);
            m.put("status", mine ? MessageStore.STATUS_QUEUED
                    : MessageStore.STATUS_DELIVERED);
            events.onCoreEvent("message", m);
        } catch (Throwable ignored) {}
    }

    private void emitDraft(String id, String to, String text, long ts) {
        try {
            JSONObject m = new JSONObject();
            m.put("id", id);
            m.put("from", myId);
            m.put("to", to);
            m.put("mine", true);
            m.put("draft", true);
            m.put("text", text);
            m.put("kind", Protocol.KIND_TEXT);
            m.put("ts", ts);
            m.put("status", MessageStore.STATUS_QUEUED);
            events.onCoreEvent("message", m);
        } catch (Throwable ignored) {}
    }

    private void emitStatusChange(String messageId, int status) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", messageId);
            o.put("status", status);
            events.onCoreEvent("messageStatus", o);
        } catch (Throwable ignored) {}
    }

    private void emitSecurity(String reason, String peerId) {
        try {
            JSONObject o = new JSONObject();
            o.put("reason", reason);
            o.put("peer", peerId);
            o.put("name", nameOf(peerId));
            events.onCoreEvent("security", o);
        } catch (Throwable ignored) {}
    }

    public void reportError(String code) {
        try {
            JSONObject o = new JSONObject();
            o.put("code", code);
            events.onCoreEvent("error", o);
        } catch (Throwable ignored) {}
    }

    // ---------------------------------------------------------- trust store (v1)

    /**
     * Pinned keys, names and avatars live in SharedPreferences for now.
     *
     * They are public values, so this is not a secret leak, but it IS the trust
     * store and it deserves integrity protection. Phase B moves it into Room
     * with a Keystore-derived MAC so a rooted-device attacker cannot silently
     * repin a contact's key.
     */
    private void loadState() {
        try {
            JSONObject o = new JSONObject(prefs.getString("state", "{}"));

            JSONObject keys = o.optJSONObject("pubKeys");
            if (keys != null) copyInto(keys, pubKeys);
            JSONObject ekeys = o.optJSONObject("ecdhPubKeys");
            if (ekeys != null) copyInto(ekeys, ecdhPubKeys);

            JSONObject nm = o.optJSONObject("names");
            if (nm != null) copyInto(nm, names);

            JSONObject av = o.optJSONObject("avatars");
            if (av != null) copyInto(av, avatars);
        } catch (Throwable ignored) {}
    }

    private void copyInto(JSONObject src, Map<String, String> dst) {
        java.util.Iterator<String> it = src.keys();
        while (it.hasNext()) {
            String k = it.next();
            String v = src.optString(k, "");
            if (v.length() > 0) dst.put(k, v);
        }
    }

    private void saveState() {
        try {
            JSONObject o = new JSONObject();
            synchronized (this) {
                o.put("pubKeys", new JSONObject(new HashMap<String, String>(pubKeys)));
                o.put("ecdhPubKeys", new JSONObject(new HashMap<String, String>(ecdhPubKeys)));
                o.put("names", new JSONObject(new HashMap<String, String>(names)));
                o.put("avatars", new JSONObject(new HashMap<String, String>(avatars)));
            }
            prefs.edit().putString("state", o.toString()).apply();
        } catch (Throwable ignored) {}
    }
}
