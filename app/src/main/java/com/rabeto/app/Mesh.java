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
import java.util.Map;/**•شبکهٔ مِش رابطو.••ترتیب انتخاب راه ارتباطی را خودِ Nearby Connections انجام می‌دهد:•ابتدا با بلوتوث سعی می‌کند•اگر بلوتوث کار نکند، وای‌فای مستقیم استفاده می‌کند (اگر پشتیبانی شود)•هرچه سرعت بیشتر باشد (کمتر latency)، ترجیح داده می‌شود•پیام‌ها علاوه بر direct connection، می‌توانند از طریق واسطه‌های دیگر منتقل شوند (flood)•هر پیام شناسه مختصی دارد تا دوباره فرستاده نشود•پیام‌های رمزشدهٔ شخصی را تنها مقصد می‌تواند باز کند•برای هر فرد کلید عمومی ذخیره می‌شود و اولین بار (TOFU) تایید می‌شود */
public class Mesh {
  private static final String TAG = "RabetoMesh";
  private static final String SERVICE_ID = "com.rabeto.mesh.v1";
  private static final int MAX_TTL = 6;
  private static final int CACHE_MAX = 250;
  private static final int SEEN_MAX = 3000;
  private static final long CACHE_TTL_MS = 24L * 60 * 60 * 1000;

  private final Context ctx;
  private final WebView web;
  private final ConnectionsClient client;
  private final Handler ui = new Handler(Looper.getMainLooper());
  private final SharedPreferences prefs;
  private final Ident ident;

  private final Map<String, String[]> peers = new LinkedHashMap<String, String[]>();  // endpoint -> {id,name}
  private final Map<String, String[]> found = new LinkedHashMap<String, String[]>();
  private final Map<String, String> quality = new HashMap<String, String>();          // endpoint -> bt|wifi
  private final Map<String, String> pubKeys = new HashMap<String, String>();          // userId -> publicKey
  private final Map<String, String> names = new HashMap<String, String>();            // userId -> name
  private final LinkedHashSet<String> seen = new LinkedHashSet<String>();
  private final LinkedList<JSONObject> cache = new LinkedList<JSONObject>();

  private String myId;
  private String myName;
  private boolean running = false;
  private boolean fastMode;   // true => P2P_STAR (وای‌فای دایرکت، اتصال ستاره‌ای)

  public Mesh(Context ctx, WebView web) {
      this.ctx = ctx;
      this.web = web;
      this.client = Nearby.getConnectionsClient(ctx);
      this.prefs = ctx.getSharedPreferences("rabeto", Context.MODE_PRIVATE);
      this.ident = new Ident(prefs);
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
          if (bytes == null) return;
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
          JSONObject o = new JSONObject();
          o.put("t", "hello");
          o.put("id", myId);
          o.put("name", myName);
          o.put("pk", ident.pubKey());
          client.sendPayload(endpointId, Payload.fromBytes(o.toString().getBytes(StandardCharsets.UTF_8)));
      } catch (Throwable ignored) {}
  }

  private void handleHello(String endpointId, JSONObject o) {
      String uid = o.optString("id", "");
      String nm = o.optString("name", uid);
      String pk = o.optString("pk", "");
      if (uid.length() == 0) return;
      names.put(uid, nm);
      learnKey(uid, pk);
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
      return e.optString("id") + "|" + e.optString("from") + "|" + e.optString("name") + "|"
              + e.optString("to") + "|" + e.optString("kind") + "|" + e.optLong("ts") + "|"
              + e.optInt("enc") + "|" + e.optString("text") + "|" + e.optString("data");
  }

  private void handleIncoming(String fromEndpoint, JSONObject env) {
      String mid = env.optString("id", "");
      if (mid.length() == 0 || seen.contains(mid)) return;
      remember(mid);

      String from = env.optString("from", "");
      String pk = env.optString("pk", "");
      boolean isNew = !pubKeys.containsKey(from) || !names.containsKey(from);
      int keyState = learnKey(from, pk);
      boolean sigOk = false;
      if (keyState == 1) sigOk = Ident.verify(pk, canonical(env), env.optString("sig", ""));

      // کسی که پیامش از راه واسطه رسیده هم باید در لیست مخاطبان ظاهر شود
      if (keyState == 1 && !from.equals(myId)) {
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
              out.put("verified", sigOk);
              out.put("keyChanged", keyState == -2);
              out.put("fake", keyState == -1);
              if (env.optInt("enc", 0) == 1 && myId.equals(to)) {
                  String txt = ident.decrypt(pk, env.optString("text", ""));
                  String dat = env.optString("data", "").length() > 0
                          ? ident.decrypt(pk, env.optString("data", "")) : "";
                  out.put("text", txt == null ? "" : txt);
                  out.put("data", dat == null ? "" : dat);
                  out.put("opened", txt != null);
              }
          } catch (Throwable ignored) {}
          emit(wrap("message", out));

          // رسید خودکار: به فرستنده خبر بده که پیام رسید
          if (myId.equals(to) && sigOk && !"ack".equals(env.optString("kind", ""))) {
              sendEnvelope(from, "ack", mid, "", false);
          }
      }

      if (!myId.equals(to)) {
          int ttl = env.optInt("ttl", 0);
          if (ttl > 0) {
              JSONObject fwd = clone(env);
              try {
                  fwd.put("ttl", ttl - 1);
                  fwd.put("hops", env.optInt("hops", 0) + 1);
              } catch (Throwable ignored) {}
              sendRaw(fwd, fromEndpoint);
          }
      }
  }

  /** ساخت و فرستادن یک پیام. اگر گیرنده مشخص باشد، متن رمز می‌شود. */
  private String sendEnvelope(String to, String kind, String text, String data, boolean wantEncrypt) {
      try {
          JSONObject e = new JSONObject();
          String mid = myId + "-" + System.currentTimeMillis() + "-" + (int) (Math.random() * 9999);
          boolean enc = false;
          String outText = text == null ? "" : text;
          String outData = data == null ? "" : data;

          if (wantEncrypt && !"*".equals(to)) {
              String theirPk = pubKeys.get(to);
              if (theirPk != null && ident.ok()) {
                  String t2 = ident.encrypt(theirPk, outText);
                  String d2 = outData.length() > 0 ? ident.encrypt(theirPk, outData) : "";
                  if (t2.length() > 0 && (outData.length() == 0 || d2.length() > 0)) {
                      outText = t2; outData = d2; enc = true;
                  }
              }
          }

          e.put("t", "msg");
          e.put("v", 2);
          e.put("id", mid);
          e.put("from", myId);
          e.put("name", myName);
          e.put("to", to);
          e.put("kind", kind);
          e.put("ts", System.currentTimeMillis());
          e.put("enc", enc ? 1 : 0);
          e.put("text", outText);
          e.put("data", outData);
          e.put("pk", ident.pubKey());
          e.put("sig", ident.sign(canonical(e)));
          e.put("ttl", MAX_TTL);
          e.put("hops", 0);

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
          Payload p = Payload.fromBytes(env.toString().getBytes(StandardCharsets.UTF_8));
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
          try { if (c.optInt("ttl", 0) < 1) c.put("ttl", 1); } catch (Throwable ignored) {}
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
          prefs.edit()
                  .putString("cache", arr.toString())
                  .putString("keys", keys.toString())
                  .putString("names", nms.toString())
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
          o.put("peers", peers.size());
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

  private void emitPeersStatus() {
      try {
          JSONObject o = new JSONObject();
          o.put("type", "status");

          JSONObject me = new JSONObject();
          me.put("id", myId);
          me.put("name", myName);
          me.put("code", ident.ok() ? ident.code() : "--------");
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
              known.put(en.getKey(), k);
          }
          o.put("known", known);
          emit(o);
      } catch (Throwable ignored) {}
  }
}
