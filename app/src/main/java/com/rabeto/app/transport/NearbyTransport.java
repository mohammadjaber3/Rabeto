package com.rabeto.app.transport;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.BandwidthInfo;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionOptions;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionType;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Google Nearby Connections transport.
 *
 * This is the code that used to live inside Mesh.java, moved behind the
 * Transport interface with no behavioural change: same service id, same
 * DISRUPTIVE connection type, same lexicographic tie-break so two peers do not
 * both dial each other, same retry-on-failure loop.
 *
 * It stays the primary transport because it handles the BT to Wi-Fi Direct
 * upgrade for us. It is no longer the ONLY transport, which was the real
 * problem: it needs Google Play Services, and a decentralised messenger cannot
 * have a hard Google dependency.
 */
public final class NearbyTransport implements Transport {

    private static final String TAG = "RabetoNearby";
    private static final String SERVICE_ID = "com.rabeto.mesh.v6";
    public static final String NAME = "nearby";

    private final Context ctx;
    private final TransportEvents events;
    private final ConnectionsClient client;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** endpointId -> advertised tag */
    private final Map<String, String> found = new LinkedHashMap<String, String>();
    private final Map<String, String> connected = new LinkedHashMap<String, String>();

    private String localId = "";
    private String localName = "";
    private boolean running = false;
    private boolean fastMode = false;
    private String detail = "خاموش";

    public NearbyTransport(Context ctx, TransportEvents events) {
        this.ctx = ctx.getApplicationContext();
        this.events = events;
        this.client = Nearby.getConnectionsClient(this.ctx);
    }

    /** P2P_STAR trades mesh topology for Wi-Fi Direct bandwidth. */
    public void setFastMode(boolean fast) {
        if (this.fastMode == fast) return;
        this.fastMode = fast;
        if (running) {
            stop();
            start();
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isSupported() {
        try {
            ctx.getPackageManager().getPackageInfo("com.google.android.gms", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean isRadioReady() {
        // Nearby can work over Bluetooth alone or Wi-Fi alone.
        return btOn() || wifiOn();
    }

    @Override
    public void setLocalTag(String id, String name) {
        this.localId = id == null ? "" : id;
        this.localName = name == null ? "" : name;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        if (localId.length() == 0) {
            detail = "هویت آماده نیست";
            return;
        }
        running = true;
        detail = "در حال جست‌وجو";
        advertise();
        discover();
    }

    @Override
    public synchronized void stop() {
        running = false;
        detail = "خاموش";
        try { client.stopAllEndpoints(); } catch (Throwable ignored) {}
        try { client.stopAdvertising(); } catch (Throwable ignored) {}
        try { client.stopDiscovery(); } catch (Throwable ignored) {}

        List<String> gone = new ArrayList<String>(connected.keySet());
        connected.clear();
        found.clear();
        for (int i = 0; i < gone.size(); i++) {
            events.onLinkDown(NAME, linkId(gone.get(i)));
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public synchronized boolean send(String linkId, byte[] packet) {
        String endpoint = endpointOf(linkId);
        if (endpoint == null || !connected.containsKey(endpoint)) return false;
        if (packet == null || packet.length == 0 || packet.length > maxPacketBytes()) return false;
        try {
            client.sendPayload(endpoint, Payload.fromBytes(packet));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "send failed: " + t.getMessage());
            return false;
        }
    }

    @Override
    public synchronized List<String> links() {
        List<String> out = new ArrayList<String>();
        List<String> eps = new ArrayList<String>(connected.keySet());
        for (int i = 0; i < eps.size(); i++) out.add(linkId(eps.get(i)));
        return out;
    }

    @Override
    public int maxPacketBytes() {
        // Nearby byte payloads cap out around 1 MiB; the protocol cap is stricter.
        return 256 * 1024;
    }

    @Override
    public String stateDetail() {
        return detail;
    }

    // ------------------------------------------------------------------ internals

    private String linkId(String endpointId) {
        return NAME + ":" + endpointId;
    }

    private String endpointOf(String linkId) {
        if (linkId == null) return null;
        String prefix = NAME + ":";
        if (!linkId.startsWith(prefix)) return null;
        return linkId.substring(prefix.length());
    }

    private Strategy strategy() {
        return fastMode ? Strategy.P2P_STAR : Strategy.P2P_CLUSTER;
    }

    private String localTag() {
        return localId + "|" + localName;
    }

    private boolean btOn() {
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            return a != null && a.isEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean wifiOn() {
        try {
            WifiManager w = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            return w != null && w.isWifiEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    private void advertise() {
        AdvertisingOptions o = new AdvertisingOptions.Builder()
                .setStrategy(strategy())
                .setConnectionType(ConnectionType.DISRUPTIVE)
                .setLowPower(false)
                .build();

        client.startAdvertising(localTag(), SERVICE_ID, lifecycle, o)
                .addOnSuccessListener(new com.google.android.gms.tasks.OnSuccessListener<Void>() {
                    public void onSuccess(Void v) {
                        detail = "قابل مشاهده";
                        events.onTransportState(NAME, true, detail);
                    }
                })
                .addOnFailureListener(new com.google.android.gms.tasks.OnFailureListener() {
                    public void onFailure(Exception e) {
                        Log.w(TAG, "advertise failed: " + e.getMessage());
                        if (running) handler.postDelayed(new Runnable() {
                            public void run() { if (running) advertise(); }
                        }, 6000);
                    }
                });
    }

    private void discover() {
        DiscoveryOptions o = new DiscoveryOptions.Builder()
                .setStrategy(strategy())
                .setLowPower(false)
                .build();

        client.startDiscovery(SERVICE_ID, discovery, o)
                .addOnFailureListener(new com.google.android.gms.tasks.OnFailureListener() {
                    public void onFailure(Exception e) {
                        Log.w(TAG, "discover failed: " + e.getMessage());
                        if (running) handler.postDelayed(new Runnable() {
                            public void run() { if (running) discover(); }
                        }, 6000);
                    }
                });
    }

    private final EndpointDiscoveryCallback discovery = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(String endpointId, DiscoveredEndpointInfo info) {
            final String ep = endpointId;
            String tag = info.getEndpointName();
            synchronized (NearbyTransport.this) {
                found.put(ep, tag == null ? "" : tag);
            }
            // Only the lexicographically smaller id dials, so we never create
            // two half-open connections for one peer pair.
            String peerId = tag == null ? "" : tag.split("\\|", 2)[0];
            if (localId.compareTo(peerId) < 0) connectTo(ep);

            // Fallback: if the other side never dialled, we dial after a delay.
            handler.postDelayed(new Runnable() {
                public void run() {
                    synchronized (NearbyTransport.this) {
                        if (running && !connected.containsKey(ep) && found.containsKey(ep)) {
                            connectTo(ep);
                        }
                    }
                }
            }, 12000);
        }

        @Override
        public void onEndpointLost(String endpointId) {
            synchronized (NearbyTransport.this) {
                found.remove(endpointId);
            }
        }
    };

    private void connectTo(String endpointId) {
        try {
            ConnectionOptions o = new ConnectionOptions.Builder()
                    .setConnectionType(ConnectionType.DISRUPTIVE)
                    .setLowPower(false)
                    .build();
            client.requestConnection(localTag(), endpointId, lifecycle, o)
                    .addOnFailureListener(new com.google.android.gms.tasks.OnFailureListener() {
                        public void onFailure(Exception e) {
                            Log.w(TAG, "connect: " + e.getMessage());
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "connect exception: " + t.getMessage());
        }
    }

    private final ConnectionLifecycleCallback lifecycle = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(String endpointId, ConnectionInfo info) {
            synchronized (NearbyTransport.this) {
                String tag = info.getEndpointName();
                found.put(endpointId, tag == null ? "" : tag);
            }
            // Rabeto authenticates at the protocol layer with signed Hello, so
            // accepting the raw transport connection is safe and keyless here.
            client.acceptConnection(endpointId, payloads);
        }

        @Override
        public void onConnectionResult(String endpointId, ConnectionResolution res) {
            if (res.getStatus().getStatusCode() != ConnectionsStatusCodes.STATUS_OK) {
                Log.w(TAG, "connection result " + res.getStatus().getStatusCode());
                return;
            }
            String tag;
            synchronized (NearbyTransport.this) {
                tag = found.containsKey(endpointId) ? found.get(endpointId) : endpointId;
                connected.put(endpointId, tag);
                detail = connected.size() + " اتصال";
            }
            events.onLinkUp(NAME, linkId(endpointId), tag);
        }

        @Override
        public void onDisconnected(String endpointId) {
            synchronized (NearbyTransport.this) {
                connected.remove(endpointId);
                detail = connected.size() + " اتصال";
            }
            events.onLinkDown(NAME, linkId(endpointId));
        }

        @Override
        public void onBandwidthChanged(String endpointId, BandwidthInfo info) {
            String quality = "bt";
            try {
                if (info.getQuality() >= BandwidthInfo.Quality.MEDIUM) quality = "wifi";
            } catch (Throwable ignored) {}
            events.onLinkQuality(NAME, linkId(endpointId), quality);
        }
    };

    private final PayloadCallback payloads = new PayloadCallback() {
        @Override
        public void onPayloadReceived(String endpointId, Payload payload) {
            byte[] bytes = payload.asBytes();
            if (bytes == null || bytes.length == 0) return;
            events.onPacket(NAME, linkId(endpointId), bytes);
        }

        @Override
        public void onPayloadTransferUpdate(String endpointId, PayloadTransferUpdate update) {
            // Transport progress is not delivery. Delivery is the ACK protocol's job.
        }
    };
}
