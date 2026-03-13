package com.disappointedpig.midi;

import android.annotation.TargetApi;
import android.content.Context;
import android.net.ConnectivityManager;

import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
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
import com.esotericsoftware.kryo.KryoException;

import net.rehacktive.waspdb.WaspDb;
import net.rehacktive.waspdb.WaspFactory;
import net.rehacktive.waspdb.WaspHash;
import net.rehacktive.waspdb.WaspListener;
import net.rehacktive.waspdb.WaspObserver;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.greenrobot.eventbus.android.BuildConfig;


import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import static android.content.Context.WIFI_SERVICE;
import static com.disappointedpig.midi.MIDIConstants.RINFO_ADDR;
import static com.disappointedpig.midi.MIDIConstants.RINFO_FAIL;
import static com.disappointedpig.midi.MIDIConstants.RINFO_PORT;
import static com.disappointedpig.midi.MIDIConstants.RINFO_RECON;

/**
 * Central singleton managing RTP MIDI sessions (RFC 4695 / AppleMIDI).
 *
 * Responsibilities:
 * - Opens two UDP ports: control (even, default 5004) and MIDI data (odd, 5005)
 * - Registers the service via mDNS/Bonjour so other devices can discover us
 * - Manages MIDIStream instances (pending connections + established streams)
 * - Persists an address book (WaspDB) for known peers with auto-reconnect
 * - Handles connection failure tracking with backoff retry (3 attempts, then 20s pause)
 * - Listens for network changes to auto-start/stop when WiFi comes/goes
 *
 * Communication pattern: uses GreenRobot EventBus throughout.
 * Internal events (e.g. PacketEvent, ConnectionFailedEvent) stay within the midi module.
 * Public events (e.g. MIDISessionStartEvent, MIDIConnectionEstablishedEvent) are consumed by the app layer.
 */
public class MIDISession {

    private static MIDISession midiSessionInstance;
    private static String TAG = MIDISession.class.getSimpleName();
    private static String BONJOUR_TYPE = "_apple-midi._udp";
    private static String BONJOUR_SEPARATOR = ".";
    private static boolean DEBUG = true;

    private WaspDb db;
    private WaspHash midiAddressBook;  // Persistent key-value store for known MIDI peers
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

    private volatile boolean shouldBeRunning = false;  // true when we want to run but network is unavailable
    private volatile boolean isRunning = false;
    private Context appContext = null;
    private final Map<Integer, MIDIStream> streams = new ConcurrentHashMap<>();         // keyed by remote SSRC
    private final Map<Integer, MIDIStream> pendingStreams = new ConcurrentHashMap<>();   // keyed by initiator_token, moved to streams after handshake
    private final Map<String, Bundle> failedConnections = new ConcurrentHashMap<>();     // keyed by "addr:port", tracks retry attempts

    public String bonjourName = Build.MODEL;
    public InetAddress bonjourHost = null;
    public InetAddress netmask = null;

    public int bonjourPort = 0;

    public int port;
    public int ssrc;
    private int readyState;
    private volatile boolean registered_eb = false;
    private volatile boolean published_bonjour = false;
    private volatile boolean initialized = false;
    private volatile boolean started = false;

    private int lastMessageTime;
    private int rate;
    private long startTime;
    private long startTimeHR;

    private MIDIPort controlChannel;
    private MIDIPort messageChannel;

    private NsdManager mNsdManager;
    private NsdManager.ResolveListener mResolveListener;
    private NsdManager.DiscoveryListener mDiscoveryListener;
    private NsdManager.RegistrationListener mRegistrationListener;
    private NsdServiceInfo serviceInfo;

    private boolean autoReconnect = false;

    public void init(Context context) {
        if(started) {
            return;
        }
        this.appContext = context;
        if(!registered_eb) {
            EventBus.getDefault().register(this);
            registered_eb = true;
        }
        if(!initialized) {
            setupWaspDB();

            initialized = true;
        }
    }

    public void start(Context context) {
        init(context);
        start();
    }

    public void start() {
        setupNetworkListener();
        if(!isOnline()) {
            Log.d(TAG,"MIDI Start : not online");
            shouldBeRunning = true;
            return;
        }
        if(this.appContext == null) {
            Log.d(TAG,"MIDI Start : ctx is null");
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

        // Reset timestamp base on each start for accurate sync
        this.startTime = (System.currentTimeMillis() / 1000L) * (long)this.rate;
        this.startTimeHR = System.nanoTime();

        // RTP MIDI uses two UDP ports: even = control channel, odd = MIDI data channel
        controlChannel = MIDIPort.newUsing(this.port);
        controlChannel.start();
        messageChannel = MIDIPort.newUsing(this.port+1);
        messageChannel.start();

        this.streams.clear();
        this.pendingStreams.clear();
        this.failedConnections.clear();
        try {
            initializeResolveListener();
            registerService();
            isRunning = true;
            shouldBeRunning = false;

            EventBus.getDefault().post(new MIDISessionStartEvent());
            checkAddressBookForReconnect();
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
    }


    public void stop() {
        if(!isRunning) {
            return;
        }
        isRunning = false;

        for (MIDIStream s : streams.values()) {
            try { s.sendEnd(); } catch (Exception e) { Log.e(TAG, "Error sending end to stream", e); }
        }
        for (MIDIStream s : pendingStreams.values()) {
            try { s.sendEnd(); } catch (Exception e) { Log.e(TAG, "Error sending end to pending stream", e); }
        }

        // Shutdown all streams properly
        for (MIDIStream s : streams.values()) {
            try { s.shutdown(); } catch (Exception e) { Log.e(TAG, "Error shutting down stream", e); }
        }
        for (MIDIStream s : pendingStreams.values()) {
            try { s.shutdown(); } catch (Exception e) { Log.e(TAG, "Error shutting down pending stream", e); }
        }
        streams.clear();
        pendingStreams.clear();
        failedConnections.clear();

        if(controlChannel != null) {
            controlChannel.stop();
            controlChannel = null;
        }
        if(messageChannel != null) {
            messageChannel.stop();
            messageChannel = null;
        }

        // Reset state so start() works again
        started = false;
        published_bonjour = false;

        shutdownNSDListener();
        EventBus.getDefault().post(new MIDISessionStopEvent());
    }


    public void finalize() {
        stop();
        removeNetworkListener();

        registered_eb = false;
        EventBus.getDefault().unregister(this);
        try {
            super.finalize();
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }

    public synchronized void connect(final Bundle rinfo) {
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

    public void disconnectAll() {
        Log.d(TAG, "disconnectAll - " + streams.size() + " streams");
        for (MIDIStream s : streams.values()) {
            s.sendEnd();
        }
    }

    public void disconnect(int remote_ssrc) {
        if(remote_ssrc != 0) {
            MIDIStream s = streams.get(remote_ssrc);
            if(s != null) {
                s.disconnect();
                s.shutdown();
                streams.remove(remote_ssrc);
            }
        }
    }

    private MIDIStream getStream(Bundle rinfo) {
        for (MIDIStream s : streams.values()) {
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

    private boolean isAlreadyConnected(Bundle rinfo) {
        Log.d(TAG,"isAlreadyConnected pending:"+pendingStreams.size()+" streams:"+streams.size());
        for (MIDIStream ps : pendingStreams.values()) {
            if(ps != null && ps.connectionMatch(rinfo)) {
                Log.d(TAG,"existsInPendingStreams: YES");
                return true;
            }
        }
        for (MIDIStream s : streams.values()) {
            if(s != null && s.connectionMatch(rinfo)) {
                Log.d(TAG,"existsInStreams: YES");
                return true;
            }
        }
        return false;
    }

    // Routes UDP packets to the correct channel based on port parity (even=control, odd=data)
    public void sendUDPMessage(MIDIControl control, Bundle rinfo) {
        if(control == null || rinfo == null) {
            Log.e(TAG,"rinfo or control was null...");
            return;
        }
        if(!isRunning) {
            Log.e(TAG, "sendUDPMessage: session not running");
            return;
        }
        MIDIPort cc = controlChannel;
        MIDIPort mc = messageChannel;
        if(cc == null || mc == null) {
            Log.e(TAG, "sendUDPMessage: channels not ready");
            return;
        }
        if (rinfo.getInt(MIDIConstants.RINFO_PORT) % 2 == 0) {
            cc.sendMidi(control, rinfo);
        } else {
            mc.sendMidi(control, rinfo);
        }
    }

    public void sendUDPMessage(MIDIMessage m, Bundle rinfo) {
        if(m == null || rinfo == null) {
            return;
        }
        if(!isRunning) {
            return;
        }
        MIDIPort cc = controlChannel;
        MIDIPort mc = messageChannel;
        if(cc == null || mc == null) {
            return;
        }
        if (rinfo.getInt(MIDIConstants.RINFO_PORT) % 2 == 0) {
            cc.sendMidi(m, rinfo);
        } else {
            mc.sendMidi(m, rinfo);
        }
    }

    public void sendMessage(Bundle m) {
        if(published_bonjour && !streams.isEmpty()) {
            MIDIMessage message = new MIDIMessage();
            message.createNote(
                    m.getInt(MIDIConstants.MSG_COMMAND,0x09),
                    m.getInt(MIDIConstants.MSG_CHANNEL,0),
                    m.getInt(MIDIConstants.MSG_NOTE,0),
                    m.getInt(MIDIConstants.MSG_VELOCITY,0));
            message.ssrc = this.ssrc;

            for (MIDIStream s : streams.values()) {
                s.sendMessage(message);
            }
        }
    }

    public void sendMessage(int note, int velocity) {
        if(published_bonjour && !streams.isEmpty()) {
            MIDIMessage message = new MIDIMessage();
            message.createNote(note, velocity);
            message.ssrc = this.ssrc;

            for (MIDIStream s : streams.values()) {
                s.sendMessage(message);
            }
        }
    }

    // Returns a timestamp in units of (1/rate) seconds since session start.
    // Used for RTP MIDI synchronization.
    public long getNow() {
        long hrtime = System.nanoTime() - this.startTimeHR;
        // Avoid integer division truncation: compute (hrtime_ns * rate) / 1_000_000_000
        // Split to avoid overflow: first convert ns to microseconds, then scale
        long hrtimeMicros = hrtime / 1000L;
        return (hrtimeMicros * this.rate) / 1_000_000L;
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onAddressBookReadyEvent(AddressBookReadyEvent event) {
        Log.d(TAG,"Addressbook ready");
        checkAddressBookForReconnect();
        dumpAddressBook();
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onStreamConnected(StreamConnectedEvent e) {
        Log.d(TAG,"StreamConnectedEvent - get "+e.initiator_token+" from pendingStreams");
        MIDIStream stream = pendingStreams.get(e.initiator_token);

        if(stream != null) {
            Log.d(TAG,"put ssrc:"+stream.ssrc+" in streams");
            streams.put(stream.ssrc, stream);
        }
        pendingStreams.remove(e.initiator_token);
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
        if(DEBUG) {
            Log.d("MIDISession", "ConnectionEstablishedEvent");
        }
        EventBus.getDefault().post(new MIDIConnectionEstablishedEvent(e.rinfo));
        addToAddressBook(e.rinfo);
        // Clear fail counter on successful connection
        String key = rinfoToKey(e.rinfo);
        failedConnections.remove(key);

    }

    /**
     * Main packet dispatcher: every incoming UDP packet arrives here via EventBus.
     * First tries to parse as AppleMIDI control message (invitation, sync, bye).
     * If that fails, tries to parse as RTP MIDI data (notes, CC, etc.).
     * Pending streams are matched by initiator_token; established streams by SSRC.
     */
    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onPacketEvent(PacketEvent e) {
        if(!isRunning) return;

        MIDIControl applecontrol = new MIDIControl();
        MIDIMessage message = new MIDIMessage();

        if(applecontrol.parse(e)) {
            if(applecontrol.isValid()) {
                // Check pending streams first (connection still being established)
                if(applecontrol.initiator_token != 0) {
                    MIDIStream pending = pendingStreams.get(applecontrol.initiator_token);
                    if (pending != null) {
                        pending.handleControlMessage(applecontrol, e.getRInfo());
                        return;
                    }
                }
                // Then check established streams by remote SSRC
                MIDIStream stream = streams.get(applecontrol.ssrc);

                if(stream == null) {
                    if(DEBUG) {
                        Log.d(TAG, "- create new stream "+applecontrol.ssrc);
                    }
                    stream = new MIDIStream();
                    streams.put(applecontrol.ssrc, stream);
                }

                stream.handleControlMessage(applecontrol, e.getRInfo());
            }
        } else {
            message.parseMessage(e);
            if(message.isValid()) {
                EventBus.getDefault().post(new MIDIReceivedEvent(message.toBundle()));
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onStreamDisconnectEvent(StreamDisconnectEvent e) {
        if(DEBUG) {
            Log.d(TAG,"onStreamDisconnectEvent - ssrc:"+e.stream_ssrc+" it:"+e.initiator_token+" #streams:"+streams.size()+" #pendstreams:"+pendingStreams.size());
        }
        MIDIStream a = streams.get(e.stream_ssrc);

        if(a == null) {
            Log.d(TAG,"can't find stream with ssrc "+e.stream_ssrc);
        } else {
            a.shutdown();
            streams.remove(e.stream_ssrc);
            checkAddressBookForReconnect();
        }
        if(e.initiator_token != 0) {
            MIDIStream p = pendingStreams.get(e.initiator_token);
            if(p == null) {
                Log.d(TAG,"can't find pending stream with IT "+e.initiator_token);
            } else {
                p.shutdown();
                pendingStreams.remove(e.initiator_token);
            }
        }
        if(e.rinfo != null) {
            EventBus.getDefault().post(new MIDIConnectionEndEvent((Bundle)e.rinfo.clone()));
        }

        if(DEBUG) {
            Log.d(TAG,"                     - ssrc:"+e.stream_ssrc+" it:"+e.initiator_token+" #streams:"+streams.size()+" #pendstreams:"+pendingStreams.size());
        }
    }

    /**
     * Handles connection failures with exponential backoff retry.
     * Strategy: retry after 5s, 10s, 15s, then wait 20s and restart the cycle.
     * Port is normalized to base (even) port for tracking, since failures can
     * arrive on either the control port (5004) or data port (5005).
     */
    @Subscribe
    public void onConnectionFailedEvent(ConnectionFailedEvent e) {
        Log.d(TAG,"onConnectionFailedEvent");
        switch(e.code) {
            case REJECTED_INVITATION:
                Log.d(TAG,"...REJECTED_INVITATION initiator_code "+e.initiator_code);
                break;
            case SYNC_FAILURE:
                Log.d(TAG,"...SYNC_FAILURE initiator_code "+e.initiator_code);
                break;
            case UNABLE_TO_CONNECT:
                Log.d(TAG,"...UNABLE_TO_CONNECT initiator_code "+e.initiator_code);
                break;
            case CONNECTION_LOST:
                Log.d(TAG,"...CONNECTION_LOST initiator_code "+e.initiator_code);
                break;
            default:
                break;

        }
        pendingStreams.remove(e.initiator_code);

        // Normalize to base (control) port for fail tracking
        Bundle failRinfo = (Bundle) e.rinfo.clone();
        int rawPort = failRinfo.getInt(RINFO_PORT, 5004);
        if (rawPort % 2 != 0) {
            failRinfo.putInt(RINFO_PORT, rawPort - 1);
        }
        String key = rinfoToKey(failRinfo);

        int failCount;
        if(failedConnections.containsKey(key)) {
            Bundle r = failedConnections.get(key);
            failCount = r.getInt(RINFO_FAIL,0) + 1;
            r.putInt(RINFO_FAIL, failCount);
            failedConnections.put(key,r);
            Log.d(TAG," rinfo: "+r.toString());
        } else {
            failCount = 1;
            failRinfo.putInt(RINFO_FAIL, failCount);
            failedConnections.put(key, failRinfo);
            Log.d(TAG," rinfo: "+failRinfo.toString());
        }

        // After 3 failures, reset counter and wait 20s before retrying
        final int delay;
        if (failCount >= 3) {
            failedConnections.remove(key);
            delay = 20;
            Log.d(TAG, "Reconnect cycle done, retrying in " + delay + "s for " + key);
        } else {
            delay = failCount * 5;
            Log.d(TAG, "Scheduling reconnect in " + delay + "s (attempt " + failCount + ")");
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(delay * 1000L);
                } catch (InterruptedException ignored) {
                    return;
                }
                if (isRunning) {
                    checkAddressBookForReconnect();
                }
            }
        }).start();
    }

//    @TargetApi(21)
//    public InetAddress getWifiAddressNew() {
//
//
//        InetAddress a = null;
//        WifiManager wm = (WifiManager) appContext.getSystemService(WIFI_SERVICE);
//        Network network = wm.getCurrentNetwork();
//        ConnectivityManager cm = (ConnectivityManager)
//                appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
//        NetworkInfo networkInfo = cm.getActiveNetworkInfo();
//
//
//        if(networkInfo.getType() == ConnectivityManager.TYPE_WIFI) {
//            Network network = cm.getActiveNetwork();
//            LinkProperties prop = cm.getLinkProperties();
//
//        }
//        //        LinkProperties prop = cm.getLinkProperties(wifiInfo);
//
//        Iterator<InetAddress> dns = prop.getDnsServers().iterator();
//        while (dns.hasNext()) {
//            Log.d(TAG,"DNS: "+dns.next().getHostAddress());
//        }
//
//        Log.d(TAG,"DNS: "+prop.getDnsServers());
//        Log.d(TAG,"domains: "+prop.getDomains());
//        Log.d(TAG,"imterface: "+prop.getInterfaceName());
//        Log.d(TAG,"string: "+prop.toString());
//        Log.d(TAG,"mask: "+prop.);
//
//        Iterator<LinkAddress> iter = prop.getLinkAddresses().iterator();
//        while(iter.hasNext()) {
//            a = iter.next().getAddress();
//            Log.d(TAG,"address: "+a.getHostAddress());
//        }
//        return a;
//
//    }

    public InetAddress getWifiAddress() {
        // Try modern ConnectivityManager API first (API 23+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    Network activeNetwork = cm.getActiveNetwork();
                    if (activeNetwork != null) {
                        android.net.LinkProperties lp = cm.getLinkProperties(activeNetwork);
                        if (lp != null) {
                            for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                                InetAddress addr = la.getAddress();
                                if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                                    Log.d(TAG, "WiFi address (modern): " + addr.getHostAddress() + "/" + la.getPrefixLength());
                                    netmask = InetAddress.getByName(intToIp(prefixLengthToNetmaskInt(la.getPrefixLength())));
                                    return addr;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Modern network lookup failed, falling back", e);
            }
        }

        // Fallback: iterate network interfaces
        try {
            if(appContext == null) {
                return InetAddress.getByName("127.0.0.1");
            }
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            if(nis == null) return InetAddress.getByName("127.0.0.1");

            while(nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if(ni.isLoopback() || !ni.isUp()) continue;
                for(InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress addr = ia.getAddress();
                    if(!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        Log.d(TAG, "WiFi address (fallback): " + addr.getHostAddress() + "/" + ia.getNetworkPrefixLength());
                        netmask = InetAddress.getByName(intToIp(prefixLengthToNetmaskInt(ia.getNetworkPrefixLength())));
                        return addr;
                    }
                }
            }
            return InetAddress.getByName("127.0.0.1");
        } catch (Exception e) {
            Log.e(TAG, "getWifiAddress failed", e);
            try { return InetAddress.getByName("127.0.0.1"); } catch (UnknownHostException ex) { return null; }
        }
    }

//    public String getLocalIpAddress() {
//        try {
//            for (Enumeration<NetworkInterface> en = NetworkInterface
//                    .getNetworkInterfaces(); en.hasMoreElements();) {
//                NetworkInterface intf = en.nextElement();
//                for (Enumeration<InetAddress> enumIpAddr = intf
//                        .getInetAddresses(); enumIpAddr.hasMoreElements();) {
//                    InetAddress inetAddress = enumIpAddr.nextElement();
//                    System.out.println("ip1--:" + inetAddress);
//                    System.out.println("ip2--:" + inetAddress.getHostAddress());
//
//                    // for getting IPV4 format
//                    if (!inetAddress.isLoopbackAddress() && InetAddressUtils.isIPv4Address(ipv4 = inetAddress.getHostAddress())) {
//
//                        String ip = inetAddress.getHostAddress().toString();
//                        System.out.println("ip---::" + ip);
////                        EditText tv = (EditText) findViewById(R.id.ipadd);
////                        tv.setText(ip);
//                        // return inetAddress.getHostAddress().toString();
//                        return ip;
//                    }
//                }
//            }
//        } catch (Exception ex) {
//            Log.e("IP Address", ex.toString());
//        }
//        return null;
//    }

    public int prefixLengthToNetmaskInt(int prefixLength)
            throws IllegalArgumentException {
        Log.d(TAG,"prefixLengthToNetmaskInt:"+prefixLength);
        if (prefixLength < 0 || prefixLength > 32) {
//            throw new IllegalArgumentException("Invalid prefix length (0 <= prefix <= 32)");
            return 0;

        }
        int value = 0xffffffff << (32 - prefixLength);
        return Integer.reverseBytes(value);
    }

    public int getNetmask(InetAddress addr) {
        try {
            NetworkInterface networkInterface = NetworkInterface.getByInetAddress(addr);
            Log.d(TAG,"    interface: "+addr.getHostAddress());
            for (InterfaceAddress address : networkInterface.getInterfaceAddresses()) {
                Log.d(TAG,"    "+address.getAddress().getHostAddress() + "/" +address.getNetworkPrefixLength());

                int netPrefix = address.getNetworkPrefixLength();
                return netPrefix;
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public String intToIp(int i) {
        i = Integer.reverseBytes(i);
        return ((i >> 24 ) & 0xFF ) + "." +
                ((i >> 16 ) & 0xFF) + "." +
                ((i >> 8 ) & 0xFF) + "." +
                ( i & 0xFF) ;
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
        return BuildConfig.LIBRARY_PACKAGE_NAME;
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
                try {
                    midiAddressBook = db.openOrCreateHash("midiAddressBook");
                    if (midiAddressBook != null && midiAddressBook.getAllKeys() != null) {
                        Log.d(TAG, "setupWaspDB - count " + midiAddressBook.getAllKeys().size());
                        EventBus.getDefault().post(new AddressBookReadyEvent());
                    }
                } catch (KryoException e) {
                    e.printStackTrace();
                    Log.e(TAG,"remove and recreate midiAddressBook");
                    db.removeHash("midiAddressBook");
                    midiAddressBook = db.openOrCreateHash("midiAddressBook");
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
//        if(!rinfo.getBoolean(RINFO_RECON, false)) {
//            // reinforce false (in case RECON isn't in bundle) - I guess I could
//            // iterate over keySet - honestly, I don't know why I'm bothering to do this
//            Log.d(TAG,"reinforce false?");
//            rinfo.putBoolean(RINFO_RECON,false);
//        }

        if(midiAddressBook.get(rinfoToKey(rinfo)) == null) {
            boolean status = midiAddressBook.put(rinfoToKey(rinfo),new MIDIAddressBookEntry(rinfo));
            if(status) {
                Log.d(TAG,"status is good");
                EventBus.getDefault().post(new MIDIAddressBookEvent());
            }

        } else {
            Log.d(TAG,"already in addressbook");
            MIDIAddressBookEntry e =  midiAddressBook.get(rinfoToKey(rinfo));
            e.setReconnect(rinfo.getBoolean(RINFO_RECON,e.getReconnect()));

            boolean status = midiAddressBook.put(rinfoToKey(rinfo),e);
            if(status) {
                Log.d(TAG,"status is good - updated entry");
                EventBus.getDefault().post(new MIDIAddressBookEvent());
            }
        }
        Log.d(TAG,"about to dump ab");
        dumpAddressBook();
//        getAllAddressBook();
        return true;
    }

    private String rinfoToKey(Bundle rinfo) {
        if (rinfo == null) {
            return "_rinfo was null_";
        }
        return String.format(Locale.ENGLISH,"%1$s:%2$d",rinfo.getString(RINFO_ADDR),rinfo.getInt(RINFO_PORT,1234));
    }

    public boolean addToAddressBook(MIDIAddressBookEntry m) {
        if (midiAddressBook != null) {
            return midiAddressBook.put(rinfoToKey(m.rinfo()),new MIDIAddressBookEntry(m.rinfo()));
        }
        return false;
    }

    public boolean deleteFromAddressBook(MIDIAddressBookEntry m) {
        return midiAddressBook.remove(rinfoToKey(m.rinfo()));

    }

    public boolean addressBookIsEmpty() {
        return midiAddressBook == null;
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

    /**
     * Iterates all address book entries and initiates connections to those
     * with auto-reconnect enabled. Called on session start, after disconnects,
     * and on retry after failed connections.
     */
    public void checkAddressBookForReconnect() {
        if(midiAddressBook != null) {
            HashMap<String, MIDIAddressBookEntry> hm = midiAddressBook.getAllData();
            Log.d(TAG, "-----------------------------------------");
            for (String key : hm.keySet()) {
                MIDIAddressBookEntry e = hm.get(key);

                Log.d(TAG, " checking for reconnect - (" + key + ") : " + e.getAddressPort() + " "+(e.getReconnect() ? "YES" : "NO"));
                if(e.getReconnect()) {
                    connect(hm.get(key).rinfo());
                    if (onSameNetwork(hm.get(key).getAddress())) {
                        Log.d(TAG, " same network - (" + key + ") : " + hm.get(key).getAddressPort());
                    } else {
                        Log.d(TAG, " different network -  (" + key + ") : " + hm.get(key).getAddressPort());
                    }
                }
            }
            Log.d(TAG, "-----------------------------------------");
        } else {
            Log.d(TAG, "-----------------MIDI Address Book null-------------");

        }
    }

    public boolean onSameNetwork(String ip) {
        try {
            byte[] a1 = InetAddress.getByName(ip).getAddress();
            byte[] a2 = bonjourHost.getAddress();
            byte[] m = {-1, -1, -1, 0}; // default for case of no-wifi
            if (netmask != null) {
                m = netmask.getAddress();
            }

            for (int i = 0; i < a1.length; i++)
                if ((a1[i] & m[i]) != (a2[i] & m[i]))
                    return false;

            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }



//    public static boolean sameNetwork(String ip1, String ip2, String mask)
//            throws Exception {
//
//        byte[] a1 = InetAddress.getByName(ip1).getAddress();
//        byte[] a2 = InetAddress.getByName(ip2).getAddress();
//        byte[] m = InetAddress.getByName(mask).getAddress();
//
//        for (int i = 0; i < a1.length; i++)
//            if ((a1[i] & m[i]) != (a2[i] & m[i]))
//                return false;
//
//        return true;
//
//    }

    public boolean sameIP(InetAddress a1, InetAddress a2) {
        byte[] b1 = a1.getAddress();
        byte[] b2 = a2.getAddress();
        for (int i = 0; i < b1.length; i++)
            if (b1[i] != b2[i])
                return false;

        return true;
    }

    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean networkListenerRegistered = false;

    public void setupNetworkListener() {
        if(networkListenerRegistered) {
            removeNetworkListener();
        }
        if(appContext == null) return;

        ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if(cm == null) return;

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.d(TAG, "networkCallback - onAvailable");
                if(shouldBeRunning && !isRunning) {
                    start();
                }
            }

            @Override
            public void onLost(Network network) {
                Log.d(TAG, "networkCallback - onLost");
                if(isRunning) {
                    shouldBeRunning = true;
                    stop();
                }
            }
        };

        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();
        cm.registerNetworkCallback(request, networkCallback);
        networkListenerRegistered = true;
    }

    public void removeNetworkListener() {
        if(appContext != null && networkCallback != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
                if(cm != null) {
                    cm.unregisterNetworkCallback(networkCallback);
                }
                networkListenerRegistered = false;
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            }
        }
    }

    public boolean isOnline() {
        ConnectivityManager cm =
                (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if(cm == null) return false;

        Network activeNetwork = cm.getActiveNetwork();
        if(activeNetwork == null) {
            Log.d(TAG, "isOnline? OFF");
            return false;
        }
        NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
        boolean online = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        Log.d(TAG, "isOnline? " + (online ? "ON" : "OFF"));
        return online;
    }

}
