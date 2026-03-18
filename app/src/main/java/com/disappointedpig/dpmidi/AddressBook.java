package com.disappointedpig.dpmidi;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.disappointedpig.midi.MIDIAddressBookEntry;
import com.disappointedpig.midi.MIDISession;
import com.disappointedpig.midi.events.MIDIDeviceDiscoveredEvent;
import com.disappointedpig.midi.events.MIDIDeviceLostEvent;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

import static com.disappointedpig.dpmidi.Constants.AB_DIALOG_FRAGMENT_KEY;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDialogFragment;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Address book screen with two sections:
 * 1. Discovered devices (via mDNS) — shown at top, filtered to exclude already-saved entries
 * 2. Saved address book entries — with swipe-to-delete and tap-to-edit
 *
 * Discovery starts when the activity opens and stops when it closes.
 */
public class AddressBook extends AppCompatActivity implements AddressBookDialog.AddressBookDialogListener {

    private final String TAG = AddressBook.class.getSimpleName();

    private EmptyRecyclerView mRecyclerView;
    private GenericRecyclerViewAdapter<AddressBookModel> adapter;

    private RecyclerView discoveredRecyclerView;
    private GenericRecyclerViewAdapter<DiscoveredDeviceModel> discoveredAdapter;
    private LinearLayout discoveredSection;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_address_book);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        EventBus.getDefault().register(this);

        discoveredSection = findViewById(R.id.discoveredSection);
        discoveredRecyclerView = findViewById(R.id.discoveredRecyclerView);
        discoveredRecyclerView.setItemAnimator(new DefaultItemAnimator());

        initializeRecyclerView(null);
        refreshDiscoveredDevices();

        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                AddressBookDialog abdialog = new AddressBookDialog();
                abdialog.show(getSupportFragmentManager(), AB_DIALOG_FRAGMENT_KEY);
            }
        });

        MIDISession.getInstance().checkAddressBookForReconnect();
        MIDISession.getInstance().startDiscovery();
    }

    @Override
    protected void onDestroy() {
        MIDISession.getInstance().stopDiscovery();
        EventBus.getDefault().unregister(this);
        super.onDestroy();
    }

    private void initializeRecyclerView(Bundle savedInstanceState) {
        mRecyclerView = (EmptyRecyclerView) findViewById(R.id.abListRecyclerView);
        mRecyclerView.setItemAnimator(new DefaultItemAnimator());
        mRecyclerView.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        View emptyView = findViewById(R.id.list_empty_view);
        mRecyclerView.setEmptyView(emptyView);

        List<AddressBookModel> l = new ArrayList<>();
        if (!MIDISession.getInstance().addressBookIsEmpty()) {
            for (MIDIAddressBookEntry a : MIDISession.getInstance().getAllAddressBook()) {
                l.add(AddressBookModel.newInstance(a));
            }
        }
        adapter = new GenericRecyclerViewAdapter<AddressBookModel>(l, null);
        mRecyclerView.setAdapter(adapter);
    }

    /** Refreshes the discovered devices list, filtering out those already in the address book. */
    private void refreshDiscoveredDevices() {
        ArrayList<MIDIAddressBookEntry> devices = MIDISession.getInstance().getDiscoveredDevices();

        if (devices.isEmpty()) {
            discoveredSection.setVisibility(View.GONE);
        } else {
            discoveredSection.setVisibility(View.VISIBLE);
            List<DiscoveredDeviceModel> models = new ArrayList<>();
            for (MIDIAddressBookEntry entry : devices) {
                models.add(DiscoveredDeviceModel.newInstance(entry));
            }
            discoveredAdapter = new GenericRecyclerViewAdapter<DiscoveredDeviceModel>(models, null);
            discoveredRecyclerView.setAdapter(discoveredAdapter);
        }
    }

    /** Refreshes both lists (address book + discovered devices). */
    private void refreshAll() {
        initializeRecyclerView(null);
        refreshDiscoveredDevices();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onAddressBookEvent(AddressBookEvent event) {
        switch (event.getType()) {
            case TOUCHED:
                Log.d(TAG, "touched ab entry - editing");
                AddressBookDialog abdialog = new AddressBookDialog();
                abdialog.setArguments(event.getEntry().rinfo());
                abdialog.show(getSupportFragmentManager(), AB_DIALOG_FRAGMENT_KEY);
                break;
            case DELETE:
                Log.d(TAG, "delete ab entry");
                MIDISession.getInstance().deleteFromAddressBook(event.getEntry());
                refreshAll();
                break;
            case UPDATED:
                refreshAll();
                break;
        }
    }

    /** Device discovered via mDNS — refresh list on main thread. */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMIDIDeviceDiscoveredEvent(MIDIDeviceDiscoveredEvent event) {
        Log.d(TAG, "Device discovered: " + event.name + " @ " + event.address + ":" + event.port);
        refreshDiscoveredDevices();
    }

    /** Device lost from mDNS — refresh list on main thread. */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMIDIDeviceLostEvent(MIDIDeviceLostEvent event) {
        Log.d(TAG, "Device lost: " + event.name);
        refreshDiscoveredDevices();
    }

    @Override
    public void onAddressBookDialogPositiveClick(AppCompatDialogFragment dialog) {
        Log.d(TAG, "done...");
        refreshAll();
    }

    @Override
    public void onAddressBookDialogNegativeClick(AppCompatDialogFragment dialog) {
        refreshAll();
    }
}
