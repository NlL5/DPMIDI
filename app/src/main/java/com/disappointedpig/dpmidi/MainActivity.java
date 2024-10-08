package com.disappointedpig.dpmidi;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.ToggleButton;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

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

//import android.support.design.widget.FloatingActionButton;
//import android.support.design.widget.Snackbar;
//import android.support.v7.widget.RecyclerView;

public class MainActivity extends AppCompatActivity {

    static class ViewHolder {
        TextView text1;
        TextView text2;
        int position;
    }

    private IServiceFunctions service = null;

    private ServiceConnection svcConn = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            service = (IServiceFunctions) binder;

            try {
                service.registerActivity(MainActivity.this, listener);
                setButtonStates();
            } catch (Throwable t) {
                System.err.println(t);
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            service = null;
        }

    };

    SharedPreferences sharedpreferences;

    ToggleButton midiSessionToggle, cmServiceToggle, backgroundToggleButton, reconnectToggleButton;
    TextView midiStatusTextView;

    Button midiInviteButton, midiEndConnectionButton, openABButton, testHeartbeat;

    TextView midiConnectionStatusTextView;

//    ArrayList<MIDIDebugEvent> activityList=new ArrayList<MIDIDebugEvent>();

//    ArrayAdapter<MIDIDebugEvent> adapter;

//    private MIDISession midiSession;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        EventBus.getDefault().register(this);
        MIDISession.getInstance().init(DPMIDIApplication.getAppContext());

        //start cms
        Intent startIntent = new Intent(MainActivity.this, ConnectionManagerService.class);
        startIntent.setAction(Constants.ACTION.STARTCMGR_ACTION);
        startService(startIntent);

        bindToCMGRS();
        // bind to CMS
//        bindService(new Intent(this, ConnectionManagerService.class), svcConn, BIND_AUTO_CREATE);

        sharedpreferences = DPMIDIApplication.getAppContext().getSharedPreferences("SCPreferences", Context.MODE_PRIVATE);

        cmServiceToggle = (ToggleButton) findViewById(R.id.cmServiceToggleButton);

        midiInviteButton = (Button) findViewById(R.id.midiInviteButton);
        midiEndConnectionButton = (Button) findViewById(R.id.midiEndConnectionButton);
        backgroundToggleButton = (ToggleButton) findViewById(R.id.backgroundToggleButton);
        reconnectToggleButton = (ToggleButton) findViewById(R.id.reconnectToggleButton);

        midiSessionToggle = (ToggleButton) findViewById(R.id.midiSessionToggleButton);
        midiStatusTextView = (TextView) findViewById(R.id.midiStatus);

        midiConnectionStatusTextView = (TextView) findViewById(R.id.midiConnectionStatus);

        testHeartbeat = (Button) findViewById(R.id.testheartbeat);
        testHeartbeat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
//                ConnectionManager.GetInstance().testHeartbeat();
                Log.e("MAINActivity","should trigger test heartbeat");
            }
        });


        cmServiceToggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    Intent startIntent = new Intent(MainActivity.this, ConnectionManagerService.class);
                    startIntent.setAction(Constants.ACTION.STARTCMGR_ACTION);
                    startService(startIntent);
                    // bind to CMS
                    bindToCMGRS();
                } else {
                    Intent startIntent = new Intent(MainActivity.this, ConnectionManagerService.class);
                    startIntent.setAction(Constants.ACTION.STOPCMGR_ACTION);
                    startService(startIntent);
                    unbindFromCMGRS();
                }
            }
        });

        backgroundToggleButton.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    ((DPMIDIApplication) getApplicationContext()).setRunInBackground(true);
//                    sharedpreferences.edit().putBoolean(Constants.PREF.MIDI_STATE_PREF, true).apply();
//                    sharedpreferences.edit().putBoolean(Constants.PREF.MIDI_STATE_PREF, true).apply();
                } else {
                    ((DPMIDIApplication) getApplicationContext()).setRunInBackground(false);
//                    sharedpreferences.edit().putBoolean(Constants.PREF.MIDI_STATE_PREF, false).apply();
//                    sharedpreferences.edit().putBoolean(Constants.PREF.MIDI_STATE_PREF, false).commit();
                }
            }
        });

        midiSessionToggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    sharedpreferences.edit().putBoolean(Constants.PREF.MIDI_STATE_PREF,true).commit();
                    Intent startIntent = new Intent(MainActivity.this, ConnectionManagerService.class);
                    startIntent.setAction(Constants.ACTION.START_MIDI_ACTION);
                    startService(startIntent);
                } else {
                    sharedpreferences.edit().putBoolean(Constants.PREF.MIDI_STATE_PREF,false).commit();
                    Intent startIntent = new Intent(MainActivity.this, ConnectionManagerService.class);
                    startIntent.setAction(Constants.ACTION.STOP_MIDI_ACTION);
                    startService(startIntent);
                }
            }
        });

        reconnectToggleButton.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
//                MIDISession.getInstance().setAutoReconnect(isChecked);
                sharedpreferences.edit().putBoolean(Constants.PREF.RECONNECT_STATE_PREF, isChecked).commit();

            }
        });

        midiInviteButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Bundle rinfo = new Bundle();
                rinfo.putString(MIDIConstants.RINFO_ADDR,"10.209.1.175");
                rinfo.putInt(MIDIConstants.RINFO_PORT,5004);
                rinfo.putBoolean(MIDIConstants.RINFO_RECON, sharedpreferences.getBoolean(Constants.PREF.RECONNECT_STATE_PREF, false));
                MIDISession.getInstance().connect(rinfo);
            }
        });

        midiEndConnectionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Bundle rinfo = new Bundle();
                rinfo.putString(MIDIConstants.RINFO_ADDR,"10.209.1.175");
                rinfo.putInt(MIDIConstants.RINFO_PORT,5004);
                rinfo.putBoolean(MIDIConstants.RINFO_RECON, sharedpreferences.getBoolean(Constants.PREF.RECONNECT_STATE_PREF, false));
                MIDISession.getInstance().disconnect(rinfo);
            }
        });

        Button testMIDIButton = (Button) findViewById(R.id.testMIDIButton);
        testMIDIButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendTestMIDI();
            }
        });

        openABButton = findViewById(R.id.openABButton);
        openABButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                Intent intent = new Intent(MainActivity.this, AddressBook.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivityForResult(intent, 1);
            }
        });

        setButtonStates();
    }

    @Override
    protected void onResume() {
        super.onResume();

        setButtonStates();
    }

    private void setButtonStates() {
//        if(service.cmsIsRunning() == true) {
        if(service != null) {
            cmServiceToggle.setChecked(service.cmsIsRunning());
            midiSessionToggle.setChecked(sharedpreferences.getBoolean(Constants.PREF.MIDI_STATE_PREF, false));
            backgroundToggleButton.setChecked(sharedpreferences.getBoolean(Constants.PREF.BACKGROUND_STATE_PREF, false));
            reconnectToggleButton.setChecked(sharedpreferences.getBoolean(Constants.PREF.RECONNECT_STATE_PREF, false));
        }
//        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // Deactivate updates to us so that we dont get callbacks no more.
        service.unregisterActivity(this);

        // Finally stop the service
        unbindService(svcConn);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    public void bindToCMGRS() {
        bindService(new Intent(this, ConnectionManagerService.class), svcConn, BIND_AUTO_CREATE);
    }

    public void unbindFromCMGRS() {
        service.unregisterActivity(this);
        unbindService(svcConn);
    }


    public void sendTestMIDI() {
        Log.d("Main","sendTestMidi 41,127");
        Bundle testMessage = new Bundle();
        testMessage.putInt(MIDIConstants.MSG_COMMAND,0x09);
        testMessage.putInt(MIDIConstants.MSG_CHANNEL,0);
        testMessage.putInt(MIDIConstants.MSG_NOTE,41);
        testMessage.putInt(MIDIConstants.MSG_VELOCITY,127);
//        MIDIMessage m = MIDIMessage.newUsing(testMessage);
        MIDISession.getInstance().sendMessage(testMessage);
//        MIDISession.getInstance().sendMessage(41,127);
//        MIDIMessage message = MIDISession.getInstance().sendNote(41,127);
//        if(message != null) {
//            MIDISession.getInstance().sendNote(41,127);
//        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMIDIConnectionEndEvent(MIDIConnectionEndEvent event) {
        Log.d("MainActivity","MIDIConnectionEndEvent");
        midiConnectionStatusTextView.setText("connection end");
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMIDIConnectionEstablishedEvent(MIDIConnectionEstablishedEvent event) {
        Log.d("MainActivity","MIDIConnectionEstablishedEvent");
        midiConnectionStatusTextView.setText("connection start");

    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMIDIConnectionRequestAcceptedEvent(MIDIConnectionRequestAcceptedEvent event) {
        Log.d("MainActivity","MIDIConnectionRequestAcceptedEvent");
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMIDIConnectionRequestReceivedEvent(MIDIConnectionRequestReceivedEvent event) {
        Log.d("MainActivity","MIDIConnectionRequestReceivedEvent");
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMIDIConnectionRequestRejectedEvent(MIDIConnectionRequestRejectedEvent event) {
        Log.d("MainActivity","MIDIConnectionRequestRejectedEvent");
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onMIDIReceivedEvent(MIDIReceivedEvent event) {
        Log.d("MainActivity","MIDIReceivedEvent");
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMIDISessionNameRegisteredEvent(MIDISessionNameRegisteredEvent event) {
        Log.d("MainActivity","MIDISessionNameRegisteredEvent");
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMIDISessionStartEvent(MIDISessionStartEvent event) {
        Log.d("MainActivity","MIDISessionStartEvent");
        midiStatusTextView.setText("running "+MIDISession.getInstance().version());
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMIDISessionStopEvent(MIDISessionStopEvent event) {
        Log.d("MainActivity","MIDISessionStopEvent");
        midiStatusTextView.setText("stopped");
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMIDISyncronizationCompleteEvent(MIDISyncronizationCompleteEvent event) {
        Log.d("MainActivity","MIDISyncronizationCompleteEvent");
        midiConnectionStatusTextView.setText("sync end");
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMIDISyncronizationStartEvent(MIDISyncronizationStartEvent event) {
        Log.d("MainActivity","MIDISyncronizationStartEvent");
        midiConnectionStatusTextView.setText("sync start");
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMIDIConnectionSentRequestEvent(MIDIConnectionSentRequestEvent event) {
        Log.d("MainActivity","MIDISyncronizationStartEvent");
        midiConnectionStatusTextView.setText("sent invite");
    }

    private IListenerFunctions listener = new IListenerFunctions() {
        public void midiStateChanged(ConnectionState state) {
            Log.d("MAIN","midistatechanged "+state.toString());
        }

        public void cmsStarted() {
            Log.d("MAIN","cms started ");
        }
    };

}
