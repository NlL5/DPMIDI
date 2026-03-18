package com.disappointedpig.midi.events;

/**
 * Posted when an RTP MIDI device is discovered on the network via mDNS.
 */
public class MIDIDeviceDiscoveredEvent {
    public final String name;
    public final String address;
    public final int port;

    public MIDIDeviceDiscoveredEvent(String name, String address, int port) {
        this.name = name;
        this.address = address;
        this.port = port;
    }
}
