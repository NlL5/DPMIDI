package com.disappointedpig.midi;

import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;

import com.disappointedpig.midi.internal_events.PacketEvent;

import org.greenrobot.eventbus.EventBus;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

public class MIDIPort implements Runnable {
    int port;

    Selector selector;
    DatagramChannel channel;

    private Queue<DatagramPacket> outboundQueue;
    private Queue<DatagramPacket> inboundQueue;

    boolean isListening = false;

    private static final int BUFFER_SIZE = 1536;
    private static final String TAG = "MIDIPort";
    private static final boolean DEBUG = false;

    final Thread thread = new Thread(this);

    public static MIDIPort newUsing(int port) {
        return new MIDIPort(port);
    }

    public MIDIPort(int port) {
        this.port = port;
        try {
            selector = Selector.open();
            channel = DatagramChannel.open();
            outboundQueue = new ConcurrentLinkedQueue<DatagramPacket>();
            inboundQueue = new ConcurrentLinkedQueue<DatagramPacket>();

            InetSocketAddress address = new InetSocketAddress(this.port);
            channel.socket().setReuseAddress(true);
            channel.configureBlocking(false);
            channel.socket().bind(address);

//            channel.socket().setSoTimeout(700);
//            int ops = channel.validOps();

//            SelectionKey selectKy = channel.register(selector, ops, new UDPBuffer()); // null for an attachment object
//            SelectionKey selectKy = channel.register(selector, (SelectionKey.OP_READ), new UDPBuffer());
//            channel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
            channel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);

        } catch (IOException e) {
            e.printStackTrace();
        }


    }

    @Override
    public void run() {
        while(isListening) {
            try {
//                int noOfKeys = selector.select();
//                Iterator<SelectionKey> keyIter = selector.selectedKeys().iterator();
//                while (keyIter.hasNext()) {
//
//                    SelectionKey key = keyIter.next(); // Key is bit mask
//
//                    // Client socket channel has pending data?
//                    if (key.isReadable()) {
//                        handleRead(key);
//                    }
//                    // Client socket channel is available for writing and
//                    // key is valid (i.e., channel not closed).
////                    if (key.isValid() && key.isWritable()) {
//                    if (key.isWritable()) {
//                            handleWrite(key);
////                            key.interestOps(SelectionKey.OP_READ);
//                    }
//                    keyIter.remove();
//                }

                    selector.select();
                    Set readyKeys = selector.selectedKeys();
                    if (readyKeys.isEmpty() ) { //&& n == LIMIT) {
                        // All packets have been written and it doesn't look like any
                        // more are will arrive from the network
                        Log.d(TAG,"no readyKeys");
                        break;
                    } else {
                        Iterator<SelectionKey> keyIter = readyKeys.iterator();
                        while (keyIter.hasNext()) {
                            SelectionKey key = (SelectionKey) keyIter.next();
                            keyIter.remove();
                            if(!key.isValid()) {
                                continue;
                            }

                            if (key.isReadable()) {
                                handleRead(key);
//                                Log.d(TAG," read "+this.port);
                            }
                            if (key.isWritable()) {
                                handleWrite(key);
//                                Log.d(TAG," write "+this.port);
                            }
//                            Log.d(TAG,"key "+key.toString());
                        }
                    }
//                    Log.d(TAG,"loop");


            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void start() {
        isListening = true;

//        final Thread thread = new Thread(this);
//        // The JVM exits when the only threads running are all daemon threads.
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY);
        thread.start();
        Log.d(TAG,"create thread : "+thread.getId());

    }

    public void stop() {
        isListening = false;

    }

    public void finalize() {
        try {
            outboundQueue.clear();
            inboundQueue.clear();
            selector.close();
            channel.close();
            thread.interrupt();
        } catch (IOException e) {
            e.printStackTrace();
        }

        try {
            super.finalize();
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }

    public void handleRead(SelectionKey key) {
//        Log.d("MIDIPort2","handleRead");
//        final byte[] buffer = new byte[BUFFER_SIZE];
//        final DatagramPacket packet = new DatagramPacket(buffer, fBUFFER_SIZE);
        DatagramChannel c = (DatagramChannel) key.channel();
        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
        try {
//            channel.socket().receive(packet);
//            UDPBuffer b = new UDPBuffer();
            SocketAddress clientAddress = c.receive(buffer);
//            Log.d("READ","buffer pos "+buffer.position());
            DatagramPacket packet = new DatagramPacket(buffer.array(),buffer.capacity(),clientAddress);
//                String output = "";
//                for(byte a:packet.getData()) {
//                    output += (String.format("%02x", a) + " ");
//                }
//                Log.d("READ"," "+output);
            EventBus.getDefault().post(new PacketEvent(packet));

        } catch (IOException e) {
            e.printStackTrace();
        }

//        if (buf.clientAddress != null) {  // Did we receive something?
//            // Register write with the selector
//            key.interestOps(SelectionKey.OP_WRITE);
//        }


    }

    public void handleWrite(SelectionKey key) {
        if(!outboundQueue.isEmpty()) {
//            Log.d("MIDIPort2","handleWrite "+ outboundQueue.size());
            try {
//                ByteBuffer buffer = (ByteBuffer) key.attachment();
                DatagramChannel c = (DatagramChannel) key.channel();
                DatagramPacket d = outboundQueue.poll();

                byte[] r = d.getData();
//                String output = "";
//                for(byte a:r) {
//                    output += (String.format("%02x", a) + " ");
//                }
//                Log.d("PACK"," "+output);
//                buffer = ByteBuffer.wrap(r);
//                output = "";
//                for(byte a:buffer.array()) {
//                    output += (String.format("%02x", a) + " ");
//                }
//                Log.d("WRIT"," "+output);
//                c.send(buffer,d.getSocketAddress());

                c.send(ByteBuffer.wrap(r),d.getSocketAddress());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }


    public void sendMidi(MIDIControl control, Bundle rinfo) {
//        Log.d("MIDIPort2","sendMidi(control)");
        if (!isListening) {
            Log.d(TAG,"not listening...");
            return;
        }
        addToOutboundQueue(control.generateBuffer(),rinfo);
    }

    public void sendMidi(MIDIMessage message, Bundle rinfo) {
//        Log.d("MIDIPort","sendMidi(message)");
        if (!isListening) {
            Log.d(TAG,"not listening...");
            return;
        }
        addToOutboundQueue(message.generateBuffer(),rinfo);
    }

    public void addToOutboundQueue(byte[] data, Bundle rinfo) {
        try {
            outboundQueue.add(new DatagramPacket(data, data.length, InetAddress.getByName(rinfo.getString(Consts.RINFO_ADDR)), rinfo.getInt(Consts.RINFO_PORT)));
            selector.wakeup();
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
    }

//    class UDPBuffer {
//        public SocketAddress clientAddress;
//        public ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
//    }

}
