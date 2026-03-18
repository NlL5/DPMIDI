package com.disappointedpig.midi.events;

/**
 * Posted when a previously discovered RTP MIDI device disappears from the network.
 */
public class MIDIDeviceLostEvent {
    public final String name;

    public MIDIDeviceLostEvent(String name) {
        this.name = name;
    }
}
