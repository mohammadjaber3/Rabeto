package com.rabeto.app.core;

import org.json.JSONObject;

/**
 * The only channel from the engine to any presentation layer.
 *
 * Keeping this to a single method is deliberate: the core must never know
 * whether it is talking to a WebView, a Compose screen, a notification or a
 * test harness. Swapping the UI later means implementing this interface.
 */
public interface CoreEvents {
    void onCoreEvent(String type, JSONObject payload);
}
