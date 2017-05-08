package com.disappointedpig.midi;

import android.annotation.TargetApi;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.SparseArray;

import com.disappointedpig.midi.events.MIDIAddressBookEvent;
import com.disappointedpig.midi.events.MIDIConnectionEndEvent;
import com.disappointedpig.midi.events.MIDIConnectionEstablishedEvent;
import com.disappointedpig.midi.events.MIDIReceivedEvent;
import com.disappointedpig.midi.events.MIDISessionNameRegisteredEvent;
import com.disappointedpig.midi.events.MIDISessionStartEvent;
import com.disappointedpig.midi.events.MIDISessionStopEvent;
import com.disappointedpig.midi.events.MIDISyncronizationCompleteEvent;
import com.disappointedpig.midi.events.MIDISyncronizationStartEvent;
import com.disappointedpig.midi.internal_events.AddressBookReadyEvent;
import com.disappointedpig.midi.internal_events.ConnectionEstablishedEvent;
import com.disappointedpig.midi.internal_events.ConnectionFailedEvent;
import com.disappointedpig.midi.internal_events.ListeningEvent;
import com.disappointedpig.midi.internal_events.PacketEvent;
import com.disappointedpig.midi.internal_events.StreamConnectedEvent;
import com.disappointedpig.midi.internal_events.StreamDisconnectEvent;
import com.disappointedpig.midi.internal_events.SyncronizeStartedEvent;
import com.disappointedpig.midi.internal_events.SyncronizeStoppedEvent;

import net.rehacktive.waspdb.WaspDb;
import net.rehacktive.waspdb.WaspFactory;
import net.rehacktive.waspdb.WaspHash;
import net.rehacktive.waspdb.WaspListener;
import net.rehacktive.waspdb.WaspObserver;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Random;

import static android.content.Context.WIFI_SERVICE;
import static com.disappointedpig.midi.MIDIConstants.RINFO_ADDR;
import static com.disappointedpig.midi.MIDIConstants.RINFO_PORT;
import static com.disappointedpig.midi.MIDIConstants.RINFO_RECON;

public class MIDISession {

    private static MIDISession midiSessionInstance;
    private static String TAG = "MIDISession";
    private static String BONJOUR_TYPE = "_apple-midi._udp";
    private static String BONJOUR_SEPARATOR = ".";

    private static boolean DEBUG = false;

    private WaspDb db;
    private WaspHash midiAddressBook;
    private WaspObserver observer;

    private MIDISession() {
        this.rate = 10000;
        this.port = 5004;
        final Random rand = new Random(System.currentTimeMillis());
        this.ssrc = (int) Math.round(rand.nextFloat() * Math.pow(2, 8 * 4));
        this.startTime = (System.currentTimeMillis() / 1000L) * (long)this.rate ;
        this.startTimeHR =  System.nanoTime();
        this.registered_eb = false;
        this.published_bonjour = false;
        this.autoReconnect = false;
    }

    public static MIDISession getInstance() {
        if(midiSessionInstance == null) {
            midiSessionInstance = new MIDISession();
        }
        return midiSessionInstance;
    }

    private Boolean isRunning = false;
    private Context appContext = null;
    private SparseArray<MIDIStream> streams;
    private SparseArray<MIDIStream> pendingStreams;

    public String bonjourName = Build.MODEL;
    public InetAddress bonjourHost = null;
    public int bonjourPort = 0;

    public int port;
    public int ssrc;
    private int readyState;
    private Boolean registered_eb;
    private Boolean published_bonjour;
    private int lastMessageTime;
    private int rate;
    private final long startTime;
    private final long startTimeHR;

    private MIDIPort controlChannel;
    private MIDIPort messageChannel;

    private NsdManager mNsdManager;
    private NsdManager.ResolveListener mResolveListener;
    private NsdManager.DiscoveryListener mDiscoveryListener;
    private NsdManager.RegistrationListener mRegistrationListener;
    private NsdServiceInfo serviceInfo;

    private boolean autoReconnect = false;

    public void init(Context context) {
        this.appContext = context;
        if(!registered_eb) {
            EventBus.getDefault().register(this);
            registered_eb = true;
//            Hawk.init(context).build();
            setupWaspDB();
            dumpAddressBook();
        }
    }

    public void start(Context context) {
        init(context);
        start();
    }

    public void start() {
        if(this.appContext == null) {
            return;
        }
        if(!registered_eb) {
            EventBus.getDefault().register(this);
            registered_eb = true;
        }
        try {
            this.bonjourHost = InetAddress.getByName("127.0.0.1");
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        this.bonjourHost = getWifiAddress();
        this.bonjourPort = this.port;
        controlChannel = MIDIPort.newUsing(this.port);
        controlChannel.start();
        messageChannel = MIDIPort.newUsing(this.port+1);
        messageChannel.start();

        this.streams = new SparseArray<>(2);
        this.pendingStreams = new SparseArray<>(2);
        try {
            initializeResolveListener();
            registerService();
            isRunning = true;
            EventBus.getDefault().post(new MIDISessionStartEvent());
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
    }


    public void stop() {
        if(streams != null) {
            for (int i = 0; i < streams.size(); i++) {
                streams.get(streams.keyAt(i)).sendEnd();
            }
        }
        if(pendingStreams != null) {
            for (int i = 0; i < pendingStreams.size(); i++) {
                pendingStreams.get(pendingStreams.keyAt(i)).sendEnd();
            }
        }

        if(controlChannel != null) {
            controlChannel.stop();
        }
        if(messageChannel != null) {
            messageChannel.stop();
        }
        isRunning = false;

        shutdownNSDListener();
        EventBus.getDefault().post(new MIDISessionStopEvent());

    }


    public void finalize() {
        stop();
        EventBus.getDefault().unregister(this);
        try {
            super.finalize();
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }

    public void connect(final Bundle rinfo) {
        if(isRunning) {
            if(!isAlreadyConnected(rinfo)) {
                Log.d(TAG,"opening connection to "+rinfo);
                MIDIStream stream = new MIDIStream();
                stream.connect(rinfo);
                Log.d(TAG,"put "+stream.initiator_token+" in pendingStreams");
                pendingStreams.put(stream.initiator_token, stream);
            } else {
                Log.e(TAG,"already have open session to "+rinfo.toString());
            }
        } else {
            Log.e(TAG,"MIDI not running");
        }
    }

    public void disconnect(Bundle rinfo) {
        Log.d(TAG,"disconnect "+rinfo);
        MIDIStream s = getStream(rinfo);
        if(s != null) {
            Log.d(TAG,"stream to disconnect : "+s.ssrc);
            s.sendEnd();
        } else {
            Log.e(TAG,"didn't find stream");

        }
    }

    public void disconnect(int remote_ssrc) {
        if(remote_ssrc != 0) {
            streams.get(remote_ssrc).disconnect();
            streams.get(remote_ssrc).shutdown();
            streams.remove(remote_ssrc);
        }

    }

    private MIDIStream getStream(Bundle rinfo) {
        for (int i = 0; i < streams.size(); i++) {
            MIDIStream s = streams.get(streams.keyAt(i));
            if(s.connectionMatch(rinfo)) {
                return s;
            }
        }
        return null;
    }

    public void setAutoReconnect(boolean b) {
        autoReconnect = b;
    }

    public boolean getAutoReconnect() {
        return autoReconnect;
    }

    private Boolean isAlreadyConnected(Bundle rinfo) {
        Log.d(TAG,"isAlreadyConnected "+pendingStreams.size()+" "+streams.size());
        boolean existsInPendingStreams = false;
        boolean existsInStreams = false;
        Log.e(TAG,"checking pendingStreams... ("+pendingStreams.size()+") "+rinfo.toString());
        for (int i = 0; i < pendingStreams.size(); i++) {
            MIDIStream ps = pendingStreams.get(pendingStreams.keyAt(i));
            if((ps != null) && ps.connectionMatch(rinfo)) {
                existsInPendingStreams = true;
                break;
            }
        }

        if(!existsInPendingStreams) {
            for (int i = 0; i < streams.size(); i++) {
                MIDIStream s = streams.get(streams.keyAt(i));
                if(s == null) {
                    Log.e(TAG,"error in isAlreadyConnected "+i+" rinfo "+rinfo.toString());
                } else {
                    Log.e(TAG,"checking streams...");
                    if(streams.get(streams.keyAt(i)).connectionMatch(rinfo)) {
                        existsInStreams = true;
                    }
                }
            }
        }
        Log.d(TAG,"existsInPendingStreams:"+(existsInPendingStreams ? "YES" : "NO"));
        Log.d(TAG,"existsInStreams:"+(existsInStreams ? "YES" : "NO"));
        return (existsInPendingStreams || existsInStreams);
//        for (int i = 0; i < streams.size(); i++) {
////            streams.get(streams.keyAt(i)).sendMessage(message);
////            String key = ((MIDIStream)streams.keyAt(i));
////            Bundle b = (MIDIStream)streams. .getRinfo1();
//            Bundle b = streams.get(streams.keyAt(i)).getRinfo1();
//            if(b.getString(MIDIConstants.RINFO_ADDR).equals(rinfo.getString(MIDIConstants.RINFO_ADDR)) && b.getInt(MIDIConstants.RINFO_PORT) == rinfo.getInt(MIDIConstants.RINFO_PORT)) {
//                return true;
//            }
//        }
//        return false;
    }

    public void sendUDPMessage(MIDIControl control, Bundle rinfo) {
//        Log.d("MIDISession","sendUDPMessage:control");
        if(rinfo.getInt(com.disappointedpig.midi.MIDIConstants.RINFO_PORT) % 2 == 0) {
//            Log.d("MIDISession", "sendUDPMessage control 5004 rinfo:" + rinfo.toString());
//            controlChannel.sendMidi(control, rinfo);
            controlChannel.sendMidi(control, rinfo);
        } else {
//            Log.d("MIDISession","sendUDPMessage control 5005 rinfo:"+rinfo.toString());
//            messageChannel.sendMidi(control, rinfo);
            messageChannel.sendMidi(control, rinfo);
        }
    }

    public void sendUDPMessage(MIDIMessage m, Bundle rinfo) {
//        Log.d("MIDISession","sendUDPMessage:message");
        if(m != null && rinfo != null) {
            if (rinfo.getInt(com.disappointedpig.midi.MIDIConstants.RINFO_PORT) % 2 == 0) {
                controlChannel.sendMidi(m, rinfo);
            } else {
                messageChannel.sendMidi(m, rinfo);
            }
        }
    }

    public void sendMessage(Bundle m) {
        if(published_bonjour && streams.size() > 0) {
//            Log.d("MIDISession", "sendMessage c:"+m.getInt("command",0x09)+" ch:"+m.getInt("channel",0)+" n:"+m.getInt("note",0)+" v:"+m.getInt("velocity",0));

            MIDIMessage message = new MIDIMessage();
            message.createNote(
                    m.getInt(com.disappointedpig.midi.MIDIConstants.MSG_COMMAND,0x09),
                    m.getInt(com.disappointedpig.midi.MIDIConstants.MSG_CHANNEL,0),
                    m.getInt(com.disappointedpig.midi.MIDIConstants.MSG_NOTE,0),
                    m.getInt(com.disappointedpig.midi.MIDIConstants.MSG_VELOCITY,0));
            message.ssrc = this.ssrc;

            for (int i = 0; i < streams.size(); i++) {
                streams.get(streams.keyAt(i)).sendMessage(message);
            }
        }
    }

    public void sendMessage(int note, int velocity) {
        if(published_bonjour && streams.size() > 0) {
//            Log.d("MIDISession", "note:" + note + " velocity:" + velocity);

            MIDIMessage message = new MIDIMessage();
            message.createNote(note, velocity);
            message.ssrc = this.ssrc;

            for (int i = 0; i < streams.size(); i++) {
                streams.get(streams.keyAt(i)).sendMessage(message);
            }
        }
    }

    // TODO : figure out what this is supposed to return... becuase I don't think this is right
    // getNow returns a unix (long)timestamp
    public long getNow() {
        long hrtime = System.nanoTime()-this.startTimeHR;
        long result = Math.round((hrtime / 1000L / 1000L / 1000L) * this.rate) ;
        return result;
    }


    // streamConnectedEvent is called when client initiates connection... ...
    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onStreamConnected(StreamConnectedEvent e) {
        Log.d("MIDISession","StreamConnectedEvent");
        Log.d(TAG,"get "+e.initiator_token+" from pendingStreams");
        MIDIStream stream = pendingStreams.get(e.initiator_token);

        if(stream != null) {
            Log.d(TAG,"put "+e.initiator_token+" in  streams");
            streams.put(stream.ssrc, stream);
        }
        Log.d(TAG,"remove "+e.initiator_token+" from pendingStreams");

        pendingStreams.delete(e.initiator_token);
//        EventBus.getDefault().post(new MIDIConnectionEstablishedEvent());
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onMIDI2ListeningEvent(ListeningEvent e) {

    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onSyncronizeStartedEvent(SyncronizeStartedEvent e) {
//        Log.d("MIDISession","SyncronizeStartedEvent");

        EventBus.getDefault().post(new MIDISyncronizationStartEvent(e.rinfo));
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onSyncronizeStoppedEvent(SyncronizeStoppedEvent e) {
//        Log.d("MIDISession","SyncronizeStoppedEvent");
        EventBus.getDefault().post(new MIDISyncronizationCompleteEvent(e.rinfo));
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onConnectionEstablishedEvent(ConnectionEstablishedEvent e) {
        Log.d("MIDISession","ConnectionEstablishedEvent");
        EventBus.getDefault().post(new MIDIConnectionEstablishedEvent(e.rinfo));
        addToAddressBook(e.rinfo);

    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onPacketEvent(PacketEvent e) {
//        Log.d("MIDISession","PacketEvent packet from "+e.getAddress().getHostAddress()+":"+e.getPort());

        // try control first
        MIDIControl applecontrol = new MIDIControl();
        MIDIMessage message = new MIDIMessage();

        if(applecontrol.parse(e)) {
//            Log.d("MIDISession","- parsed as apple control packet");
            if(applecontrol.isValid()) {
//                applecontrol.dumppacket();

                if(applecontrol.initiator_token != 0) {
                    MIDIStream pending = pendingStreams.get(applecontrol.initiator_token);
                    if (pending != null) {
                        Log.d("MIDISession", " - got pending stream by token");
                        pending.handleControlMessage(applecontrol, e.getRInfo());
                        return;
                    }
                }
                // check if this applecontrol.ssrc is known stream
                MIDIStream stream = streams.get(applecontrol.ssrc);

                if(stream == null) {
                    // else, check if this is an invitation
                    //       create stream and tell stream to handle invite
//                    Log.d("MIDISession","- create new stream");
                    stream = new MIDIStream();
                    streams.put(applecontrol.ssrc, stream);
                } else {
//                    Log.d("MIDISession", " - got existing stream by ssrc");

                }
//                Log.d("MIDISession","- pass control packet to stream");

                stream.handleControlMessage(applecontrol, e.getRInfo());
            }
            // control packet
        } else {
//            Log.d("MIDISession","message?");
            message.parseMessage(e);
            if(message.isValid()) {
                EventBus.getDefault().post(new MIDIReceivedEvent(message.toBundle()));
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onStreamDisconnectEvent(StreamDisconnectEvent e) {
//        if(DEBUG) {
//            Log.d(TAG,"onStreamDisconnectEvent - ssrc:"+e.stream_ssrc+" it:"+e.initiator_token+" #streams:"+streams.size()+" #pendstreams:"+pendingStreams.size());
//        }
        MIDIStream a = streams.get(e.stream_ssrc,null);

        if(a == null) {
            Log.d(TAG,"can't find stream with ssrc "+e.stream_ssrc);
        } else {
            Bundle rinfo = (Bundle) a.getRinfo1().clone();
            a.shutdown();
            streams.delete(e.stream_ssrc);

            if(rinfo.getBoolean(MIDIConstants.RINFO_RECON,false)) {
                connect(rinfo);
            }
//            if(autoReconnect) {
//                connect(rinfo);
//            }
        }
        if(e.initiator_token != 0) {
            MIDIStream p = pendingStreams.get(e.initiator_token,null);
            if(p == null) {
                Log.d(TAG,"can't find pending stream with IT "+e.initiator_token);
            } else {
                p.shutdown();
                pendingStreams.delete(e.initiator_token);
            }
        }
        if(e.rinfo != null) {
            EventBus.getDefault().post(new MIDIConnectionEndEvent((Bundle)e.rinfo.clone()));
        }
    }

    @Subscribe
    public void onConnectionFailedEvent(ConnectionFailedEvent e) {
        Log.d(TAG,"onConnectionFailedEvent");
        switch(e.code) {
            case REJECTED_INVITATION:
                Log.d(TAG,"initiator_code "+e.initiator_code);
                pendingStreams.delete(e.initiator_code);
                break;
            default:
                break;

        }
    }

    public InetAddress getWifiAddress() {
        try {
            if(appContext == null) {
                return InetAddress.getByName("127.0.0.1");
            }
            WifiManager wm = (WifiManager) appContext.getSystemService(WIFI_SERVICE);
            byte[] ipbytearray= BigInteger.valueOf(wm.getConnectionInfo().getIpAddress()).toByteArray();
            reverseByteArray(ipbytearray);
            if(ipbytearray.length != 4) {
                return InetAddress.getByName("127.0.0.1");
            }
            return InetAddress.getByAddress(ipbytearray);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static void reverseByteArray(byte[] array) {
        if (array == null) {
            return;
        }
        int i = 0;
        int j = array.length - 1;
        byte tmp;
        while (j > i) {
            tmp = array[j];
            array[j] = array[i];
            array[i] = tmp;
            j--;
            i++;
        }
    }


    // --------------------------------------------
    // bonjour stuff
    //

    public void setBonjourName(String name) {
        this.bonjourName = name;
    }

    private void registerService() throws UnknownHostException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            // Create the NsdServiceInfo object, and populate it.
            serviceInfo = new NsdServiceInfo();

            // The name is subject to change based on conflicts
            // with other services advertised on the same network.

            serviceInfo.setServiceName(this.bonjourName);
            serviceInfo.setServiceType(BONJOUR_TYPE);
            serviceInfo.setHost(this.bonjourHost);
            serviceInfo.setPort(this.bonjourPort);

//            if(DEBUG) {
//                Log.d(TAG,"register service: "+serviceInfo.toString());
//            }
            mNsdManager = (NsdManager) appContext.getApplicationContext().getSystemService(Context.NSD_SERVICE);

            initializeNSDRegistrationListener();

            mNsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, mRegistrationListener);
//            mNsdManager.resolveService(serviceInfo, mResolveListener);
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private void initializeNSDRegistrationListener() {
        mRegistrationListener = new NsdManager.RegistrationListener() {

            @Override
            public void onServiceRegistered(NsdServiceInfo NsdServiceInfo) {
                // Save the service name.  Android may have changed it in order to
                // resolve a conflict, so update the name you initially requested
                // with the name Android actually used.
                Log.d(TAG,"Service Registered "+NsdServiceInfo.toString());
                if(NsdServiceInfo.getServiceName() != null && bonjourName != NsdServiceInfo.getServiceName()) {
                    bonjourName = NsdServiceInfo.getServiceName();
                    serviceInfo.setServiceName(bonjourName);

//                    mNsdManager.resolveService(serviceInfo, mResolveListener);

                }
//                mNsdManager.resolveService(serviceInfo, mResolveListener);

                published_bonjour = true;
                EventBus.getDefault().post(new MIDISessionNameRegisteredEvent());
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                // Registration failed!  Put debugging code here to determine why.
                System.out.print("onRegistrationFailed \n"+serviceInfo.toString()+"\nerror code: "+errorCode);
                published_bonjour = false;
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo arg0) {
                // Service has been unregistered.  This only happens when you call
                // NsdManager.unregisterService() and pass in this listener.
                System.out.print("onServiceUnregistered ");
                published_bonjour = false;
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                // Unregistration failed.  Put debugging code here to determine why.
                System.out.print("onUnregistrationFailed ");
                published_bonjour = false;
            }
        };
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private void initializeResolveListener() {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            mResolveListener = new NsdManager.ResolveListener() {

                @Override
                public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    // Called when the resolve fails.  Use the error code to debug.
                    Log.e(TAG, "Resolve failed" + errorCode);
                }

                @Override
                public void onServiceResolved(NsdServiceInfo serviceInfo) {
                    Log.e(TAG, "Resolve Succeeded. " + serviceInfo);

                }
            };
        }
    }

    private void shutdownNSDListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            try {
                if (mNsdManager != null) {
                    mNsdManager.unregisterService(mRegistrationListener);
                }
//            mNsdManager.stopServiceDiscovery(mDiscoveryListener);
            } catch (IllegalArgumentException e) {
                // absorb stupid listener not registered exception...
            }
        }

    }

    public String version() {
        return BuildConfig.VERSION_NAME;
    }

    // TODO : make this actually work...
    boolean isHostConnectionAllowed(Bundle rinfo) {
        return true;
    }


    // -------------------------------------------------

    public void setupWaspDB() {
        String path = appContext.getFilesDir().getPath();
        String databaseName = "MIDIAddressBook";
        String password = "passw0rd";

        WaspFactory.openOrCreateDatabase(path, databaseName, password, new WaspListener<WaspDb>() {
            @Override
            public void onDone(WaspDb waspDb) {
                db = waspDb;
                midiAddressBook = db.openOrCreateHash("midiAddressBook");
                if (midiAddressBook != null && midiAddressBook.getAllKeys() != null) {
                    Log.d(TAG, "setupWaspDB - count " + midiAddressBook.getAllKeys().size());
                    EventBus.getDefault().post(new AddressBookReadyEvent());
                }

            }
        });

//            db = WaspFactory.openOrCreateDatabase(path, databaseName, password, new WaspListener<WaspDb>() {
//                        @Override
//                        public void onDone(WaspDb waspDb) {
//                            Log.d("WaspFactoryINIT","on done?");
//                        }
//                    });
    }


    public Bundle getEntryFromAddressBook(String key) {
        MIDIAddressBookEntry abe = midiAddressBook.get(key);
        return abe.rinfo();
    }

    public boolean addToAddressBook(Bundle rinfo) {
        String key = rinfoToKey(rinfo);
        Log.d(TAG,"addToAddressBook : "+key+" "+rinfo.toString());
        if(!rinfo.getBoolean(RINFO_RECON, false)) {
            // reinforce false (in case RECON isn't in bundle) - I guess I could
            // iterate over keySet - honestly, I don't know why I'm bothering to do this
            Log.d(TAG,"reinforce false?");
            rinfo.putBoolean(RINFO_RECON,false);
        }
        boolean status = midiAddressBook.put(rinfoToKey(rinfo),new MIDIAddressBookEntry(rinfo));
        if(status) {
            Log.d(TAG,"status is good");
            EventBus.getDefault().post(new MIDIAddressBookEvent());
        }

        Log.d(TAG,"about to dump ab");
        dumpAddressBook();
//        getAllAddressBook();
        return status;
    }

    private String rinfoToKey(Bundle rinfo) {
        return String.format(Locale.ENGLISH,"%1$s:%2$d",rinfo.getString(RINFO_ADDR),rinfo.getInt(RINFO_PORT,1234));
    }

    public boolean addToAddressBook(MIDIAddressBookEntry m) {
        if (midiAddressBook != null) {
            return midiAddressBook.put(rinfoToKey(m.rinfo()),new MIDIAddressBookEntry(m.rinfo()));
        }
        return false;
    }
    public ArrayList<MIDIAddressBookEntry> getAllAddressBook() {
        Log.d(TAG,"getAllAddressBook");
        if(midiAddressBook != null) {
            HashMap<String, MIDIAddressBookEntry> hm = midiAddressBook.getAllData();
            Log.d(TAG,"value count: "+hm.values().size());
            Collection<MIDIAddressBookEntry> values = hm.values();
            ArrayList<MIDIAddressBookEntry> list = new ArrayList<MIDIAddressBookEntry>(values);

            return list;
        }
        return null;
    }

//    // whenever a connect is called, check addressbook to see if we need to
//    // add RECON:true
//    private void checkAddressBookForReconnect(Bundle rinfo) {
//        Bundle abentry = getEntryFromAddressBook(rinfoToKey(rinfo));
//        if(abentry != null) {
//            Log.d(TAG,"checkAddressBookForReconnect : ");
//            rinfo.putBoolean(RINFO_RECON,abentry.getBoolean(RINFO_RECON,false));
//        }
//    }

    private void dumpAddressBook() {
        if(midiAddressBook != null) {
            HashMap<String, MIDIAddressBookEntry> hm = midiAddressBook.getAllData();
            Log.d(TAG, "-----------------------------------------");
            for (String key : hm.keySet()) {
                Log.d(TAG, " (" + key + ") : " + hm.get(key).getAddressPort());
            }
            Log.d(TAG, "-----------------------------------------");
        } else {
            Log.d(TAG, "-----------------MIDI Address Book null-------------");

        }
    }
}
