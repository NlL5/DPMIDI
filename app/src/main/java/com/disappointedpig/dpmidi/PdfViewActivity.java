package com.disappointedpig.dpmidi;

import static android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

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
                Intent intent = new Intent(DPMIDIApplication.getAppContext(), PdfViewActivity.class);
                mainActivity.startActivity(intent);
            }

            @Subscribe(threadMode = ThreadMode.MAIN)
            public void onMidiNoteEvent(MIDIReceivedEvent event) {
                System.out.println("PdfViewActivity: got MIDI event. " + event.midi.toString());
                //mainActivity.startActivity(intent);

                MIDIMessage message = MIDIMessage.newUsing(event.midi);
                if (message.getChannel() == 1 && message.getCommand() == 0x09) { // MIDI ON

                    lastPage = message.getNote()*100 + message.getVelocity();

                    if (action != null) {
                        action.gotoPage(lastPage);
                    }
                }
            }
        }
        EventBus.getDefault().register(new MidiListener());
    }
}
