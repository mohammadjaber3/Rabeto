package com.rabeto.app.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.webkit.JavascriptInterface;

import com.rabeto.app.Protocol;
import com.rabeto.app.core.RabetoCore;
import com.rabeto.app.storage.MessageStore;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The entire surface the WebView can reach.
 *
 * Before Phase A the whole Mesh object was handed to JavaScript via
 * addJavascriptInterface. Every public method on it, including transport and
 * key handling, was one typo away from being callable by any script running in
 * the WebView. That is an enormous attack surface for a security product.
 *
 * This class is the contract instead: a short, fixed list of @JavascriptInterface
 * methods, each one validating its own arguments, each one returning JSON
 * strings rather than live objects. The core is private and never leaks out.
 *
 * Every method here runs on a WebView JS thread, so nothing may block.
 */
public final class RabetoBridge {

    /** Bumped whenever this contract changes, so the UI can refuse to run. */
    public static final int BRIDGE_API = 1;

    public interface Host {
        /** Push an event into the page. Implementation hops to the UI thread. */
        void emit(String type, String json);
        void requestRestart();
    }

    private final Context ctx;
    private final RabetoCore core;
    private final Host host;

    public RabetoBridge(Context ctx, RabetoCore core, Host host) {
        this.ctx = ctx.getApplicationContext();
        this.core = core;
        this.host = host;
    }

    // ------------------------------------------------------------------- identity

    @JavascriptInterface
    public int api() {
        return BRIDGE_API;
    }

    @JavascriptInterface
    public String identity() {
        return core.identity().toString();
    }

    @JavascriptInterface
    public String verificationCode() {
        return core.verificationCode();
    }

    @JavascriptInterface
    public void setProfile(String name, String avatar) {
        core.setProfile(name, avatar);
    }

    // --------------------------------------------------------------------- status

    @JavascriptInterface
    public String status() {
        return core.status().toString();
    }

    @JavascriptInterface
    public String contacts() {
        return core.contacts().toString();
    }

    // ------------------------------------------------------------------- messages

    /**
     * @return message id, draft id, or "" when the message was refused.
     *         An empty string is not a bug: a directed message that cannot be
     *         encrypted is never sent in the clear.
     */
    @JavascriptInterface
    public String send(String to, String text) {
        if (to == null || text == null) return "";
        String body = text.trim();
        if (body.length() == 0) return "";
        if (body.length() > Protocol.MAX_TEXT_CHARS) {
            body = body.substring(0, Protocol.MAX_TEXT_CHARS);
        }
        return core.sendText(to, body);
    }

    @JavascriptInterface
    public String broadcast(String text) {
        if (text == null) return "";
        String body = text.trim();
        if (body.length() == 0) return "";
        return core.sendBroadcast(body);
    }

    @JavascriptInterface
    public void conversation(final String peerId, final int limit) {
        if (peerId == null || peerId.length() == 0) return;
        final int capped = clamp(limit, 1, 500);
        core.conversation(peerId, capped, new MessageStore.ResultCallback<JSONArray>() {
            @Override
            public void onResult(JSONArray rows) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("peer", peerId);
                    o.put("messages", rows == null ? new JSONArray() : rows);
                    host.emit("conversation", o.toString());
                } catch (Throwable ignored) {}
            }
        });
    }

    @JavascriptInterface
    public void chats(int limit) {
        final int capped = clamp(limit, 1, 500);
        core.recent(capped, new MessageStore.ResultCallback<JSONArray>() {
            @Override
            public void onResult(JSONArray rows) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("messages", rows == null ? new JSONArray() : rows);
                    host.emit("chats", o.toString());
                } catch (Throwable ignored) {}
            }
        });
    }

    @JavascriptInterface
    public void markRead(String peerId) {
        if (peerId == null || peerId.length() == 0) return;
        core.markThreadRead(peerId);
    }

    // ------------------------------------------------------------------- settings

    @JavascriptInterface
    public void setFastMode(boolean fast) {
        core.setFastMode(fast);
    }

    @JavascriptInterface
    public boolean bleEnabled() {
        return core.bleEnabled();
    }

    @JavascriptInterface
    public void setBleEnabled(boolean enabled) {
        if (core.setBleEnabled(enabled)) host.requestRestart();
    }

    /**
     * Rabeto cannot turn a user's radio on behalf of them, and it should not
     * want to. It can only take them to the right settings screen.
     */
    @JavascriptInterface
    public void openRadioSettings(String which) {
        String action;
        if ("wifi".equals(which)) action = Settings.ACTION_WIFI_SETTINGS;
        else if ("bluetooth".equals(which)) action = Settings.ACTION_BLUETOOTH_SETTINGS;
        else action = Settings.ACTION_SETTINGS;

        try {
            Intent i = new Intent(action);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable ignored) {}
    }

    @JavascriptInterface
    public void vibrate(int ms) {
        int duration = clamp(ms, 1, 500);
        try {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(
                        duration, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(duration);
            }
        } catch (Throwable ignored) {}
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }
}
