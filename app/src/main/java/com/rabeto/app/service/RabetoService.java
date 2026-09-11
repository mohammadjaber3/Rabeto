package com.rabeto.app.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import com.rabeto.app.MainActivity;
import com.rabeto.app.R;
import com.rabeto.app.core.CoreEvents;
import com.rabeto.app.core.RabetoCore;

import org.json.JSONObject;

/**
 * The mesh, as a foreground service.
 *
 * This is the single most important change in Phase A. Previously the engine was
 * a field on MainActivity, created in onCreate and destroyed in onDestroy, which
 * meant:
 *
 *   - closing the app killed discovery,
 *   - nobody could relay for an offline peer,
 *   - "your message arrives when they open the app" was impossible,
 *   - and FLAG_KEEP_SCREEN_ON was being used as a substitute, which just burned
 *     battery without solving anything.
 *
 * Now the Activity is optional. It binds to observe, and unbinding changes
 * nothing about the network.
 */
public final class RabetoService extends Service implements CoreEvents {

    private static final String TAG = "RabetoService";

    public static final String ACTION_START = "com.rabeto.app.START";
    public static final String ACTION_STOP = "com.rabeto.app.STOP";

    private static final String CHANNEL_STATUS = "rabeto_status";
    private static final String CHANNEL_MESSAGES = "rabeto_messages";
    private static final int NOTIFICATION_ID = 0x2A;

    private static final long TICK_MS = 20_000L;

    private final IBinder binder = new LocalBinder();

    private RabetoCore core;
    private HandlerThread worker;
    private Handler workerHandler;
    private PowerManager.WakeLock wakeLock;

    private volatile CoreEvents listener;
    private volatile boolean uiVisible = false;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                if (core != null) core.tick();
            } catch (Throwable t) {
                Log.w(TAG, "tick failed: " + t.getMessage());
            }
            if (workerHandler != null) workerHandler.postDelayed(this, TICK_MS);
        }
    };

    public final class LocalBinder extends Binder {
        public RabetoService service() {
            return RabetoService.this;
        }
    }

    public static void startMesh(Context ctx) {
        Intent i = new Intent(ctx, RabetoService.class);
        i.setAction(ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Throwable t) {
            Log.w(TAG, "startMesh: " + t.getMessage());
        }
    }

    public static void stopMesh(Context ctx) {
        Intent i = new Intent(ctx, RabetoService.class);
        i.setAction(ACTION_STOP);
        try {
            ctx.startService(i);
        } catch (Throwable t) {
            Log.w(TAG, "stopMesh: " + t.getMessage());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();

        core = new RabetoCore(this, this);

        worker = new HandlerThread("rabeto-core");
        worker.start();
        workerHandler = new Handler(worker.getLooper());

        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rabeto:mesh");
                wakeLock.setReferenceCounted(false);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }

        // startForeground must happen fast or Android kills us with an ANR.
        startForeground(NOTIFICATION_ID, buildStatusNotification(
                getString(R.string.service_detail)));

        try {
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
        } catch (Throwable ignored) {}

        if (core != null && !core.isRunning()) core.start();

        if (workerHandler != null) {
            workerHandler.removeCallbacks(tick);
            workerHandler.postDelayed(tick, TICK_MS);
        }

        // START_STICKY: if the OS reclaims us under memory pressure, come back.
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        listener = null;
        uiVisible = false;
        // Returning true means onRebind is called next time. The mesh keeps
        // running either way; the UI is a spectator.
        return true;
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private void shutdown() {
        if (workerHandler != null) workerHandler.removeCallbacks(tick);
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {}
        if (core != null) core.close();
        if (worker != null) worker.quitSafely();
        try {
            stopForeground(true);
        } catch (Throwable ignored) {}
        stopSelf();
    }

    // ------------------------------------------------------------------- UI hooks

    public RabetoCore core() {
        return core;
    }

    public void attach(CoreEvents events) {
        this.listener = events;
        this.uiVisible = true;
        if (core != null) {
            core.tick();
        }
    }

    public void detach() {
        this.listener = null;
        this.uiVisible = false;
    }

    public void setUiVisible(boolean visible) {
        this.uiVisible = visible;
    }

    // ---------------------------------------------------------------- core events

    @Override
    public void onCoreEvent(String type, JSONObject payload) {
        CoreEvents target = listener;
        if (target != null) {
            try {
                target.onCoreEvent(type, payload);
            } catch (Throwable t) {
                Log.w(TAG, "listener failed: " + t.getMessage());
            }
        }

        if ("status".equals(type)) {
            updateStatusNotification(payload);
        } else if ("message".equals(type)) {
            maybeNotifyMessage(payload);
        }
    }

    private void maybeNotifyMessage(JSONObject payload) {
        if (payload == null) return;
        if (payload.optBoolean("mine", false)) return;
        if (uiVisible) return;

        String name = payload.optString("name", payload.optString("from", ""));
        String text = payload.optString("text", "");
        if (text.length() > 120) text = text.substring(0, 120) + "…";

        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CHANNEL_MESSAGES)
                    : new Notification.Builder(this);

            b.setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle(name)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setContentIntent(contentIntent());

            nm.notify(name.hashCode(), b.build());
        } catch (Throwable t) {
            Log.w(TAG, "notify failed: " + t.getMessage());
        }
    }

    private void updateStatusNotification(JSONObject status) {
        if (status == null) return;
        int links = status.optInt("links", 0);
        boolean bt = status.optBoolean("bluetooth", false);
        boolean wifi = status.optBoolean("wifi", false);

        String detail;
        if (links > 0) {
            detail = links + " دستگاه متصل";
        } else if (!bt && !wifi) {
            detail = "بلوتوث و وای‌فای خاموش است";
        } else {
            detail = getString(R.string.service_detail);
        }

        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildStatusNotification(detail));
        } catch (Throwable ignored) {}
    }

    private Notification buildStatusNotification(String detail) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_STATUS)
                : new Notification.Builder(this);

        b.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(getString(R.string.service_running))
                .setContentText(detail)
                .setOngoing(true)
                .setContentIntent(contentIntent());

        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    private PendingIntent contentIntent() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getActivity(this, 0, open, flags);
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            NotificationChannel status = new NotificationChannel(
                    CHANNEL_STATUS,
                    getString(R.string.service_channel),
                    NotificationManager.IMPORTANCE_LOW);
            status.setShowBadge(false);
            nm.createNotificationChannel(status);

            NotificationChannel messages = new NotificationChannel(
                    CHANNEL_MESSAGES,
                    "پیام‌ها",
                    NotificationManager.IMPORTANCE_HIGH);
            messages.enableVibration(true);
            nm.createNotificationChannel(messages);
        } catch (Throwable t) {
            Log.w(TAG, "channels: " + t.getMessage());
        }
    }
}
