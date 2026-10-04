package com.simog.clipmanager;

import android.Manifest;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

public class MainActivity extends BaseActivity {

    private static final int REQ_STORAGE = 1;

    private boolean showLists;
    private final LinkedHashSet<Long> selected = new LinkedHashSet<>();
    private List<Store.Clip> inbox = new ArrayList<>();
    private List<Store.ClipList> lists = new ArrayList<>();

    private View activeBar;
    private TextView activeText, empty;
    private Button tabClips, tabLists, btnLeft, btnMid, btnRight;
    private ListView listView;
    private final Adapter adapter = new Adapter();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        activeBar = findViewById(R.id.active_bar);
        activeText = (TextView) findViewById(R.id.active_text);
        empty = (TextView) findViewById(R.id.empty);
        tabClips = (Button) findViewById(R.id.tab_clips);
        tabLists = (Button) findViewById(R.id.tab_lists);
        btnLeft = (Button) findViewById(R.id.btn_left);
        btnMid = (Button) findViewById(R.id.btn_mid);
        btnRight = (Button) findViewById(R.id.btn_right);
        listView = (ListView) findViewById(R.id.list);
        listView.setAdapter(adapter);
        listView.setEmptyView(empty);

        tabClips.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLists = false;
                refresh();
            }
        });
        tabLists.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLists = true;
                selected.clear();
                refresh();
            }
        });
        findViewById(R.id.active_stop).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                store.setActive(Store.INBOX);
                toast("Raccolta fermata: i nuovi clip vanno in «Clip»");
                refresh();
            }
        });
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                if (showLists) {
                    openList(lists.get(pos).id);
                } else {
                    long cid = inbox.get(pos).id;
                    if (!selected.remove(cid)) selected.add(cid);
                    refresh();
                }
            }
        });
        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int pos, long id) {
                if (showLists) listMenu(lists.get(pos));
                else clipMenu(inbox.get(pos));
                return true;
            }
        });
        btnLeft.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (showLists) newTitle();
                else addManual();
            }
        });
        btnMid.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!showLists) deleteSelected();
            }
        });
        btnRight.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!showLists) joinSelected();
            }
        });

        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQ_STORAGE && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            store.exportAllLists();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Seleziona tutti");
        menu.add(0, 2, 0, "Deseleziona");
        menu.add(0, 3, 0, "Rigenera tutti i TXT");
        menu.add(0, 4, 0, "Come funziona");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case 1:
                showLists = false;
                for (Store.Clip c : store.clipsOf(Store.INBOX)) selected.add(c.id);
                refresh();
                return true;
            case 2:
                selected.clear();
                refresh();
                return true;
            case 3:
                store.exportAllLists();
                toast("File TXT aggiornati in Documents/" + TxtExporter.FOLDER);
                return true;
            case 4:
                new AlertDialog.Builder(this)
                        .setTitle("Come funziona")
                        .setMessage("• Ogni volta che apri l'app, il testo negli appunti viene salvato come clip.\n\n"
                                + "• CLIP: tocca i clip per selezionarli, poi «Riunisci» per metterli in una lista con un nome.\n\n"
                                + "• LISTE: «Nuovo titolo» crea una lista e la rende attiva: da quel momento tutti i nuovi clip finiscono lì, finché premi «Stop».\n\n"
                                + "• Ogni lista genera un file .txt in Documents/" + TxtExporter.FOLDER + ", aggiornato automaticamente.\n\n"
                                + "• Android non permette alle app di leggere gli appunti in background. Per salvare senza aprire l'app:\n"
                                + "   – seleziona un testo e scegli «Salva in Clip Manager» dal menu, oppure\n"
                                + "   – usa «Condividi» → Clip Manager, oppure\n"
                                + "   – aggiungi il riquadro «Salva clip» alle Impostazioni rapide (tendina) e toccalo dopo aver copiato.\n\n"
                                + "• Tieni premuto un clip per leggerlo tutto o per altre opzioni; dentro una lista basta toccarlo.")
                        .setPositiveButton("OK", null)
                        .show();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void refresh() {
        if (tabClips == null) return;
        inbox = store.clipsOf(Store.INBOX);
        Collections.reverse(inbox); // newest first
        lists = store.getLists();
        // forget selections of clips that no longer exist / moved away
        ArrayList<Long> keep = new ArrayList<>();
        for (Store.Clip c : inbox) if (selected.contains(c.id)) keep.add(c.id);
        selected.retainAll(keep);

        Store.ClipList active = store.getActiveList();
        activeBar.setVisibility(active == null ? View.GONE : View.VISIBLE);
        if (active != null) {
            activeText.setText("● Raccolta attiva: «" + active.name + "» (" + store.count(active.id) + ")");
        }

        tabClips.setText("CLIP (" + inbox.size() + ")");
        tabLists.setText("LISTE (" + lists.size() + ")");
        tabClips.setAlpha(showLists ? 0.6f : 1f);
        tabLists.setAlpha(showLists ? 1f : 0.6f);

        if (showLists) {
            empty.setText("Nessuna lista.\nCrea un «Nuovo titolo»: i prossimi clip verranno salvati lì.");
            btnLeft.setText("+ Nuovo titolo");
            btnMid.setVisibility(View.GONE);
            btnRight.setVisibility(View.GONE);
        } else {
            empty.setText("Nessun clip.\nCopia un testo in qualsiasi app e torna qui: verrà aggiunto automaticamente.");
            btnLeft.setText("+ Aggiungi");
            btnMid.setVisibility(View.VISIBLE);
            btnRight.setVisibility(View.VISIBLE);
            btnMid.setText("Elimina (" + selected.size() + ")");
            btnRight.setText("Riunisci (" + selected.size() + ")");
            btnMid.setEnabled(!selected.isEmpty());
            btnRight.setEnabled(!selected.isEmpty());
        }
        adapter.notifyDataSetChanged();
    }

    // ---------------------------------------------------------------- actions

    private void openList(long id) {
        startActivity(new Intent(this, ListActivity.class).putExtra(ListActivity.EXTRA_ID, id));
    }

    private void addManual() {
        askText("Nuovo clip", "", true, null, new TextCallback() {
            @Override
            public void onText(String text, boolean checked) {
                if (text.trim().isEmpty()) return;
                toast("Clip salvato in «" + store.addText(text) + "»");
                refresh();
            }
        });
    }

    private void newTitle() {
        askText("Nuovo titolo", "", false, "Salva qui tutti i prossimi clip", new TextCallback() {
            @Override
            public void onText(String name, boolean activate) {
                Store.ClipList l = store.createList(name);
                if (activate) {
                    store.setActive(l.id);
                    toast("«" + l.name + "» attiva: copia pure, i clip finiranno qui");
                }
                refresh();
            }
        });
    }

    private void deleteSelected() {
        if (selected.isEmpty()) return;
        confirm("Eliminare " + selected.size() + " clip?", new Runnable() {
            @Override
            public void run() {
                store.deleteClips(new ArrayList<>(selected));
                selected.clear();
                refresh();
            }
        });
    }

    private void joinSelected() {
        if (selected.isEmpty()) return;
        final List<Store.ClipList> all = store.getLists();
        if (all.isEmpty()) {
            joinIntoNew();
            return;
        }
        String[] items = new String[all.size() + 1];
        items[0] = "+ Nuovo titolo…";
        for (int i = 0; i < all.size(); i++) items[i + 1] = all.get(i).name;
        new AlertDialog.Builder(this)
                .setTitle("Riunisci " + selected.size() + " clip in…")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) joinIntoNew();
                        else joinInto(all.get(which - 1));
                    }
                })
                .show();
    }

    private void joinIntoNew() {
        askText("Nome della lista", "", false, null, new TextCallback() {
            @Override
            public void onText(String name, boolean checked) {
                joinInto(store.createList(name));
            }
        });
    }

    private void joinInto(Store.ClipList l) {
        int n = selected.size();
        // oldest first, matching the order they were copied
        store.moveClips(new ArrayList<>(selected), l.id);
        selected.clear();
        toast(n + " clip riuniti in «" + l.name + "»");
        refresh();
    }

    private void clipMenu(final Store.Clip c) {
        new AlertDialog.Builder(this)
                .setItems(new String[]{"Leggi tutto", "Copia", "Modifica", "Elimina"},
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (which == 0) {
                                    showClip(c);
                                } else if (which == 1) {
                                    store.copyToClipboard(MainActivity.this, c.text);
                                    toast("Copiato");
                                } else if (which == 2) {
                                    askText("Modifica clip", c.text, true, null, new TextCallback() {
                                        @Override
                                        public void onText(String text, boolean checked) {
                                            store.editClip(c.id, text);
                                            refresh();
                                        }
                                    });
                                } else {
                                    ArrayList<Long> one = new ArrayList<>();
                                    one.add(c.id);
                                    store.deleteClips(one);
                                    refresh();
                                }
                            }
                        })
                .show();
    }

    private void listMenu(final Store.ClipList l) {
        final boolean isActive = store.getActiveList() == l;
        new AlertDialog.Builder(this)
                .setTitle(l.name)
                .setItems(new String[]{isActive ? "Ferma raccolta" : "Attiva (salva qui i nuovi clip)",
                                "Rinomina", "Elimina"},
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (which == 0) {
                                    store.setActive(isActive ? Store.INBOX : l.id);
                                    refresh();
                                } else if (which == 1) {
                                    askText("Rinomina", l.name, false, null, new TextCallback() {
                                        @Override
                                        public void onText(String name, boolean checked) {
                                            if (!store.renameList(l.id, name)) {
                                                toast("Esiste già una lista con questo nome");
                                            }
                                            refresh();
                                        }
                                    });
                                } else {
                                    confirm("Eliminare la lista «" + l.name + "», i suoi "
                                            + store.count(l.id) + " clip e il file TXT?", new Runnable() {
                                        @Override
                                        public void run() {
                                            store.deleteList(l.id);
                                            refresh();
                                        }
                                    });
                                }
                            }
                        })
                .show();
    }

    // ---------------------------------------------------------------- adapter

    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return showLists ? lists.size() : inbox.size();
        }

        @Override
        public Object getItem(int position) {
            return showLists ? lists.get(position) : inbox.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return showLists ? 1 : 0;
        }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            if (showLists) {
                if (v == null) v = getLayoutInflater().inflate(R.layout.row_list, parent, false);
                Store.ClipList l = lists.get(pos);
                ((TextView) v.findViewById(R.id.name)).setText(l.name);
                String path = l.filePath != null ? l.filePath
                        : "Documents/" + TxtExporter.FOLDER + "/" + TxtExporter.fileName(l);
                ((TextView) v.findViewById(R.id.meta)).setText(store.count(l.id) + " clip  ·  " + path);
                v.findViewById(R.id.badge).setVisibility(
                        store.getActiveList() == l ? View.VISIBLE : View.GONE);
            } else {
                if (v == null) v = getLayoutInflater().inflate(R.layout.row_clip, parent, false);
                Store.Clip c = inbox.get(pos);
                ((TextView) v.findViewById(R.id.text)).setText(c.text);
                ((TextView) v.findViewById(R.id.meta)).setText(formatTime(c.time) + "  ·  "
                        + c.text.length() + " caratteri");
                ((CheckBox) v.findViewById(R.id.check)).setChecked(selected.contains(c.id));
            }
            return v;
        }
    }
}
