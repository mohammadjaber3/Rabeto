package com.rabeto.app.transport;

import java.util.List;

/**
 * One way of moving bytes to a nearby device.
 *
 * A Transport knows nothing about messages, identities, encryption or routing.
 * It only opens links, hands raw packets up, and sends raw packets down.
 * That boundary is what lets Rabeto add Wi-Fi Direct, LoRa, USB or ultrasonic
 * later without touching a single line of messaging logic.
 *
 * Link ids are namespaced by the transport ("nearby:AB12", "ble:C4:9F:..."),
 * so two transports can never collide and the manager can always tell which
 * transport owns a link.
 */
public interface Transport {

    /** Stable short name, also the link-id prefix. */
    String name();

    /** Hardware and platform support, independent of whether the radio is on. */
    boolean isSupported();

    /** True when the underlying radio is currently usable. */
    boolean isRadioReady();

    /** Identity tag advertised to peers. Called before start(). */
    void setLocalTag(String localId, String localName);

    void start();

    void stop();

    boolean isRunning();

    /** Send to one link. Returns false if the link is gone or the packet is too big. */
    boolean send(String linkId, byte[] packet);

    /** Currently established links. */
    List<String> links();

    /** Largest packet this transport will accept. */
    int maxPacketBytes();

    /** Human readable state for the UI / notification. */
    String stateDetail();
}
