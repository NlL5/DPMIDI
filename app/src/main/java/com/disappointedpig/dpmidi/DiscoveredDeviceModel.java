package com.disappointedpig.dpmidi;

import android.os.Bundle;
import android.view.View;

import com.disappointedpig.midi.MIDIAddressBookEntry;
import com.disappointedpig.midi.MIDISession;

import org.greenrobot.eventbus.EventBus;

import static com.disappointedpig.midi.MIDIConstants.RINFO_ADDR;
import static com.disappointedpig.midi.MIDIConstants.RINFO_NAME;
import static com.disappointedpig.midi.MIDIConstants.RINFO_PORT;
import static com.disappointedpig.midi.MIDIConstants.RINFO_RECON;

/**
 * View model for a discovered (not yet saved) RTP MIDI device.
 * Shows name + address with a plus button to add to address book.
 */
public class DiscoveredDeviceModel implements ViewModel {

    public MIDIAddressBookEntry entry;

    public static DiscoveredDeviceModel newInstance(MIDIAddressBookEntry a) {
        DiscoveredDeviceModel m = new DiscoveredDeviceModel();
        m.entry = a;
        return m;
    }

    @Override
    public int layoutId() {
        return R.layout.row_discovered_device;
    }

    @Override
    public String dataId() {
        return entry.getAddressPort();
    }

    public String getName() { return entry.getName(); }

    public String getAddressPort() { return entry.getAddressPort(); }

    /**
     * Plus button: adds this discovered device to the address book with auto-reconnect on.
     */
    public void onClickAdd(View view) {
        Bundle rinfo = new Bundle();
        rinfo.putString(RINFO_NAME, entry.getName());
        rinfo.putString(RINFO_ADDR, entry.getAddress());
        rinfo.putInt(RINFO_PORT, entry.getPort());
        rinfo.putBoolean(RINFO_RECON, true);
        MIDISession.getInstance().addToAddressBook(rinfo);

        // Post event so AddressBook activity refreshes both lists
        EventBus.getDefault().post(new AddressBookEvent(AddressBookEventType.UPDATED, entry));
    }
}
