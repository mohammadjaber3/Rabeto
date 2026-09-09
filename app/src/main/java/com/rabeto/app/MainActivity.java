package com.rabeto.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private WebView web;
    private Mesh mesh;
    private static final int REQ_PERMS = 1001;
    private static final int REQ_FILE = 2001;
    private ValueCallback<Uri[]> filePathCallback;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        setContentView(web);

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                              FileChooserParams params) {
                filePathCallback = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "انتخاب عکس"), REQ_FILE);
                } catch (Throwable t) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        mesh = new Mesh(this, web);
        web.addJavascriptInterface(mesh, "RabetoNative");
        web.loadUrl("file:///android_asset/www/index.html");

        askPermissions();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || filePathCallback == null) return;
        Uri[] result = null;
        if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            result = new Uri[]{data.getData()};
        }
        filePathCallback.onReceiveValue(result);
        filePathCallback = null;
    }

    private void askPermissions() {
        List<String> need = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 31) {
            need.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
            need.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            need.add("android.permission.NEARBY_WIFI_DEVICES");
        }
        if (Build.VERSION.SDK_INT <= 31) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }

        List<String> missing = new ArrayList<String>();
        for (int i = 0; i < need.size(); i++) {
            if (checkSelfPermission(need.get(i)) != PackageManager.PERMISSION_GRANTED) missing.add(need.get(i));
        }
        if (missing.isEmpty()) mesh.start();
        else requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_PERMS) return;
        boolean allOk = results.length > 0;
        for (int i = 0; i < results.length; i++) {
            if (results[i] != PackageManager.PERMISSION_GRANTED) allOk = false;
        }
        if (allOk) mesh.start();
        else mesh.reportError("permissions-denied");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mesh != null) mesh.refresh();
    }

    @Override
    protected void onDestroy() {
        if (mesh != null) mesh.stop();
        super.onDestroy();
    }
}
