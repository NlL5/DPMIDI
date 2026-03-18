package com.disappointedpig.dpmidi;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.ToggleButton;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.disappointedpig.midi.MIDIConstants;
import com.disappointedpig.midi.MIDISession;
import com.disappointedpig.midi.events.MIDISessionStartEvent;
import com.disappointedpig.midi.events.MIDISessionStopEvent;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class SettingsActivity extends AppCompatActivity {

    private IServiceFunctions service = null;
    private SharedPreferences sharedpreferences;

    ToggleButton cmServiceToggle, midiSessionToggle, backgroundToggleButton;
    TextView midiStatusTextView;
    EditText bonjourNameEdit;
    Button midiInviteButton, midiEndConnectionButton, testMIDIButton, testHeartbeat;

    private ServiceConnection svcConn = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            service = (IServiceFunctions) binder;
            setButtonStates();
        }

        public void onServiceDisconnected(ComponentName className) {
            service = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        EventBus.getDefault().register(this);

        sharedpreferences = DPMIDIApplication.getAppContext().getSharedPreferences("SCPreferences", Context.MODE_PRIVATE);

        bindService(new Intent(this, ConnectionManagerService.class), svcConn, BIND_AUTO_CREATE);

        cmServiceToggle = findViewById(R.id.cmServiceToggleButton);
        midiSessionToggle = findViewById(R.id.midiSessionToggleButton);
        backgroundToggleButton = findViewById(R.id.backgroundToggleButton);
        midiStatusTextView = findViewById(R.id.midiStatus);
        midiInviteButton = findViewById(R.id.midiInviteButton);
        midiEndConnectionButton = findViewById(R.id.midiEndConnectionButton);
        testMIDIButton = findViewById(R.id.testMIDIButton);
        testHeartbeat = findViewById(R.id.testheartbeat);
        bonjourNameEdit = findViewById(R.id.bonjourNameEdit);

        // Load saved Bonjour name, default to current session name
        String savedName = sharedpreferences.getString(Constants.PREF.BONJOUR_NAME_PREF, "");
        if (savedName.isEmpty()) {
            savedName = MIDISession.getInstance().bonjourName;
        }
        bonjourNameEdit.setText(savedName);

        // Save on focus loss (user taps away from the field)
        bonjourNameEdit.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {
                    saveBonjourName();
                }
            }
        });

        cmServiceToggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    Intent startIntent = new Intent(SettingsActivity.this, ConnectionManagerService.class);
                    startIntent.setAction(Constants.ACTION.STARTCMGR_ACTION);
                    startService(startIntent);
                } else {
                    Intent startIntent = new Intent(SettingsActivity.this, ConnectionManagerService.class);
                    startIntent.setAction(Constants.ACTION.STOPCMGR_ACTION);
                    startService(startIntent);
                }
            }
        });

        midiSessionToggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                sharedpreferences.edit().putBoolean(Constants.PREF.MIDI_STATE_PREF, isChecked).commit();
                Intent startIntent = new Intent(SettingsActivity.this, ConnectionManagerService.class);
                startIntent.setAction(isChecked ? Constants.ACTION.START_MIDI_ACTION : Constants.ACTION.STOP_MIDI_ACTION);
                startService(startIntent);
            }
        });

        backgroundToggleButton.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                ((DPMIDIApplication) getApplicationContext()).setRunInBackground(isChecked);
            }
        });

        midiInviteButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                MIDISession.getInstance().checkAddressBookForReconnect();
            }
        });

        midiEndConnectionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                MIDISession.getInstance().disconnectAll();
            }
        });

        testMIDIButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendTestMIDI();
            }
        });

        testHeartbeat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.e("SettingsActivity", "should trigger test heartbeat");
            }
        });

        setButtonStates();
    }

    private void setButtonStates() {
        if (service != null) {
            cmServiceToggle.setChecked(service.cmsIsRunning());
        }
        midiSessionToggle.setChecked(sharedpreferences.getBoolean(Constants.PREF.MIDI_STATE_PREF, true));
        backgroundToggleButton.setChecked(sharedpreferences.getBoolean(Constants.PREF.BACKGROUND_STATE_PREF, true));
    }

    @Override
    protected void onResume() {
        super.onResume();
        setButtonStates();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveBonjourName();
    }

    private void saveBonjourName() {
        String name = bonjourNameEdit.getText().toString().trim();
        if (!name.isEmpty()) {
            sharedpreferences.edit().putString(Constants.PREF.BONJOUR_NAME_PREF, name).apply();
            MIDISession.getInstance().setBonjourName(name);
        }
    }

    @Override
    public void onDestroy() {
        EventBus.getDefault().unregister(this);
        try {
            unbindService(svcConn);
        } catch (IllegalArgumentException e) {
            // not bound
        }
        super.onDestroy();
    }

    private void sendTestMIDI() {
        Log.d("Settings", "sendTestMidi noteOn 0,127");
        Bundle testMessage = new Bundle();
        testMessage.putInt(MIDIConstants.MSG_COMMAND, 0x09);
        testMessage.putInt(MIDIConstants.MSG_CHANNEL, 0);
        testMessage.putInt(MIDIConstants.MSG_NOTE, 0);
        testMessage.putInt(MIDIConstants.MSG_VELOCITY, 127);
        MIDISession.getInstance().sendMessage(testMessage);

        testMessage = new Bundle();
        Log.d("Settings", "sendTestMidi noteOn 1,127");
        testMessage.putInt(MIDIConstants.MSG_COMMAND, 0x09);
        testMessage.putInt(MIDIConstants.MSG_CHANNEL, 0);
        testMessage.putInt(MIDIConstants.MSG_NOTE, 1);
        testMessage.putInt(MIDIConstants.MSG_VELOCITY, 127);
        MIDISession.getInstance().sendMessage(testMessage);

        Log.d("Settings", "sendTestMidi CC 0,127");
        testMessage.putInt(MIDIConstants.MSG_COMMAND, 0xb0);
        testMessage.putInt(MIDIConstants.MSG_CHANNEL, 0);
        testMessage.putInt(MIDIConstants.MSG_NOTE, 0);
        testMessage.putInt(MIDIConstants.MSG_VELOCITY, 127);
        MIDISession.getInstance().sendMessage(testMessage);

        Log.d("Settings", "sendTestMidi CC 1,127");
        testMessage.putInt(MIDIConstants.MSG_COMMAND, 0xb0);
        testMessage.putInt(MIDIConstants.MSG_CHANNEL, 0);
        testMessage.putInt(MIDIConstants.MSG_NOTE, 1);
        testMessage.putInt(MIDIConstants.MSG_VELOCITY, 127);
        MIDISession.getInstance().sendMessage(testMessage);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMIDISessionStartEvent(MIDISessionStartEvent event) {
        midiStatusTextView.setText("Läuft " + MIDISession.getInstance().version());
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMIDISessionStopEvent(MIDISessionStopEvent event) {
        midiStatusTextView.setText("Gestoppt");
    }
}
