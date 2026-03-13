package com.disappointedpig.dpmidi;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import com.disappointedpig.midi.MIDIConstants;
import com.disappointedpig.midi.MIDISession;
import com.disappointedpig.midi.events.MIDIConnectionEndEvent;
import com.disappointedpig.midi.events.MIDIConnectionEstablishedEvent;
import com.disappointedpig.midi.events.MIDIConnectionRequestAcceptedEvent;
import com.disappointedpig.midi.events.MIDIConnectionRequestReceivedEvent;
import com.disappointedpig.midi.events.MIDIConnectionRequestRejectedEvent;
import com.disappointedpig.midi.events.MIDIConnectionSentRequestEvent;
import com.disappointedpig.midi.events.MIDIReceivedEvent;
import com.disappointedpig.midi.events.MIDISessionNameRegisteredEvent;
import com.disappointedpig.midi.events.MIDISessionStartEvent;
import com.disappointedpig.midi.events.MIDISessionStopEvent;
import com.disappointedpig.midi.events.MIDISyncronizationCompleteEvent;
import com.disappointedpig.midi.events.MIDISyncronizationStartEvent;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class MainActivity extends AppCompatActivity {

    private IServiceFunctions service = null;
    private boolean isConnected = false;
    private boolean isConnecting = false;

    // UI elements
    private View statusDot;
    private ProgressBar connectionSpinner;
    private TextView statusText;
    private TextView statusDetail;
    private TextView midiStatusTextView;
    private Button connectButton;
    private TextView midiConnectionStatusTextView;

    SharedPreferences sharedpreferences;

    private ServiceConnection svcConn = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            service = (IServiceFunctions) binder;
            try {
                service.registerActivity(MainActivity.this, listener);
            } catch (Throwable t) {
                Log.e("MainActivity", "Error registering activity", t);
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            service = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        EventBus.getDefault().register(this);
        MIDISession.getInstance().init(DPMIDIApplication.getAppContext());

        Intent startIntent = new Intent(MainActivity.this, ConnectionManagerService.class);
        startIntent.setAction(Constants.ACTION.STARTCMGR_ACTION);
        startService(startIntent);

        bindToCMGRS();

        PdfViewActivity.register(this);

        sharedpreferences = DPMIDIApplication.getAppContext().getSharedPreferences("SCPreferences", Context.MODE_PRIVATE);

        // Auto-start MIDI if it was enabled before
        if (sharedpreferences.getBoolean(Constants.PREF.MIDI_STATE_PREF, true)) {
            Intent midiIntent = new Intent(MainActivity.this, ConnectionManagerService.class);
            midiIntent.setAction(Constants.ACTION.START_MIDI_ACTION);
            startService(midiIntent);
        }

        // Bind UI
        statusDot = findViewById(R.id.statusDot);
        connectionSpinner = findViewById(R.id.connectionSpinner);
        statusText = findViewById(R.id.statusText);
        statusDetail = findViewById(R.id.statusDetail);
        midiStatusTextView = findViewById(R.id.midiStatus);
        connectButton = findViewById(R.id.connectButton);
        midiConnectionStatusTextView = findViewById(R.id.midiConnectionStatus);

        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isConnected || isConnecting) {
                    disconnect();
                } else {
                    connect();
                }
            }
        });

        Button openABButton = findViewById(R.id.openABButton);
        openABButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, AddressBook.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intent);
            }
        });

        Button openPdfButton = findViewById(R.id.openPdfButton);
        openPdfButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, PdfViewActivity.class);
                startActivity(intent);
            }
        });

        updateConnectionUI();
    }

    private void connect() {
        // First ensure MIDI is started
        if (!sharedpreferences.getBoolean(Constants.PREF.MIDI_STATE_PREF, true)) {
            sharedpreferences.edit().putBoolean(Constants.PREF.MIDI_STATE_PREF, true).commit();
            Intent midiIntent = new Intent(MainActivity.this, ConnectionManagerService.class);
            midiIntent.setAction(Constants.ACTION.START_MIDI_ACTION);
            startService(midiIntent);
        }

        isConnecting = true;
        updateConnectionUI();
        setStatus(R.string.status_searching, null, R.color.statusConnecting);

        Bundle rinfo = new Bundle();
        rinfo.putString(MIDIConstants.RINFO_ADDR, "10.209.1.175");
        rinfo.putInt(MIDIConstants.RINFO_PORT, 5004);
        rinfo.putBoolean(MIDIConstants.RINFO_RECON, sharedpreferences.getBoolean(Constants.PREF.RECONNECT_STATE_PREF, false));
        MIDISession.getInstance().connect(rinfo);
    }

    private void disconnect() {
        Bundle rinfo = new Bundle();
        rinfo.putString(MIDIConstants.RINFO_ADDR, "10.209.1.175");
        rinfo.putInt(MIDIConstants.RINFO_PORT, 5004);
        rinfo.putBoolean(MIDIConstants.RINFO_RECON, sharedpreferences.getBoolean(Constants.PREF.RECONNECT_STATE_PREF, false));
        MIDISession.getInstance().disconnect(rinfo);

        isConnected = false;
        isConnecting = false;
        updateConnectionUI();
        setStatus(R.string.status_disconnected, null, R.color.statusDisconnected);
    }

    private void setStatus(int textResId, String detail, int colorResId) {
        statusText.setText(textResId);
        int color = ContextCompat.getColor(this, colorResId);

        // Update dot color
        GradientDrawable dot = (GradientDrawable) statusDot.getBackground();
        dot.setColor(color);

        // Show/hide detail
        if (detail != null) {
            statusDetail.setText(detail);
            statusDetail.setVisibility(View.VISIBLE);
        } else {
            statusDetail.setVisibility(View.GONE);
        }
    }

    private void updateConnectionUI() {
        if (isConnected) {
            connectButton.setText(R.string.btn_disconnect);
            connectButton.setBackgroundResource(R.drawable.btn_disconnect);
            connectionSpinner.setVisibility(View.GONE);
            statusDot.setVisibility(View.VISIBLE);
        } else if (isConnecting) {
            connectButton.setText(R.string.btn_disconnect);
            connectButton.setBackgroundResource(R.drawable.btn_disconnect);
            connectionSpinner.setVisibility(View.VISIBLE);
            statusDot.setVisibility(View.GONE);
        } else {
            connectButton.setText(R.string.btn_connect);
            connectButton.setBackgroundResource(R.drawable.btn_connect);
            connectionSpinner.setVisibility(View.GONE);
            statusDot.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    public void onDestroy() {
        EventBus.getDefault().unregister(this);
        if (service != null) {
            service.unregisterActivity(this);
        }
        try {
            unbindService(svcConn);
        } catch (IllegalArgumentException e) {
            // service was not bound
        }
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public void bindToCMGRS() {
        bindService(new Intent(this, ConnectionManagerService.class), svcConn, BIND_AUTO_CREATE);
    }

    public void unbindFromCMGRS() {
        if (service != null) {
            service.unregisterActivity(this);
        }
        try {
            unbindService(svcConn);
        } catch (IllegalArgumentException e) {
            // not bound
        }
    }

    // ---- EventBus handlers ----

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMIDIConnectionEndEvent(MIDIConnectionEndEvent event) {
        Log.d("MainActivity", "MIDIConnectionEndEvent");
        isConnected = false;
        isConnecting = false;
        updateConnectionUI();
        setStatus(R.string.status_connection_end, null, R.color.statusDisconnected);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMIDIConnectionEstablishedEvent(MIDIConnectionEstablishedEvent event) {
        Log.d("MainActivity", "MIDIConnectionEstablishedEvent");
        isConnected = true;
        isConnecting = false;
        updateConnectionUI();
        setStatus(R.string.status_connected, null, R.color.statusConnected);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMIDIConnectionRequestAcceptedEvent(MIDIConnectionRequestAcceptedEvent event) {
        Log.d("MainActivity", "MIDIConnectionRequestAcceptedEvent");
        isConnecting = true;
        updateConnectionUI();
        setStatus(R.string.status_establishing, null, R.color.statusConnecting);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMIDIConnectionRequestReceivedEvent(MIDIConnectionRequestReceivedEvent event) {
        Log.d("MainActivity", "MIDIConnectionRequestReceivedEvent");
        isConnecting = true;
        updateConnectionUI();
        setStatus(R.string.status_establishing, null, R.color.statusConnecting);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMIDIConnectionRequestRejectedEvent(MIDIConnectionRequestRejectedEvent event) {
        Log.d("MainActivity", "MIDIConnectionRequestRejectedEvent");
        isConnected = false;
        isConnecting = false;
        updateConnectionUI();
        setStatus(R.string.status_error, null, R.color.statusError);
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onMIDIReceivedEvent(MIDIReceivedEvent event) {
        Log.d("MainActivity", "MIDIReceivedEvent");
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMIDISessionNameRegisteredEvent(MIDISessionNameRegisteredEvent event) {
        Log.d("MainActivity", "MIDISessionNameRegisteredEvent");
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMIDISessionStartEvent(MIDISessionStartEvent event) {
        Log.d("MainActivity", "MIDISessionStartEvent");
        midiStatusTextView.setText("MIDI " + MIDISession.getInstance().version());
        midiStatusTextView.setVisibility(View.VISIBLE);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMIDISessionStopEvent(MIDISessionStopEvent event) {
        Log.d("MainActivity", "MIDISessionStopEvent");
        midiStatusTextView.setVisibility(View.GONE);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMIDISyncronizationCompleteEvent(MIDISyncronizationCompleteEvent event) {
        Log.d("MainActivity", "MIDISyncronizationCompleteEvent");
        isConnected = true;
        isConnecting = false;
        updateConnectionUI();
        setStatus(R.string.status_connected, null, R.color.statusConnected);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMIDISyncronizationStartEvent(MIDISyncronizationStartEvent event) {
        Log.d("MainActivity", "MIDISyncronizationStartEvent");
        isConnecting = true;
        updateConnectionUI();
        setStatus(R.string.status_syncing, null, R.color.statusSyncing);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMIDIConnectionSentRequestEvent(MIDIConnectionSentRequestEvent event) {
        Log.d("MainActivity", "MIDIConnectionSentRequestEvent");
        isConnecting = true;
        updateConnectionUI();
        setStatus(R.string.status_invite_sent, null, R.color.statusConnecting);
    }

    private IListenerFunctions listener = new IListenerFunctions() {
        public void midiStateChanged(ConnectionState state) {
            Log.d("MAIN", "midistatechanged " + state.toString());
        }

        public void cmsStarted() {
            Log.d("MAIN", "cms started ");
        }
    };
}
