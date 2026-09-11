package com.rabeto.app.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Brings the mesh back after a reboot or an app update.
 *
 * Opt-in: a mesh node that silently restarts itself on every boot without the
 * user agreeing is a battery and privacy surprise. The setting defaults to off.
 */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;

        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        SharedPreferences prefs =
                context.getSharedPreferences("rabeto", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("autoStart", false)) return;

        RabetoService.startMesh(context);
    }
}
