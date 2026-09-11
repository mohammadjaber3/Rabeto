package com.rabeto.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.rabeto.app.core.CoreEvents;
import com.rabeto.app.service.RabetoService;
import com.rabeto.app.ui.RabetoBridge;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A window onto the mesh. Nothing more.
 *
 * The Activity no longer owns the engine, no longer owns a transport, and no
 * longer keeps the screen on to stay alive. It binds to RabetoService, renders,
 * and gets out of the way. Killing this screen does not touch the network.
 *
 * WebView hardening applied here:
 *   - file and content access disabled (assets still load),
 *   - no cross-origin access from file URLs,
 *   - navigation locked to android_asset; anything else goes to the browser,
 *   - the app declares no INTERNET permission at all, so even a compromised
 *     page has nowhere to phone home to.
 */
public final class MainActivity extends Activity implements CoreEvents, RabetoBridge.Host {

    private static final int REQ_PERMS = 1001;
    private static final String PAGE = "file:///android_asset/www/index.html";

    private WebView web;
    private RabetoService service;
    private RabetoBridge bridge;
    private boolean pageReady = false;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((RabetoService.LocalBinder) binder).service();
            service.attach(MainActivity.this);

            bridge = new RabetoBridge(
                    getApplicationContext(), service.core(), MainActivity.this);
            web.addJavascriptInterface(bridge, "Rabeto");
            web.loadUrl(PAGE);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setGeolocationEnabled(false);
        s.setTextZoom(100);
        if (Build.VERSION.SDK_INT >= 16) {
            s.setAllowFileAccessFromFileURLs(false);
            s.setAllowUniversalAccessFromFileURLs(false);
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl().toString());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                if (service != null) service.core().tick();
            }
        });

        setContentView(web);

        RabetoService.startMesh(this);
        bindService(new Intent(this, RabetoService.class), connection, Context.BIND_AUTO_CREATE);

        askPermissions();
    }

    /** Only the bundled page may load in here. */
    private boolean handleUrl(String url) {
        if (url == null) return true;
        if (url.startsWith("file:///android_asset/")) return false;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable ignored) {}
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (service != null) {
            service.setUiVisible(true);
            service.core().tick();
        }
    }

    @Override
    protected void onPause() {
        if (service != null) service.setUiVisible(false);
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (!pageReady) {
            super.onBackPressed();
            return;
        }
        // Let the page handle in-app navigation first, but never trap the user:
        // if the page did not consume the press we background the app. We do not
        // finish() it, because a messenger should behave like a messenger.
        web.evaluateJavascript(
                "(window.RabetoBack && window.RabetoBack()) ? true : false",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String handled) {
                        if (!"true".equals(handled)) moveTaskToBack(true);
                    }
                });
    }

    @Override
    protected void onDestroy() {
        try {
            if (service != null) service.detach();
            unbindService(connection);
        } catch (Throwable ignored) {}
        // Note: the service is NOT stopped here. That is the whole point.
        if (web != null) web.destroy();
        super.onDestroy();
    }

    // ------------------------------------------------------------------- CoreEvents

    @Override
    public void onCoreEvent(String type, JSONObject payload) {
        emit(type, payload == null ? "{}" : payload.toString());
    }

    @Override
    public void emit(final String type, final String json) {
        if (web == null) return;
        final String script = "window.RabetoEvent && window.RabetoEvent("
                + quote(type) + "," + json + ")";
        web.post(new Runnable() {
            @Override
            public void run() {
                if (!pageReady || web == null) return;
                try {
                    web.evaluateJavascript(script, null);
                } catch (Throwable ignored) {}
            }
        });
    }

    @Override
    public void requestRestart() {
        RabetoService.stopMesh(this);
        web.postDelayed(new Runnable() {
            @Override
            public void run() {
                RabetoService.startMesh(MainActivity.this);
            }
        }, 800);
    }

    private static String quote(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(' ');
            else sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }

    // ------------------------------------------------------------------ permissions

    private void askPermissions() {
        List<String> need = new ArrayList<String>();

        if (Build.VERSION.SDK_INT >= 31) {
            need.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
            need.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            need.add("android.permission.NEARBY_WIFI_DEVICES");
            need.add("android.permission.POST_NOTIFICATIONS");
        }
        if (Build.VERSION.SDK_INT <= 31) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }

        List<String> missing = new ArrayList<String>();
        for (int i = 0; i < need.size(); i++) {
            if (checkSelfPermission(need.get(i)) != PackageManager.PERMISSION_GRANTED) {
                missing.add(need.get(i));
            }
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_PERMS) return;

        // Partial grants are normal and survivable: notifications may be denied
        // while Bluetooth is granted. The transport manager re-evaluates what it
        // can actually run instead of refusing to start.
        RabetoService.startMesh(this);
        if (service != null) service.core().tick();
    }
}
