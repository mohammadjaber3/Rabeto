package com.rabeto.app.transport;

/**
 * Upward events from a Transport. Implemented by TransportManager, which
 * fans them out to the core engine.
 *
 * Implementations must be cheap and non-blocking; these fire on transport
 * callback threads.
 */
public interface TransportEvents {

    /**
     * @param linkId       namespaced link id
     * @param advertisedTag raw "id|name" tag as announced by the peer.
     *                      UNTRUSTED: it is not authenticated until the signed
     *                      Hello arrives. Use it for display hints only.
     */
    void onLinkUp(String transport, String linkId, String advertisedTag);

    void onLinkDown(String transport, String linkId);

    /** @param quality "wifi" for high bandwidth, "bt" for low bandwidth. */
    void onLinkQuality(String transport, String linkId, String quality);

    void onPacket(String transport, String linkId, byte[] packet);

    void onTransportState(String transport, boolean running, String detail);
}
