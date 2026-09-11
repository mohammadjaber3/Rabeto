package com.rabeto.app.transport;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Raw Bluetooth Low Energy transport built directly on GATT.
 *
 * Why this exists: Nearby Connections requires Google Play Services. A
 * censorship-resistant, decentralised messenger cannot depend on a single
 * vendor's proprietary layer being installed. This transport uses nothing but
 * the Android Bluetooth stack, so Rabeto keeps working on de-Googled ROMs and
 * on devices where Play Services is disabled or blocked.
 *
 * It also gives us location-free discovery: BLUETOOTH_SCAN is declared with
 * neverForLocation, so on Android 12+ the app finds peers with Location off.
 *
 * Design:
 *  - Every device runs BOTH a GATT server (advertising) and a scanner/client.
 *  - Advertisement carries the 128-bit Rabeto service UUID; the scan response
 *    carries the 5-byte identity fingerprint. Splitting them is required: both
 *    together do not fit in one 31-byte advertising PDU.
 *  - Only the lexicographically smaller identity dials, so a peer pair produces
 *    one link, not two.
 *  - Packets are length-prefixed and chunked to (MTU - 3). BLE allows a single
 *    outstanding write per connection, so every link has its own send queue
 *    drained by the write/notify completion callback. Skipping that queue is the
 *    classic reason hand-rolled BLE transports silently drop data.
 *
 * Status: shipped as OPT-IN in Phase A. It is new code touching a notoriously
 * fragile stack, and it must not be able to break the working Nearby path
 * before it has been tested on real hardware.
 */
public final class BleTransport implements Transport {

    private static final String TAG = "RabetoBle";
    public static final String NAME = "ble";

    public static final UUID SERVICE_UUID =
            UUID.fromString("7ab0e1c4-9a5e-4f61-9d33-52a1c0b17e01");
    /** Peers write inbound packets here. */
    public static final UUID RX_UUID =
            UUID.fromString("7ab0e1c4-9a5e-4f61-9d33-52a1c0b17e02");
    /** We notify outbound packets here. */
    public static final UUID TX_UUID =
            UUID.fromString("7ab0e1c4-9a5e-4f61-9d33-52a1c0b17e03");
    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int DEFAULT_MTU = 23;
    private static final int WANTED_MTU = 517;
    private static final int MAX_LINKS = 12;
    private static final long DIAL_FALLBACK_MS = 15000L;

    private final Context ctx;
    private final TransportEvents events;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Map<String, BluetoothGatt> clientLinks =
            Collections.synchronizedMap(new LinkedHashMap<String, BluetoothGatt>());
    private final Map<String, BluetoothDevice> serverLinks =
            Collections.synchronizedMap(new LinkedHashMap<String, BluetoothDevice>());
    private final Map<String, Integer> mtus =
            Collections.synchronizedMap(new HashMap<String, Integer>());
    private final Map<String, String> peerTags =
            Collections.synchronizedMap(new HashMap<String, String>());
    private final Map<String, Framing.Reassembler> inbound =
            Collections.synchronizedMap(new HashMap<String, Framing.Reassembler>());
    private final Map<String, SendQueue> outbound =
            Collections.synchronizedMap(new HashMap<String, SendQueue>());
    private final Map<String, Long> dialing =
            Collections.synchronizedMap(new HashMap<String, Long>());

    private BluetoothAdapter adapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;
    private BluetoothGattServer server;
    private BluetoothGattCharacteristic txCharacteristic;

    private String localId = "";
    private String localName = "";
    private volatile boolean running = false;
    private String detail = "خاموش";

    public BleTransport(Context ctx, TransportEvents events) {
        this.ctx = ctx.getApplicationContext();
        this.events = events;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isSupported() {
        try {
            if (!ctx.getPackageManager()
                    .hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
                return false;
            }
            BluetoothAdapter a = defaultAdapter();
            if (a == null) return false;
            // Peripheral mode is optional on Android. Without it we can still
            // scan and dial, but we cannot be found, which halves the mesh.
            return a.isMultipleAdvertisementSupported() || a.isEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean isRadioReady() {
        BluetoothAdapter a = defaultAdapter();
        return a != null && a.isEnabled() && hasPermissions();
    }

    @Override
    public void setLocalTag(String id, String name) {
        this.localId = id == null ? "" : id;
        this.localName = name == null ? "" : name;
    }

    @SuppressLint("MissingPermission")
    @Override
    public synchronized void start() {
        if (running) return;
        if (localId.length() < 10) {
            detail = "هویت آماده نیست";
            return;
        }
        adapter = defaultAdapter();
        if (adapter == null || !adapter.isEnabled() || !hasPermissions()) {
            detail = "بلوتوث آماده نیست";
            return;
        }

        running = true;
        detail = "در حال جست‌وجو";

        if (!startGattServer()) {
            Log.w(TAG, "gatt server unavailable, continuing as client only");
        }
        startAdvertising();
        startScanning();
    }

    @SuppressLint("MissingPermission")
    @Override
    public synchronized void stop() {
        running = false;
        detail = "خاموش";

        try {
            if (advertiser != null) advertiser.stopAdvertising(advertiseCallback);
        } catch (Throwable ignored) {}
        try {
            if (scanner != null) scanner.stopScan(scanCallback);
        } catch (Throwable ignored) {}

        List<String> gone = new ArrayList<String>();
        synchronized (clientLinks) {
            gone.addAll(clientLinks.keySet());
            for (BluetoothGatt g : clientLinks.values()) {
                try { g.disconnect(); } catch (Throwable ignored) {}
                try { g.close(); } catch (Throwable ignored) {}
            }
            clientLinks.clear();
        }
        synchronized (serverLinks) {
            for (String a : serverLinks.keySet()) if (!gone.contains(a)) gone.add(a);
            serverLinks.clear();
        }

        try {
            if (server != null) {
                server.clearServices();
                server.close();
            }
        } catch (Throwable ignored) {}

        server = null;
        advertiser = null;
        scanner = null;
        txCharacteristic = null;

        mtus.clear();
        peerTags.clear();
        inbound.clear();
        outbound.clear();
        dialing.clear();

        for (int i = 0; i < gone.size(); i++) {
            events.onLinkDown(NAME, linkId(gone.get(i)));
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean send(String linkId, byte[] packet) {
        String address = addressOf(linkId);
        if (address == null || packet == null || packet.length == 0) return false;
        if (packet.length > maxPacketBytes()) return false;
        if (!clientLinks.containsKey(address) && !serverLinks.containsKey(address)) {
            return false;
        }

        byte[] framed = Framing.frame(packet);

        SendQueue queue = queueFor(address);
        queue.enqueue(framed, chunkSize(address));
        queue.pump();
        return true;
    }

    @Override
    public List<String> links() {
        List<String> out = new ArrayList<String>();
        synchronized (clientLinks) {
            for (String a : clientLinks.keySet()) out.add(linkId(a));
        }
        synchronized (serverLinks) {
            for (String a : serverLinks.keySet()) {
                String id = linkId(a);
                if (!out.contains(id)) out.add(id);
            }
        }
        return out;
    }

    @Override
    public int maxPacketBytes() {
        // BLE is slow. Anything bigger belongs to the chunked attachment engine.
        return 32 * 1024;
    }

    @Override
    public String stateDetail() {
        return detail;
    }

    // -------------------------------------------------------------- advertising

    @SuppressLint("MissingPermission")
    private void startAdvertising() {
        try {
            advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) {
                Log.w(TAG, "no advertiser: peripheral mode unsupported");
                return;
            }

            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                    .setConnectable(true)
                    .setTimeout(0)
                    .build();

            ParcelUuid pu = new ParcelUuid(SERVICE_UUID);

            // 31-byte budget: flags (3) + 128-bit service UUID (18) = 21.
            AdvertiseData data = new AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    .addServiceUuid(pu)
                    .build();

            // Second 31-byte budget: service data with the identity fingerprint.
            AdvertiseData response = new AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .addServiceData(pu, Framing.hexToBytes(localId))
                    .build();

            advertiser.startAdvertising(settings, data, response, advertiseCallback);
        } catch (Throwable t) {
            Log.w(TAG, "advertise failed: " + t.getMessage());
        }
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            detail = "قابل مشاهده";
            events.onTransportState(NAME, true, detail);
        }

        @Override
        public void onStartFailure(int errorCode) {
            Log.w(TAG, "advertise error " + errorCode);
            detail = "پخش ناموفق (" + errorCode + ")";
            events.onTransportState(NAME, running, detail);
        }
    };

    // ----------------------------------------------------------------- scanning

    @SuppressLint("MissingPermission")
    private void startScanning() {
        try {
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) return;

            List<ScanFilter> filters = new ArrayList<ScanFilter>();
            filters.add(new ScanFilter.Builder()
                    .setServiceUuid(new ParcelUuid(SERVICE_UUID))
                    .build());

            ScanSettings.Builder b = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_BALANCED);
            if (Build.VERSION.SDK_INT >= 23) {
                b.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES);
                b.setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE);
            }

            scanner.startScan(filters, b.build(), scanCallback);
        } catch (Throwable t) {
            Log.w(TAG, "scan failed: " + t.getMessage());
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (!running || result == null || result.getDevice() == null) return;

            final String address = result.getDevice().getAddress();
            if (address == null) return;

            String peerId = "";
            ScanRecord record = result.getScanRecord();
            if (record != null) {
                byte[] sd = record.getServiceData(new ParcelUuid(SERVICE_UUID));
                if (sd != null && sd.length >= 5) peerId = Framing.bytesToHex(sd, 5);
            }
            if (peerId.length() > 0) peerTags.put(address, peerId + "|");

            if (clientLinks.containsKey(address) || serverLinks.containsKey(address)) return;
            if (links().size() >= MAX_LINKS) return;

            Long since = dialing.get(address);
            long now = System.currentTimeMillis();
            if (since != null && now - since.longValue() < DIAL_FALLBACK_MS) return;

            // Tie-break: smaller identity dials. Unknown peer id falls back to
            // the delayed dial so an unreadable scan response is not fatal.
            boolean shouldDial = peerId.length() == 0
                    ? (since != null)
                    : localId.compareTo(peerId) < 0;

            if (!shouldDial) {
                dialing.put(address, Long.valueOf(now));
                handler.postDelayed(new Runnable() {
                    public void run() {
                        if (running
                                && !clientLinks.containsKey(address)
                                && !serverLinks.containsKey(address)) {
                            connect(address);
                        }
                    }
                }, DIAL_FALLBACK_MS);
                return;
            }

            dialing.put(address, Long.valueOf(now));
            connect(address);
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.w(TAG, "scan error " + errorCode);
            detail = "جست‌وجو ناموفق (" + errorCode + ")";
            events.onTransportState(NAME, running, detail);
        }
    };

    @SuppressLint("MissingPermission")
    private void connect(String address) {
        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            if (device == null) return;
            if (Build.VERSION.SDK_INT >= 23) {
                device.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            } else {
                device.connectGatt(ctx, false, gattCallback);
            }
        } catch (Throwable t) {
            Log.w(TAG, "connect " + address + ": " + t.getMessage());
        }
    }

    // ------------------------------------------------------------- GATT client

    @SuppressLint("MissingPermission")
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String address = gatt.getDevice().getAddress();

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                clientLinks.put(address, gatt);
                mtus.put(address, Integer.valueOf(DEFAULT_MTU));
                try { gatt.requestMtu(WANTED_MTU); } catch (Throwable ignored) {}
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                clientLinks.remove(address);
                cleanupLink(address);
                try { gatt.close(); } catch (Throwable ignored) {}
                if (!serverLinks.containsKey(address)) {
                    events.onLinkDown(NAME, linkId(address));
                }
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            String address = gatt.getDevice().getAddress();
            if (status == BluetoothGatt.GATT_SUCCESS && mtu > DEFAULT_MTU) {
                mtus.put(address, Integer.valueOf(mtu));
            }
            try { gatt.discoverServices(); } catch (Throwable ignored) {}
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            String address = gatt.getDevice().getAddress();
            if (status != BluetoothGatt.GATT_SUCCESS) {
                try { gatt.disconnect(); } catch (Throwable ignored) {}
                return;
            }

            BluetoothGattService service = gatt.getService(SERVICE_UUID);
            if (service == null) {
                try { gatt.disconnect(); } catch (Throwable ignored) {}
                return;
            }

            BluetoothGattCharacteristic tx = service.getCharacteristic(TX_UUID);
            if (tx != null) {
                try {
                    gatt.setCharacteristicNotification(tx, true);
                    BluetoothGattDescriptor cccd = tx.getDescriptor(CCCD_UUID);
                    if (cccd != null) {
                        cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        gatt.writeDescriptor(cccd);
                    }
                } catch (Throwable ignored) {}
            }

            detail = links().size() + " اتصال";
            events.onLinkUp(NAME, linkId(address), tagOf(address));
            queueFor(address).pump();
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic characteristic,
                                          int status) {
            String address = gatt.getDevice().getAddress();
            SendQueue q = outbound.get(address);
            if (q != null) q.onCompleted(status == BluetoothGatt.GATT_SUCCESS);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            if (!TX_UUID.equals(characteristic.getUuid())) return;
            String address = gatt.getDevice().getAddress();
            feed(address, characteristic.getValue());
        }
    };

    // ------------------------------------------------------------- GATT server

    @SuppressLint("MissingPermission")
    private boolean startGattServer() {
        try {
            BluetoothManager manager =
                    (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            if (manager == null) return false;

            server = manager.openGattServer(ctx, serverCallback);
            if (server == null) return false;

            BluetoothGattService service = new BluetoothGattService(
                    SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);

            BluetoothGattCharacteristic rx = new BluetoothGattCharacteristic(
                    RX_UUID,
                    BluetoothGattCharacteristic.PROPERTY_WRITE
                            | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE);

            txCharacteristic = new BluetoothGattCharacteristic(
                    TX_UUID,
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_READ);

            BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(
                    CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ
                            | BluetoothGattDescriptor.PERMISSION_WRITE);
            txCharacteristic.addDescriptor(cccd);

            service.addCharacteristic(rx);
            service.addCharacteristic(txCharacteristic);
            server.addService(service);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "gatt server: " + t.getMessage());
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    private final BluetoothGattServerCallback serverCallback =
            new BluetoothGattServerCallback() {

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (device == null) return;
            String address = device.getAddress();

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (links().size() >= MAX_LINKS) {
                    try { server.cancelConnection(device); } catch (Throwable ignored) {}
                    return;
                }
                serverLinks.put(address, device);
                if (!mtus.containsKey(address)) {
                    mtus.put(address, Integer.valueOf(DEFAULT_MTU));
                }
                detail = links().size() + " اتصال";
                if (!clientLinks.containsKey(address)) {
                    events.onLinkUp(NAME, linkId(address), tagOf(address));
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                serverLinks.remove(address);
                cleanupLink(address);
                if (!clientLinks.containsKey(address)) {
                    events.onLinkDown(NAME, linkId(address));
                }
            }
        }

        @Override
        public void onMtuChanged(BluetoothDevice device, int mtu) {
            if (device == null) return;
            if (mtu > DEFAULT_MTU) {
                mtus.put(device.getAddress(), Integer.valueOf(mtu));
            }
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device,
                                                 int requestId,
                                                 BluetoothGattCharacteristic characteristic,
                                                 boolean preparedWrite,
                                                 boolean responseNeeded,
                                                 int offset,
                                                 byte[] value) {
            if (device != null && RX_UUID.equals(characteristic.getUuid())) {
                feed(device.getAddress(), value);
            }
            if (responseNeeded) {
                try {
                    server.sendResponse(device, requestId,
                            BluetoothGatt.GATT_SUCCESS, offset, null);
                } catch (Throwable ignored) {}
            }
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device,
                                             int requestId,
                                             BluetoothGattDescriptor descriptor,
                                             boolean preparedWrite,
                                             boolean responseNeeded,
                                             int offset,
                                             byte[] value) {
            if (responseNeeded) {
                try {
                    server.sendResponse(device, requestId,
                            BluetoothGatt.GATT_SUCCESS, offset, null);
                } catch (Throwable ignored) {}
            }
            if (device != null) queueFor(device.getAddress()).pump();
        }

        @Override
        public void onNotificationSent(BluetoothDevice device, int status) {
            if (device == null) return;
            SendQueue q = outbound.get(device.getAddress());
            if (q != null) q.onCompleted(status == BluetoothGatt.GATT_SUCCESS);
        }
    };

    // ---------------------------------------------------------------- plumbing

    private void cleanupLink(String address) {
        mtus.remove(address);
        inbound.remove(address);
        outbound.remove(address);
        dialing.remove(address);
        detail = links().size() + " اتصال";
    }

    private String tagOf(String address) {
        String t = peerTags.get(address);
        return t == null ? "" : t;
    }

    private String linkId(String address) {
        return NAME + ":" + address;
    }

    private String addressOf(String linkId) {
        if (linkId == null) return null;
        String prefix = NAME + ":";
        if (!linkId.startsWith(prefix)) return null;
        return linkId.substring(prefix.length());
    }

    private int chunkSize(String address) {
        Integer mtu = mtus.get(address);
        int m = mtu == null ? DEFAULT_MTU : mtu.intValue();
        int size = m - 3;
        return size < 18 ? 18 : size;
    }

    private SendQueue queueFor(String address) {
        SendQueue q = outbound.get(address);
        if (q == null) {
            q = new SendQueue(address);
            outbound.put(address, q);
        }
        return q;
    }

    private void feed(String address, byte[] chunk) {
        if (address == null || chunk == null || chunk.length == 0) return;
        Framing.Reassembler r = inbound.get(address);
        if (r == null) {
            r = new Framing.Reassembler();
            inbound.put(address, r);
        }
        List<byte[]> complete = r.accept(chunk);
        for (int i = 0; i < complete.size(); i++) {
            events.onPacket(NAME, linkId(address), complete.get(i));
        }
    }

    private BluetoothAdapter defaultAdapter() {
        try {
            BluetoothManager m =
                    (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            if (m != null && m.getAdapter() != null) return m.getAdapter();
            return BluetoothAdapter.getDefaultAdapter();
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT < 31) return true;
        return granted("android.permission.BLUETOOTH_SCAN")
                && granted("android.permission.BLUETOOTH_CONNECT")
                && granted("android.permission.BLUETOOTH_ADVERTISE");
    }

    private boolean granted(String permission) {
        try {
            return ctx.checkSelfPermission(permission)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * One outstanding BLE write per link, drained by the completion callback.
     */
    private final class SendQueue {
        private final String address;
        private final ArrayDeque<byte[]> chunks = new ArrayDeque<byte[]>();
        private boolean inFlight = false;

        SendQueue(String address) {
            this.address = address;
        }

        synchronized void enqueue(byte[] framed, int chunkSize) {
            chunks.addAll(Framing.chunks(framed, chunkSize));
        }

        synchronized void onCompleted(boolean ok) {
            inFlight = false;
            if (!ok) chunks.clear();
            pumpLocked();
        }

        synchronized void pump() {
            pumpLocked();
        }

        @SuppressLint("MissingPermission")
        private void pumpLocked() {
            if (inFlight || chunks.isEmpty()) return;
            byte[] chunk = chunks.peek();
            boolean dispatched = false;

            BluetoothGatt gatt = clientLinks.get(address);
            if (gatt != null) {
                try {
                    BluetoothGattService s = gatt.getService(SERVICE_UUID);
                    BluetoothGattCharacteristic rx =
                            s == null ? null : s.getCharacteristic(RX_UUID);
                    if (rx != null) {
                        rx.setWriteType(
                                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                        rx.setValue(chunk);
                        dispatched = gatt.writeCharacteristic(rx);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "client write: " + t.getMessage());
                }
            }

            if (!dispatched) {
                BluetoothDevice device = serverLinks.get(address);
                if (device != null && server != null && txCharacteristic != null) {
                    try {
                        txCharacteristic.setValue(chunk);
                        dispatched = server.notifyCharacteristicChanged(
                                device, txCharacteristic, false);
                    } catch (Throwable t) {
                        Log.w(TAG, "server notify: " + t.getMessage());
                    }
                }
            }

            if (dispatched) {
                chunks.poll();
                inFlight = true;
            } else {
                chunks.clear();
            }
        }
    }
}
