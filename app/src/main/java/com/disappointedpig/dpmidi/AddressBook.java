package com.disappointedpig.dpmidi;

import android.os.Bundle;
import android.util.Log;
import android.view.View;

import com.disappointedpig.midi.MIDIAddressBookEntry;
import com.disappointedpig.midi.MIDISession;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

import java.util.ArrayList;
import java.util.List;

import static com.disappointedpig.dpmidi.Constants.AB_DIALOG_FRAGMENT_KEY;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDialogFragment;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.DividerItemDecoration;

public class AddressBook extends AppCompatActivity implements AddressBookDialog.AddressBookDialogListener {

    private final String TAG = AddressBook.class.getSimpleName();

    private EmptyRecyclerView mRecyclerView;
    private GenericRecyclerViewAdapter<AddressBookModel> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_address_book);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        EventBus.getDefault().register(this);

        initializeRecyclerView(null);

        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
//                Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
//                        .setAction("Action", null).show();
                AddressBookDialog abdialog = new AddressBookDialog();
                abdialog.show(getSupportFragmentManager(), AB_DIALOG_FRAGMENT_KEY);
            }
        });
        MIDISession.getInstance().checkAddressBookForReconnect();
    }

    @Override
    protected void onDestroy() {
        EventBus.getDefault().unregister(this);
        super.onDestroy();
    }

    private void initializeRecyclerView(Bundle savedInstanceState) {

        mRecyclerView = (EmptyRecyclerView) findViewById(R.id.abListRecyclerView);
        mRecyclerView.setItemAnimator(new DefaultItemAnimator());
        mRecyclerView.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        View emptyView = findViewById(R.id.list_empty_view);
        mRecyclerView.setEmptyView(emptyView);

//        DaoSession daoSession = ((StageCallerApplication) getApplicationContext()).getDaoSession();

//        PatternsDao patternsDao = daoSession.getPatternsDao();
        List<AddressBookModel> l = new ArrayList<>();
        if(!MIDISession.getInstance().addressBookIsEmpty()) {
            for (MIDIAddressBookEntry a : MIDISession.getInstance().getAllAddressBook()) {
                l.add(AddressBookModel.newInstance(a));
            }
        }
        adapter = new GenericRecyclerViewAdapter<AddressBookModel>(l,null);
        mRecyclerView.setAdapter(adapter);

    }

    @Subscribe
    public void onAddressBookEvent(AddressBookEvent event) {
        switch(event.getType()) {
            case TOUCHED:
                Log.d(TAG,"touched ab entry - not implemented");
//                AddressBookDialog abdialog = new AddressBookDialog();
//                abdialog.setArguments(event.getEntry().rinfo());
//                abdialog.show(getSupportFragmentManager(), AB_DIALOG_FRAGMENT_KEY);
                break;
            case DELETE:
                Log.d(TAG,"delete ab entry - not implemented");
                //TODO: implement ab delete
                MIDISession.getInstance().deleteFromAddressBook(event.getEntry());
                initializeRecyclerView(null);
        }
    }

    @Override
    public void onAddressBookDialogPositiveClick(AppCompatDialogFragment dialog) {
        Log.d(TAG,"done...");
        initializeRecyclerView(null);

    }

    @Override
    public void onAddressBookDialogNegativeClick(AppCompatDialogFragment dialog) {
        initializeRecyclerView(null);

    }
}
