package com.disappointedpig.dpmidi;

import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;

import com.disappointedpig.midi.MIDIMessage;
import com.disappointedpig.midi.events.MIDIConnectionEstablishedEvent;
import com.disappointedpig.midi.events.MIDIReceivedEvent;
import com.github.barteksc.pdfviewer.PDFView;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class PdfViewActivity extends AppCompatActivity {

    static PdfDisplayAction action;
    static int lastPage = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pdf_viewer);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        PDFView pdfView = this.findViewById(R.id.pdfView);
        action = new PdfDisplayAction(pdfView, lastPage);
    }

    @Override
    protected void onDestroy() {
        EventBus.getDefault().unregister(this);
        action = null;
        super.onDestroy();
    }

    public static void register(MainActivity mainActivity) {

        class MidiListener {

            @Subscribe(threadMode = ThreadMode.MAIN)
            public void onConnectionEstablishedEvent(MIDIConnectionEstablishedEvent event) {
                // Only auto-open PDF when MainActivity is in foreground (not in Settings/AddressBook)
                if (!mainActivity.isInForeground) return;
                Intent intent = new Intent(DPMIDIApplication.getAppContext(), PdfViewActivity.class);
                mainActivity.startActivity(intent);
            }

            @Subscribe(threadMode = ThreadMode.MAIN)
            public void onMidiNoteEvent(MIDIReceivedEvent event) {
                System.out.println("PdfViewActivity: got MIDI event. " + event.midi.toString());

                if (action == null) {
                    return;
                }

                MIDIMessage message = MIDIMessage.newUsing(event.midi);
                if (message.getChannel() == 0 && message.getCommand() == 0x9) { // MIDI ON, helper tool: http://www.xmlizer.net/hansLindauer/midiapp.html
                    lastPage = message.getNote()*100 + message.getVelocity();
                    action.gotoPage(lastPage);
                } else if (message.getChannel() == 0 && message.getCommand() == 0xB && message.getNote() >= 16 && message.getNote() <= 19) { // CC
                    lastPage = (message.getNote() - 16)*100 + message.getVelocity();
                    action.gotoPage(lastPage);
                }
            }
        }
        EventBus.getDefault().register(new MidiListener());
    }
}
