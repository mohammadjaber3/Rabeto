package com.rabeto.app.transport;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns every Transport and presents them to the core as one link space.
 *
 * This class is where the user's requirement lives:
 *   "if Wi-Fi is off use Bluetooth, if Bluetooth is off use Wi-Fi".
 *
 * That is deliberately NOT an if-statement inside the messaging code. It is a
 * radio state machine here: we listen for Bluetooth and Wi-Fi state changes,
 * debounce them, then start or stop each transport according to what it needs.
 * Adding a transport later means registering it, nothing else.
 */
public final class TransportManager implements TransportEvents {

    private static final String TAG = "RabetoTransport";
    private static final long DEBOUNCE_MS = 1500L;

    private final Context ctx;
    private final TransportEvents upstream;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final List<Transport> transports = new ArrayList<Transport>();
    private final Map<String, String> linkOwner = new LinkedHashMap<String, String>();
    private final Map<String, String> linkQuality = new LinkedHashMap<String, String>();
    private final Map<String, String> linkTag = new LinkedHashMap<String, String>();

    private String localId = "";
    private String localName = "";
    private boolean started = false;
    private boolean receiverRegistered = false;

    private final Runnable evaluate = new Runnable() {
        public void run() {
            evaluateRadios();
        }
    };

    private final BroadcastReceiver radioReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            // Radios flap during toggling; settle before reacting.
            handler.removeCallbacks(evaluate);
            handler.postDelayed(evaluate, DEBOUNCE_MS);
        }
    };

    public TransportManager(Context ctx, TransportEvents upstream) {
        this.ctx = ctx.getApplicationContext();
        this.upstream = upstream;
    }

    public void register(Transport transport) {
        if (transport == null) return;
        transports.add(transport);
    }

    public void setLocalTag(String id, String name) {
        this.localId = id == null ? "" : id;
        this.localName = name == null ? "" : name;
        for (int i = 0; i < transports.size(); i++) {
            transports.get(i).setLocalTag(this.localId, this.localName);
        }
    }

    public synchronized void start() {
        if (started) return;
        started = true;

        if (!receiverRegistered) {
            IntentFilter f = new IntentFilter();
            f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
            try {
                // Android 14+ wants an explicit export flag. These are protected
                // system broadcasts, so NOT_EXPORTED is correct and they are
                // still delivered. ContextCompat covers older releases.
                ContextCompat.registerReceiver(ctx, radioReceiver, f,
                        ContextCompat.RECEIVER_NOT_EXPORTED);
                receiverRegistered = true;
            } catch (Throwable t) {
                Log.w(TAG, "radio receiver: " + t.getMessage());
            }
        }

        evaluateRadios();
    }

    public synchronized void stop() {
        started = false;
        handler.removeCallbacks(evaluate);

        if (receiverRegistered) {
            try { ctx.unregisterReceiver(radioReceiver); } catch (Throwable ignored) {}
            receiverRegistered = false;
        }

        for (int i = 0; i < transports.size(); i++) {
            Transport t = transports.get(i);
            try { t.stop(); } catch (Throwable ignored) {}
        }

        linkOwner.clear();
        linkQuality.clear();
        linkTag.clear();
    }

    /**
     * Start what can run, stop what cannot. Safe to call repeatedly.
     */
    public synchronized void evaluateRadios() {
        if (!started) return;

        for (int i = 0; i < transports.size(); i++) {
            Transport t = transports.get(i);

            boolean wanted;
            try {
                wanted = t.isSupported() && t.isRadioReady();
            } catch (Throwable e) {
                wanted = false;
            }

            try {
                if (wanted && !t.isRunning()) {
                    t.setLocalTag(localId, localName);
                    t.start();
                    upstream.onTransportState(t.name(), true, t.stateDetail());
                } else if (!wanted && t.isRunning()) {
                    t.stop();
                    dropLinksOf(t.name());
                    upstream.onTransportState(t.name(), false, t.stateDetail());
                }
            } catch (Throwable e) {
                Log.w(TAG, t.name() + " evaluate failed: " + e.getMessage());
            }
        }
    }

    /** Called by the core when the user renames themselves. */
    public synchronized void restart() {
        for (int i = 0; i < transports.size(); i++) {
            Transport t = transports.get(i);
            try { if (t.isRunning()) t.stop(); } catch (Throwable ignored) {}
        }
        linkOwner.clear();
        linkQuality.clear();
        linkTag.clear();
        evaluateRadios();
    }

    public synchronized boolean send(String linkId, byte[] packet) {
        Transport t = ownerOf(linkId);
        if (t == null) return false;
        try {
            return t.send(linkId, packet);
        } catch (Throwable e) {
            Log.w(TAG, "send failed on " + linkId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Fan a packet out to every link except one.
     *
     * @return number of links the packet was handed to. This is a transport
     *         attempt count, never a delivery count.
     */
    public synchronized int broadcast(byte[] packet, String excludeLinkId) {
        int sent = 0;
        List<String> ids = new ArrayList<String>(linkOwner.keySet());
        for (int i = 0; i < ids.size(); i++) {
            String linkId = ids.get(i);
            if (excludeLinkId != null && excludeLinkId.equals(linkId)) continue;
            if (send(linkId, packet)) sent++;
        }
        return sent;
    }

    public synchronized List<String> links() {
        return new ArrayList<String>(linkOwner.keySet());
    }

    public synchronized int linkCount() {
        return linkOwner.size();
    }

    public synchronized String qualityOf(String linkId) {
        String q = linkQuality.get(linkId);
        return q == null ? "bt" : q;
    }

    public synchronized String tagOf(String linkId) {
        String t = linkTag.get(linkId);
        return t == null ? "" : t;
    }

    /** Radio snapshot for the UI. */
    public boolean bluetoothOn() {
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            return a != null && a.isEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean wifiOn() {
        try {
            WifiManager w = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            return w != null && w.isWifiEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    public synchronized List<String> runningTransports() {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < transports.size(); i++) {
            Transport t = transports.get(i);
            try { if (t.isRunning()) out.add(t.name()); } catch (Throwable ignored) {}
        }
        return out;
    }

    private Transport ownerOf(String linkId) {
        String owner = linkOwner.get(linkId);
        if (owner == null) return null;
        for (int i = 0; i < transports.size(); i++) {
            if (owner.equals(transports.get(i).name())) return transports.get(i);
        }
        return null;
    }

    private void dropLinksOf(String transportName) {
        List<String> ids = new ArrayList<String>(linkOwner.keySet());
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            if (transportName.equals(linkOwner.get(id))) {
                linkOwner.remove(id);
                linkQuality.remove(id);
                linkTag.remove(id);
                upstream.onLinkDown(transportName, id);
            }
        }
    }

    // ------------------------------------------------------------- upward events

    @Override
    public void onLinkUp(String transport, String linkId, String advertisedTag) {
        synchronized (this) {
            linkOwner.put(linkId, transport);
            linkTag.put(linkId, advertisedTag == null ? "" : advertisedTag);
        }
        upstream.onLinkUp(transport, linkId, advertisedTag);
    }

    @Override
    public void onLinkDown(String transport, String linkId) {
        synchronized (this) {
            linkOwner.remove(linkId);
            linkQuality.remove(linkId);
            linkTag.remove(linkId);
        }
        upstream.onLinkDown(transport, linkId);
    }

    @Override
    public void onLinkQuality(String transport, String linkId, String quality) {
        synchronized (this) {
            linkQuality.put(linkId, quality);
        }
        upstream.onLinkQuality(transport, linkId, quality);
    }

    @Override
    public void onPacket(String transport, String linkId, byte[] packet) {
        upstream.onPacket(transport, linkId, packet);
    }

    @Override
    public void onTransportState(String transport, boolean running, String detail) {
        upstream.onTransportState(transport, running, detail);
    }
}
