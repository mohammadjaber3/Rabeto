package com.rabeto.app;import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;import androidx.core.content.FileProvider;import com.google.android.gms.nearby.Nearby;
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
import com.google.android.gms.nearby.connection.Strategy;import org.json.JSONArray;
import org.json.JSONObject;import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;/**•شبکهٔ مِش رابطو.••ترتیب انتخاب راه ارتباطی را خودِ Nearby Connections انجام می‌دهد:•ابتدا با بلوتو... */
public class Mesh {
  private static final String TAG = "RabetoMesh";
  private static final String SERVICE_ID = "com.rabeto.mesh.v5";
  private static final int CACHE_MAX = 250;
  private static final int SEEN_MAX = 3000;
  private static final long CACHE_TTL_MS = 24L * 60 * 60 * 1000;

  private final Context ctx;
  private final WebView web;
  private final ConnectionsClient client;
  private final Handler ui = new Handler(Looper.getMainLooper());
  private final SharedPreferences prefs;
  private final Ident ident;
  private final SessionManager sessions;

  private final Map<String, String[]> peers = new LinkedHashMap<String, String[]>();  // endpoint -> {id,name}
  private final Map<String, String[]> found = new LinkedHashMap<String, String[]>();
  private final Map<String, String> quality = new HashMap<String, String>();          // endpoint -> bt|wifi
  private final Map<String, String> pubKeys = new HashMap<String, String>();          // userId -> publicKey
  private final Map<String, String> names = new HashMap<String, String>();            // userId -> name
  private final Map<String, String> avatars = new HashMap<String, String>();          // userId -> عکس (base64)
  private final LinkedHashSet<String> seen = new LinkedHashSet<String>();
  private final LinkedList<JSONObject> cache = new LinkedList<JSONObject>();

  private String myId;
  private String myName;
  private String myAvatar;
  private boolean running = false;
  private boolean fastMode;   // true => P2P_STAR (وای‌فای دایرکت، اتصال ستاره‌ای)

  public Mesh(Context ctx, WebView web) {
      this.ctx = ctx;
      this.web = web;
      this.client = Nearby.getConnectionsClient(ctx);
      this.prefs = ctx.getSharedPreferences("rabeto", Context.MODE_PRIVATE);
      this.ident = new Ident(prefs);
      this.sessions = new SessionManager(ident);
      this.fastMode = prefs.getBoolean("fastMode", false);

      if (ident.ok()) {
          myId = ident.id();
      } else {
          myId = prefs.getString("fallbackId", null);
          if (myId == null) {
              myId = Long.toHexString(System.nanoTime()).substring(0, 10);
              prefs.edit().putString("fallbackId", myId).apply();
          }
      }
      myName = prefs.getString("myName", "کاربر " + myId.substring(0, 3));
      myAvatar = prefs.getString("myAvatar", "");
      loadState();
  }

  // ---------------------------------------------------------------- lifecycle
  public void start() {
      if (running) return;
      running = true;
      advertise();
      discover();
      emitStatus();
      emitRadios();
  }

  public void stop() {
      running = false;
      try { client.stopAllEndpoints(); } catch (Throwable ignored) {}
      try { client.stopAdvertising(); } catch (Throwable ignored) {}
      try { client.stopDiscovery(); } catch (Throwable ignored) {}
      peers.clear();
      found.clear();
      sessions.clear();
  }

  private Strategy strategy() {
      return fastMode ? Strategy.P2P_STAR : Strategy.P2P_CLUSTER;
  }

  private String localTag() {
      return myId + "|" + myName;
  }

  private void advertise() {
      AdvertisingOptions o = new AdvertisingOptions.Builder()
              .setStrategy(strategy())
              .setConnectionType(ConnectionType.DISRUPTIVE)   // وای‌فای را ترجیح بده
              .setLowPower(false)
              .build();
      client.startAdvertising(localTag(), SERVICE_ID, lifecycle, o)
              .addOnSuccessListener(new com.google.android.gms.tasks.OnSuccessListener<Void>() {
                  public void onSuccess(Void v) { Log.i(TAG, "advertising"); }
              })
              .addOnFailureListener(new com.google.android.gms.tasks.OnFailureListener() {
                  public void onFailure(Exception e) {
                      Log.w(TAG, "advertise failed: " + e.getMessage());
                      if (running) ui.postDelayed(new Runnable() { public void run() { advertise(); } }, 6000);
                  }
              });
  }

  private void discover() {
      DiscoveryOptions o = new DiscoveryOptions.Builder()
              .setStrategy(strategy())
              .setLowPower(false)
              .build();
      client.startDiscovery(SERVICE_ID, discovery, o)
              .addOnSuccessListener(new com.google.android.gms.tasks.OnSuccessListener<Void>() {
                  public void onSuccess(Void v) { Log.i(TAG, "discovering"); }
              })
              .addOnFailureListener(new com.google.android.gms.tasks.OnFailureListener() {
                  public void onFailure(Exception e) {
                      Log.w(TAG, "discover failed: " + e.getMessage());
                      if (running) ui.postDelayed(new Runnable() { public void run() { discover(); } }, 6000);
                  }
              });
  }

  // ---------------------------------------------------------------- discovery
  private final EndpointDiscoveryCallback discovery = new EndpointDiscoveryCallback() {
      @Override
      public void onEndpointFound(String endpointId, DiscoveredEndpointInfo info) {
          final String ep = endpointId;
          String[] tag = parseTag(info.getEndpointName());
          found.put(ep, tag);
          if (myId.compareTo(tag[0]) < 0) connectTo(ep);
          ui.postDelayed(new Runnable() {
              public void run() {
                  if (running && !peers.containsKey(ep) && found.containsKey(ep)) connectTo(ep);
              }
          }, 12000);
      }

      @Override
      public void onEndpointLost(String endpointId) {
          found.remove(endpointId);
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
                      public void onFailure(Exception e) { Log.w(TAG, "connect: " + e.getMessage()); }
                  });
      } catch (Throwable t) {
          Log.w(TAG, "connect exception: " + t.getMessage());
      }
  }

  private final ConnectionLifecycleCallback lifecycle = new ConnectionLifecycleCallback() {
      @Override
      public void onConnectionInitiated(String endpointId, ConnectionInfo info) {
          found.put(endpointId, parseTag(info.getEndpointName()));
          client.acceptConnection(endpointId, payloads);
      }

      @Override
      public void onConnectionResult(String endpointId, ConnectionResolution res) {
          if (res.getStatus().getStatusCode() == ConnectionsStatusCodes.STATUS_OK) {
              String[] tag = found.containsKey(endpointId) ? found.get(endpointId)
                      : new String[]{endpointId, endpointId};
              peers.put(endpointId, tag);
              names.put(tag[0], tag[1]);
              emitStatus();
              sendHello(endpointId);
              flushCacheTo(endpointId);
          } else {
              Log.w(TAG, "connection result " + res.getStatus().getStatusCode());
          }
      }

      @Override
      public void onDisconnected(String endpointId) {
          peers.remove(endpointId);
          quality.remove(endpointId);
          emitStatus();
      }

      @Override
      public void onBandwidthChanged(String endpointId, BandwidthInfo info) {
          try {
              int q = info.getQuality();
              quality.put(endpointId, q >= BandwidthInfo.Quality.MEDIUM ? "wifi" : "bt");
          } catch (Throwable ignored) {
              quality.put(endpointId, "bt");
          }
          emitStatus();
      }
  };

  // ---------------------------------------------------------------- payloads
  private final PayloadCallback payloads = new PayloadCallback() {
      @Override
      public void onPayloadReceived(String endpointId, Payload payload) {
          byte[] bytes = payload.asBytes();
          if (!Protocol.validPacketSize(bytes)) return;
          try {
              JSONObject o = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
              String t = o.optString("t", "msg");
              if ("hello".equals(t)) handleHello(endpointId, o);
              else handleIncoming(endpointId, o);
          } catch (Throwable e) {
              Log.w(TAG, "bad payload");
          }
      }

      @Override
      public void onPayloadTransferUpdate(String endpointId, PayloadTransferUpdate update) {
          // Handle payload transfer updates if needed
      }
  };

  private void sendHello(String endpointId) {
      try {
          if (!ident.ok()) return;

          JSONObject o = new JSONObject();
          o.put("t", "hello");
          o.put("v", Protocol.VERSION);
          o.put("id", myId);
          o.put("name", myName);
          o.put("av", myAvatar);
          o.put("pk", ident.pubKey());
          o.put("sig", ident.sign(Protocol.canonicalHello(o)));

          byte[] packet = o.toString().getBytes(StandardCharsets.UTF_8);
          if (!Protocol.validPacketSize(packet)) return;

          client.sendPayload(endpointId, Payload.fromBytes(packet));
      } catch (Throwable ignored) {}
  }

  private void handleHello(String endpointId, JSONObject o) {
      if (!Protocol.validHello(o)) return;

      String uid = o.optString("id", "");
      String nm = o.optString("name", uid);
      String pk = o.optString("pk", "");
      String sig = o.optString("sig", "");

      if (uid.length() == 0 || uid.equals(myId)) return;

      // Authenticate the identity announcement before trusting or persisting
      // its public key, name, or avatar.
      if (!Ident.verify(pk, Protocol.canonicalHello(o), sig)) return;

      int keyState = learnKey(uid, pk);
      if (keyState == -1) return;

      if (keyState == -2) {
          sessions.remove(uid);
          emitSecurityEvent("identity_changed", uid);
          return;
      }

      if (keyState != 1 || !sessions.establish(uid, pk)) return;

      names.put(uid, nm);
      String av = o.optString("av", "");
      if (av.length() > 0) avatars.put(uid, av);

      String[] tag = new String[]{uid, nm};
      found.put(endpointId, tag);
      if (peers.containsKey(endpointId)) peers.put(endpointId, tag);

      saveState();
      emitStatus();
  }

  /** اعتماد در اولین برخورد: کلید را ذخیره می‌کنیم و از آن پس عوض شدنش هشدار می‌دهد */
  private int learnKey(String uid, String pk) {
      if (pk == null || pk.length() == 0) return 0;
      if (!uid.equals(Ident.idFor(pk))) return -1;          // شناسه با کلید نمی‌خواند => جعلی
      String had = pubKeys.get(uid);
      if (had == null) { pubKeys.put(uid, pk); saveState(); return 1; }
      if (!had.equals(pk)) return -2;                        // کلید عوض شده => هشدار جدی
      return 1;
  }

  // ---------------------------------------------------------------- messages
  private String canonical(JSONObject e) {
      return Protocol.canonicalMessage(e);
  }

  private String aad(JSONObject e) {
      return e.optInt("v") + "|" + e.optString("id") + "|"
              + e.optString("from") + "|" + e.optString("to") + "|"
              + e.optString("kind") + "|" + e.optLong("ts") + "|"
              + e.optInt("enc") + "|" + e.optString("pk");
  }

  private void handleIncoming(String fromEndpoint, JSONObject env) {
      if (!Protocol.validEnvelope(env, System.currentTimeMillis())) return;
      String mid = env.optString("id", "");
      if (mid.length() == 0 || seen.contains(mid)) return;

      String from = env.optString("from", "");
      String pk = env.optString("pk", "");
      boolean isNew = !pubKeys.containsKey(from) || !names.containsKey(from);

      // Authenticate with the presented public key before changing
      // persistent identity state. This makes first contact proof-of-possession.
      boolean sigOk = Ident.verify(pk, canonical(env), env.optString("sig", ""));
      if (!sigOk) return;

      int keyState = learnKey(from, pk);
      if (keyState == -1) return;
      if (keyState == -2) {
          sessions.remove(from);
          emitSecurityEvent("identity_changed", from);
          return;
      }
      if (keyState != 1 || !sessions.establish(from, pk)) return;

      // Only authenticated messages enter replay state or the store-and-forward cache.
      remember(mid);

      if (!from.equals(myId)) {
          String nm = env.optString("name", from);
          if (nm.length() > 0) names.put(from, nm);
          if (isNew) { saveState(); emitStatus(); }
      }

      cacheAdd(env);

      String to = env.optString("to", "*");
      boolean forMe = "*".equals(to) || myId.equals(to);
      if (forMe) {
          JSONObject out = clone(env);
          try {
              out.put("verified", true);
              out.put("keyChanged", false);
              out.put("fake", false);
              if (env.optInt("enc", 0) == 1 && myId.equals(to)) {
                  String txt = sessions.decrypt(from, env.optString("text", ""), aad(env));
                  String dat = env.optString("data", "").length() > 0
                          ? sessions.decrypt(from, env.optString("data", ""), aad(env)) : "";
                  out.put("text", txt == null ? "" : txt);
                  out.put("data", dat == null ? "" : dat);
                  out.put("opened", txt != null);
              }
          } catch (Throwable ignored) {}
          emit(wrap("message", out));

          if (myId.equals(to) && !"ack".equals(env.optString("kind", ""))) {
              sendEnvelope(from, "ack", mid, "", false);
          }
      }

      if (!myId.equals(to)) {
          int ttl = env.optInt("ttl", 0);
          int hops = env.optInt("hops", 0);
          if (ttl > 0 && hops < Protocol.MAX_HOPS) {
              JSONObject fwd = clone(env);
              try {
                  fwd.put("ttl", ttl - 1);
                  fwd.put("hops", hops + 1);
              } catch (Throwable ignored) {}
              sendRaw(fwd, fromEndpoint);
          }
      }
  }

  /** ساخت و فرستادن یک پیام. اگر گیرنده مشخص باشد، متن رمز می‌شود. */
  private String sendEnvelope(String to, String kind, String text, String data, boolean wantEncrypt) {
      try {
          JSONObject e = new JSONObject();
          String mid = Crypto.randomId(myId);
          String outText = text == null ? "" : text;
          String outData = data == null ? "" : data;

          long ts = System.currentTimeMillis();
          e.put("t", "msg");
          e.put("v", Protocol.VERSION);
          e.put("id", mid);
          e.put("from", myId);
          e.put("name", myName);
          e.put("to", to);
          e.put("kind", kind);
          e.put("ts", ts);
          e.put("enc", 0);
          e.put("text", outText);
          e.put("data", outData);
          e.put("pk", ident.pubKey());
          e.put("ttl", Protocol.MAX_TTL);
          e.put("hops", 0);

          if (wantEncrypt && !"*".equals(to)) {
              // Private messages must fail closed. Never silently downgrade
              // an encryption request to plaintext.
              if (!ident.ok()) return "";

              String theirPk = pubKeys.get(to);
              if (theirPk == null || theirPk.length() == 0) return "";

              if (!sessions.has(to) && !sessions.establish(to, theirPk)) return "";

              e.put("enc", 1);
              String aad = aad(e);
              String t2 = sessions.encrypt(to, outText, aad);
              String d2 = outData.length() > 0 ? sessions.encrypt(to, outData, aad) : "";

              if (t2.length() > 0 && (outData.length() == 0 || d2.length() > 0)) {
                  outText = t2;
                  outData = d2;
                  e.put("text", outText);
                  e.put("data", outData);
              } else {
                  return "";
              }
          }

          e.put("sig", ident.sign(canonical(e)));

          remember(mid);
          cacheAdd(e);
          sendRaw(e, null);
          return mid;
      } catch (Throwable t) {
          return "";
      }
  }

  private void sendRaw(JSONObject env, String skipEndpoint) {
      if (peers.isEmpty()) return;
      List<String> targets = new ArrayList<String>();
      for (String ep : peers.keySet()) {
          if (skipEndpoint != null && skipEndpoint.equals(ep)) continue;
          targets.add(ep);
      }
      if (targets.isEmpty()) return;
      try {
          byte[] packet = env.toString().getBytes(StandardCharsets.UTF_8);
          if (packet.length > Protocol.MAX_PACKET_BYTES) return;
          Payload p = Payload.fromBytes(packet);
          client.sendPayload(targets, p);
      } catch (Throwable t) {
          Log.w(TAG, "send failed: " + t.getMessage());
      }
  }

  // ---------------------------------------------------------------- cache
  private JSONObject clone(JSONObject o) {
      try { return new JSONObject(o.toString()); } catch (Throwable t) { return o; }
  }

  private void remember(String id) {
      seen.add(id);
      while (seen.size() > SEEN_MAX) {
          Iterator<String> it = seen.iterator();
          it.next();
          it.remove();
      }
  }

  private void cacheAdd(JSONObject env) {
      cache.addLast(env);
      while (cache.size() > CACHE_MAX) cache.removeFirst();
      saveState();
  }

  private void flushCacheTo(String endpointId) {
      long now = System.currentTimeMillis();
      int sent = 0;
      List<JSONObject> snapshot = new ArrayList<JSONObject>(cache);
      for (JSONObject env : snapshot) {
          if (now - env.optLong("ts", now) > CACHE_TTL_MS) continue;
          JSONObject c = clone(env);
          if (c.optInt("ttl", 0) <= 0 || c.optInt("hops", 0) >= Protocol.MAX_HOPS) continue;
          try {
              client.sendPayload(endpointId, Payload.fromBytes(c.toString().getBytes(StandardCharsets.UTF_8)));
          } catch (Throwable ignored) {}
          if (++sent > 120) break;
      }
  }

  private void saveState() {
      try {
          JSONArray arr = new JSONArray();
          for (JSONObject o : cache) {
              if (o.optString("data", "").length() > 120000) continue;
              arr.put(o);
          }
          JSONObject keys = new JSONObject();
          for (Map.Entry<String, String> en : pubKeys.entrySet()) keys.put(en.getKey(), en.getValue());
          JSONObject nms = new JSONObject();
          for (Map.Entry<String, String> en : names.entrySet()) nms.put(en.getKey(), en.getValue());
          JSONObject avs = new JSONObject();
          for (Map.Entry<String, String> en : avatars.entrySet()) avs.put(en.getKey(), en.getValue());
          prefs.edit()
                  .putString("cache", arr.toString())
                  .putString("keys", keys.toString())
                  .putString("names", nms.toString())
                  .putString("avatars", avs.toString())
                  .apply();
      } catch (Throwable ignored) {}
  }

  private void loadState() {
      try {
          JSONArray arr = new JSONArray(prefs.getString("cache", "[]"));
          for (int i = 0; i < arr.length(); i++) {
              JSONObject o = arr.getJSONObject(i);
              cache.addLast(o);
              remember(o.optString("id", ""));
          }
      } catch (Throwable ignored) {}
      try {
          JSONObject keys = new JSONObject(prefs.getString("keys", "{}"));
          Iterator<String> it = keys.keys();
          while (it.hasNext()) { String k = it.next(); pubKeys.put(k, keys.optString(k)); }
      } catch (Throwable ignored) {}
      try {
          JSONObject nms = new JSONObject(prefs.getString("names", "{}"));
          Iterator<String> it = nms.keys();
          while (it.hasNext()) { String k = it.next(); names.put(k, nms.optString(k)); }
      } catch (Throwable ignored) {}
      try {
          JSONObject avs = new JSONObject(prefs.getString("avatars", "{}"));
          Iterator<String> it = avs.keys();
          while (it.hasNext()) { String k = it.next(); avatars.put(k, avs.optString(k)); }
      } catch (Throwable ignored) {}
  }

  // ---------------------------------------------------------------- to web
  private JSONObject wrap(String type, JSONObject env) {
      JSONObject o = new JSONObject();
      try { o.put("type", type); o.put("env", env); } catch (Throwable ignored) {}
      return o;
  }

  private void emit(final JSONObject o) {
      ui.post(new Runnable() {
          public void run() {
              try {
                  web.evaluateJavascript("window.rabetoOn && window.rabetoOn(" + o.toString() + ")", null);
              } catch (Throwable ignored) {}
          }
      });
  }

  private void emitStatus() {
      try {
          JSONObject o = new JSONObject();
          o.put("type", "status");
          o.put("running", running);
          o.put("fastMode", fastMode);
          o.put("crypto", ident.ok());

          JSONObject me = new JSONObject();
          me.put("id", myId);
          me.put("name", myName);
          me.put("code", ident.ok() ? ident.code() : "--------");
          me.put("av", myAvatar);
          o.put("me", me);

          boolean anyWifi = false;
          JSONArray arr = new JSONArray();
          for (Map.Entry<String, String[]> e : peers.entrySet()) {
              JSONObject p = new JSONObject();
              String uid = e.getValue()[0];
              p.put("id", uid);
              p.put("name", e.getValue()[1]);
              String q = quality.get(e.getKey());
              p.put("link", q == null ? "bt" : q);
              if ("wifi".equals(q)) anyWifi = true;
              String pk = pubKeys.get(uid);
              p.put("code", pk == null ? "" : Ident.codeFor(pk));
              p.put("hasKey", pk != null);
              arr.put(p);
          }
          o.put("peers", arr);
          o.put("link", anyWifi ? "wifi" : "bt");

          JSONObject known = new JSONObject();
          for (Map.Entry<String, String> en : names.entrySet()) {
              if (en.getKey().equals(myId)) continue;
              JSONObject k = new JSONObject();
              k.put("name", en.getValue());
              String pk = pubKeys.get(en.getKey());
              k.put("code", pk == null ? "" : Ident.codeFor(pk));
              k.put("hasKey", pk != null);
              String av = avatars.get(en.getKey());
              if (av != null) k.put("av", av);
              known.put(en.getKey(), k);
          }
          o.put("known", known);
          emit(o);
      } catch (Throwable ignored) {}
  }

  private void emitSecurityEvent(String code, String peerId) {
      try {
          JSONObject o = new JSONObject();
          o.put("type", "security");
          o.put("code", code);
          o.put("peer", peerId == null ? "" : peerId);
          emit(o);
      } catch (Throwable ignored) {}
  }

  private void emitRadios() {
      try {
          JSONObject o = new JSONObject();
          o.put("type", "radio");
          o.put("bt", btOn());
          o.put("wifi", wifiOn());
          emit(o);
      } catch (Throwable ignored) {}
  }

  private boolean btOn() {
      try {
          BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
          BluetoothAdapter a = bm != null ? bm.getAdapter() : BluetoothAdapter.getDefaultAdapter();
          return a != null && a.isEnabled();
      } catch (Throwable t) { return true; }
  }

  private boolean wifiOn() {
      try {
          WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
          return wm != null && wm.isWifiEnabled();
      } catch (Throwable t) { return true; }
  }

  public void reportError(String code) {
      try {
          JSONObject o = new JSONObject();
          o.put("type", "error");
          o.put("code", code);
          emit(o);
      } catch (Throwable ignored) {}
  }

  private String[] parseTag(String raw) {
      if (raw == null) return new String[]{"?", "?"};
      int i = raw.indexOf('|');
      if (i < 0) return new String[]{raw, raw};
      return new String[]{raw.substring(0, i), raw.substring(i + 1)};
  }

  // ---------------------------------------------------------------- JS bridge
  @JavascriptInterface
  public String send(String json) {
      try {
          JSONObject in = new JSONObject(json);
          String to = in.optString("to", "*");
          return sendEnvelope(to, in.optString("kind", "text"),
                  in.optString("text", ""), in.optString("data", ""), true);
      } catch (Throwable t) {
          return "";
      }
  }

  @JavascriptInterface
  public void setName(String name) {
      if (name == null || name.trim().length() == 0) return;
      myName = name.trim().replace("|", " ");
      prefs.edit().putString("myName", myName).apply();
      if (running) {
          try { client.stopAdvertising(); } catch (Throwable ignored) {}
          advertise();
          for (String ep : new ArrayList<String>(peers.keySet())) sendHello(ep);
      }
      emitStatus();
  }

  @JavascriptInterface
  public void setAvatar(String base64) {
      myAvatar = base64 == null ? "" : base64;
      prefs.edit().putString("myAvatar", myAvatar).apply();
      if (running) {
          for (String ep : new ArrayList<String>(peers.keySet())) sendHello(ep);
      }
      emitStatus();
  }

  @JavascriptInterface
  public String state() {
      try {
          JSONObject o = new JSONObject();
          o.put("id", myId);
          o.put("name", myName);
          o.put("code", ident.ok() ? ident.code() : "--------");
          o.put("running", running);
          o.put("peers", peers.size());
          o.put("fastMode", fastMode);
          return o.toString();
      } catch (Throwable t) { return "{}"; }
  }

  @JavascriptInterface
  public void refresh() { emitStatus(); emitRadios(); }

  @JavascriptInterface
  public void restart() {
      stop();
      ui.postDelayed(new Runnable() { public void run() { start(); } }, 1500);
  }

  @JavascriptInterface
  public void setFastMode(boolean on) {
      fastMode = on;
      prefs.edit().putBoolean("fastMode", on).apply();
      restart();
  }

  @JavascriptInterface
  public void openBluetooth() {
      ui.post(new Runnable() {
          public void run() {
              try {
                  ctx.startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
              } catch (Throwable ignored) {}
          }
      });
  }

  @JavascriptInterface
  public void openWifi() {
      ui.post(new Runnable() {
          public void run() {
              try {
                  Intent i;
                  if (Build.VERSION.SDK_INT >= 29) i = new Intent(Settings.Panel.ACTION_WIFI);
                  else i = new Intent(Settings.ACTION_WIFI_SETTINGS);
                  ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
              } catch (Throwable ignored) {}
          }
      });
  }

  /** فرستادن خودِ فایل نصبی اپ به گوشی بغلی — بدون اینترنت */
  @JavascriptInterface
  public void shareApp() {
      ui.post(new Runnable() {
          public void run() {
              try {
                  File src = new File(ctx.getApplicationInfo().sourceDir);
                  File dir = new File(ctx.getCacheDir(), "apk");
                  dir.mkdirs();
                  File out = new File(dir, "Rabeto.apk");
                  InputStream in = new FileInputStream(src);
                  OutputStream os = new FileOutputStream(out);
                  byte[] buf = new byte[65536];
                  int n;
                  while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                  os.close();
                  in.close();

                  Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".files", out);
                  Intent i = new Intent(Intent.ACTION_SEND);
                  i.setType("application/vnd.android.package-archive");
                  i.putExtra(Intent.EXTRA_STREAM, uri);
                  i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                  Intent chooser = Intent.createChooser(i, "فرستادن اپ رابطو");
                  chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                  ctx.startActivity(chooser);
              } catch (Throwable t) {
                  reportError("share-failed");
              }
          }
      });
  }
}
