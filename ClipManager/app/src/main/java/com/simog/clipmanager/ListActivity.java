package com.simog.clipmanager;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** One named list: its clips, the .txt location, share / copy / activate. */
public class ListActivity extends BaseActivity {

    public static final String EXTRA_ID = "list_id";

    private long listId;
    private List<Store.Clip> clips = new ArrayList<>();
    private ArrayAdapter<Store.Clip> adapter;
    private TextView filePath;
    private Button btnActive;
    private boolean reorder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        listId = getIntent().getLongExtra(EXTRA_ID, Store.INBOX);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        filePath = (TextView) findViewById(R.id.file_path);
        btnActive = (Button) findViewById(R.id.btn_active);

        ListView lv = (ListView) findViewById(R.id.list);
        lv.setEmptyView(findViewById(R.id.empty));
        adapter = new ArrayAdapter<Store.Clip>(this, R.layout.row_clip, R.id.text) {
            @Override
            public View getView(int pos, View convertView, ViewGroup parent) {
                View v = super.getView(pos, convertView, parent);
                Store.Clip c = getItem(pos);
                v.findViewById(R.id.check).setVisibility(View.GONE);
                v.findViewById(R.id.move_box).setVisibility(reorder ? View.VISIBLE : View.GONE);
                final long cid = c.id;
                View up = v.findViewById(R.id.move_up);
                View down = v.findViewById(R.id.move_down);
                up.setEnabled(pos > 0);
                down.setEnabled(pos < getCount() - 1);
                up.setAlpha(pos > 0 ? 1f : 0.25f);
                down.setAlpha(pos < getCount() - 1 ? 1f : 0.25f);
                up.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View b) {
                        move(cid, -1);
                    }
                });
                down.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View b) {
                        move(cid, 1);
                    }
                });
                ((TextView) v.findViewById(R.id.text)).setText(c.text);
                ((TextView) v.findViewById(R.id.meta)).setText((pos + 1) + ".  " + formatTime(c.time));
                return v;
            }
        };
        lv.setAdapter(adapter);
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                showClip(clips.get(pos));
            }
        });
        lv.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int pos, long id) {
                clipMenu(clips.get(pos));
                return true;
            }
        });

        btnActive.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean active = store.getActiveList() == store.getList(listId);
                store.setActive(active ? Store.INBOX : listId);
                toast(active ? "Raccolta fermata" : "Attiva: i nuovi clip verranno salvati qui");
                refresh();
            }
        });
        findViewById(R.id.btn_share).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                share();
            }
        });
        findViewById(R.id.btn_copy_all).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                StringBuilder sb = new StringBuilder();
                for (Store.Clip c : clips) {
                    if (sb.length() > 0) sb.append("\n\n");
                    sb.append(c.text);
                }
                store.copyToClipboard(ListActivity.this, sb.toString());
                toast("Tutti i " + clips.size() + " clip copiati");
            }
        });
    }

    @Override
    protected void refresh() {
        if (adapter == null) return;
        Store.ClipList l = store.getList(listId);
        if (l == null) {
            finish();
            return;
        }
        setTitle(l.name);
        boolean active = store.getActiveList() == l;
        btnActive.setText(active ? "■ Ferma raccolta" : "● Attiva");
        String path = l.filePath != null ? l.filePath
                : "Documents/" + TxtExporter.FOLDER + "/" + TxtExporter.fileName(l);
        filePath.setText(reorder
                ? "RIORDINA: usa ▲ ▼ per spostare i clip, poi premi «Fine» in alto.\nIl file TXT segue il nuovo ordine."
                : (active ? "RACCOLTA ATTIVA – i nuovi clip arrivano qui\n" : "")
                + "File: " + path + "\nTocca un clip per leggerlo tutto, tieni premuto per altre opzioni.");
        invalidateOptionsMenu();
        clips = store.clipsOf(listId);
        adapter.clear();
        adapter.addAll(clips);
    }

    private void move(long clipId, int delta) {
        if (store.moveInList(clipId, delta)) refresh();
    }

    private void share() {
        Store.ClipList l = store.getList(listId);
        if (l == null) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, l.name);
        send.putExtra(Intent.EXTRA_TEXT, TxtExporter.content(store, l));
        if (l.fileUri != null && l.fileUri.startsWith("content:")) {
            send.putExtra(Intent.EXTRA_STREAM, Uri.parse(l.fileUri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        startActivity(Intent.createChooser(send, "Condividi «" + l.name + "»"));
    }

    private void clipMenu(final Store.Clip c) {
        new AlertDialog.Builder(this)
                .setItems(new String[]{"Copia", "Modifica", "Sposta in cima", "Sposta in fondo",
                                "Rimetti tra i Clip", "Elimina"},
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                ArrayList<Long> one = new ArrayList<>();
                                one.add(c.id);
                                if (which == 0) {
                                    store.copyToClipboard(ListActivity.this, c.text);
                                    toast("Copiato");
                                } else if (which == 1) {
                                    askText("Modifica clip", c.text, true, null, new TextCallback() {
                                        @Override
                                        public void onText(String text, boolean checked) {
                                            store.editClip(c.id, text);
                                            refresh();
                                        }
                                    });
                                } else if (which == 2) {
                                    store.moveInList(c.id, Integer.MIN_VALUE);
                                } else if (which == 3) {
                                    store.moveInList(c.id, Integer.MAX_VALUE);
                                } else if (which == 4) {
                                    store.moveClips(one, Store.INBOX);
                                } else {
                                    store.deleteClips(one);
                                }
                                refresh();
                            }
                        })
                .show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 3, 0, reorder ? "Fine" : "Riordina")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        menu.add(0, 1, 0, "Rinomina");
        menu.add(0, 2, 0, "Elimina lista");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final Store.ClipList l = store.getList(listId);
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        if (l == null) return true;
        if (item.getItemId() == 3) {
            reorder = !reorder;
            refresh();
            return true;
        }
        if (item.getItemId() == 1) {
            askText("Rinomina", l.name, false, null, new TextCallback() {
                @Override
                public void onText(String name, boolean checked) {
                    if (!store.renameList(listId, name)) toast("Esiste già una lista con questo nome");
                    refresh();
                }
            });
            return true;
        }
        if (item.getItemId() == 2) {
            confirm("Eliminare la lista «" + l.name + "», i suoi " + clips.size()
                    + " clip e il file TXT?", new Runnable() {
                @Override
                public void run() {
                    store.deleteList(listId);
                    finish();
                }
            });
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
