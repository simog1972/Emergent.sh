package com.simog.clipmanager;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 * All app data: loose clips (the "inbox", listId == INBOX), named lists, and which list is
 * currently active (new clips go straight into it). Persisted as JSON in internal storage.
 * Every change to a list rewrites its .txt file through {@link TxtExporter}.
 */
public final class Store {

    public static final long INBOX = -1;

    public static final class Clip {
        public long id;
        public String text;
        public long time;
        public long listId = INBOX;
    }

    public static final class ClipList {
        public long id;
        public String name;
        public long created;
        /** content:// (Android 10+) or file:// uri of the generated .txt, if any. */
        public String fileUri;
        /** Human readable location of the .txt, e.g. "Documents/ClipManager/Spesa.txt". */
        public String filePath;
    }

    private static Store sInstance;

    private final Context app;
    private final File dataFile;
    private final ArrayList<Clip> clips = new ArrayList<>();
    private final ArrayList<ClipList> lists = new ArrayList<>();
    private long activeListId = INBOX;
    private String lastCaptured = "";
    private long nextId = 1;

    public static synchronized Store get(Context c) {
        if (sInstance == null) sInstance = new Store(c.getApplicationContext());
        return sInstance;
    }

    private Store(Context app) {
        this.app = app;
        this.dataFile = new File(app.getFilesDir(), "data.json");
        load();
    }

    // ---------------------------------------------------------------- capture

    /** Reads the system clipboard. Only works while one of our windows has focus (Android 10+). */
    public static String readClipboard(Context c) {
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            ClipData data = cm.getPrimaryClip();
            if (data == null || data.getItemCount() == 0) return null;
            CharSequence t = data.getItemAt(0).coerceToText(c);
            return t == null ? null : t.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Puts text on the clipboard without it being captured again as a new clip. */
    public synchronized void copyToClipboard(Context c, String text) {
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        lastCaptured = text;
        save();
        cm.setPrimaryClip(ClipData.newPlainText("clip", text));
    }

    /**
     * Saves the clipboard text if it is new. Returns where it went ("Clip" or the list name),
     * or null when there was nothing new to save.
     */
    public synchronized String captureClipboard(Context c) {
        String text = readClipboard(c);
        if (text == null || text.trim().isEmpty() || text.equals(lastCaptured)) return null;
        return addText(text);
    }

    /** Adds text explicitly (share / text selection / manual), into the active list if any. */
    public synchronized String addText(String text) {
        lastCaptured = text;
        Clip clip = new Clip();
        clip.id = nextId++;
        clip.text = text;
        clip.time = System.currentTimeMillis();
        ClipList active = getList(activeListId);
        clip.listId = active == null ? INBOX : active.id;
        clips.add(clip);
        save();
        if (active != null) {
            TxtExporter.write(app, this, active);
            return active.name;
        }
        return "Clip";
    }

    // ---------------------------------------------------------------- queries

    public synchronized List<Clip> clipsOf(long listId) {
        ArrayList<Clip> out = new ArrayList<>();
        for (Clip c : clips) if (c.listId == listId) out.add(c);
        return out;
    }

    public synchronized List<ClipList> getLists() {
        return new ArrayList<>(lists);
    }

    public synchronized ClipList getList(long id) {
        for (ClipList l : lists) if (l.id == id) return l;
        return null;
    }

    public synchronized ClipList findListByName(String name) {
        for (ClipList l : lists) if (l.name.equalsIgnoreCase(name.trim())) return l;
        return null;
    }

    public synchronized Clip getClip(long id) {
        for (Clip c : clips) if (c.id == id) return c;
        return null;
    }

    public synchronized ClipList getActiveList() {
        return getList(activeListId);
    }

    public synchronized int count(long listId) {
        int n = 0;
        for (Clip c : clips) if (c.listId == listId) n++;
        return n;
    }

    // ---------------------------------------------------------------- mutations

    /** Creates a list, or returns the existing one with the same name. */
    public synchronized ClipList createList(String name) {
        ClipList existing = findListByName(name);
        if (existing != null) return existing;
        ClipList l = new ClipList();
        l.id = nextId++;
        l.name = name.trim();
        l.created = System.currentTimeMillis();
        lists.add(l);
        save();
        TxtExporter.write(app, this, l);
        return l;
    }

    public synchronized void setActive(long listId) {
        activeListId = getList(listId) == null ? INBOX : listId;
        save();
    }

    public synchronized void moveClips(Collection<Long> ids, long listId) {
        ArrayList<Long> touched = new ArrayList<>();
        touched.add(listId);
        for (Clip c : clips) {
            if (ids.contains(c.id)) {
                if (!touched.contains(c.listId)) touched.add(c.listId);
                c.listId = listId;
            }
        }
        // moved clips go to the end of the target list, in the order they were captured
        ArrayList<Clip> moved = new ArrayList<>();
        for (Iterator<Clip> it = clips.iterator(); it.hasNext(); ) {
            Clip c = it.next();
            if (ids.contains(c.id)) {
                moved.add(c);
                it.remove();
            }
        }
        clips.addAll(moved);
        save();
        exportAll(touched);
    }

    public synchronized void deleteClips(Collection<Long> ids) {
        ArrayList<Long> touched = new ArrayList<>();
        for (Iterator<Clip> it = clips.iterator(); it.hasNext(); ) {
            Clip c = it.next();
            if (ids.contains(c.id)) {
                if (!touched.contains(c.listId)) touched.add(c.listId);
                it.remove();
            }
        }
        save();
        exportAll(touched);
    }

    public synchronized void editClip(long id, String text) {
        Clip c = getClip(id);
        if (c == null) return;
        c.text = text;
        save();
        ArrayList<Long> touched = new ArrayList<>();
        touched.add(c.listId);
        exportAll(touched);
    }

    /** Renames a list; its .txt is recreated under the new name. False if the name is taken. */
    public synchronized boolean renameList(long id, String name) {
        ClipList l = getList(id);
        ClipList other = findListByName(name);
        if (l == null || (other != null && other != l)) return false;
        TxtExporter.delete(app, l);
        l.name = name.trim();
        l.fileUri = null;
        l.filePath = null;
        save();
        TxtExporter.write(app, this, l);
        return true;
    }

    /** Deletes a list, its clips and its .txt file. */
    public synchronized void deleteList(long id) {
        ClipList l = getList(id);
        if (l == null) return;
        TxtExporter.delete(app, l);
        for (Iterator<Clip> it = clips.iterator(); it.hasNext(); ) {
            if (it.next().listId == id) it.remove();
        }
        lists.remove(l);
        if (activeListId == id) activeListId = INBOX;
        save();
    }

    /** Called by the exporter after it (re)created a file. */
    synchronized void setFile(ClipList l, String uri, String path) {
        l.fileUri = uri;
        l.filePath = path;
        save();
    }

    public synchronized void exportAllLists() {
        for (ClipList l : lists) TxtExporter.write(app, this, l);
    }

    private void exportAll(List<Long> listIds) {
        for (Long id : listIds) {
            ClipList l = getList(id);
            if (l != null) TxtExporter.write(app, this, l);
        }
    }

    // ---------------------------------------------------------------- persistence

    private void load() {
        if (!dataFile.exists()) return;
        try (InputStream in = new FileInputStream(dataFile)) {
            byte[] buf = new byte[(int) dataFile.length()];
            int off = 0;
            while (off < buf.length) {
                int r = in.read(buf, off, buf.length - off);
                if (r < 0) break;
                off += r;
            }
            JSONObject root = new JSONObject(new String(buf, 0, off, StandardCharsets.UTF_8));
            activeListId = root.optLong("active", INBOX);
            lastCaptured = root.optString("last", "");
            nextId = root.optLong("nextId", 1);
            JSONArray ls = root.optJSONArray("lists");
            for (int i = 0; ls != null && i < ls.length(); i++) {
                JSONObject o = ls.getJSONObject(i);
                ClipList l = new ClipList();
                l.id = o.getLong("id");
                l.name = o.getString("name");
                l.created = o.optLong("created");
                l.fileUri = o.has("fileUri") ? o.getString("fileUri") : null;
                l.filePath = o.has("filePath") ? o.getString("filePath") : null;
                lists.add(l);
            }
            JSONArray cs = root.optJSONArray("clips");
            for (int i = 0; cs != null && i < cs.length(); i++) {
                JSONObject o = cs.getJSONObject(i);
                Clip c = new Clip();
                c.id = o.getLong("id");
                c.text = o.getString("text");
                c.time = o.optLong("time");
                c.listId = o.optLong("list", INBOX);
                clips.add(c);
            }
        } catch (Exception e) {
            // corrupted file: keep a copy and start fresh rather than crash
            dataFile.renameTo(new File(app.getFilesDir(), "data.broken.json"));
        }
    }

    private void save() {
        try {
            JSONObject root = new JSONObject();
            root.put("active", activeListId);
            root.put("last", lastCaptured);
            root.put("nextId", nextId);
            JSONArray ls = new JSONArray();
            for (ClipList l : lists) {
                JSONObject o = new JSONObject();
                o.put("id", l.id);
                o.put("name", l.name);
                o.put("created", l.created);
                if (l.fileUri != null) o.put("fileUri", l.fileUri);
                if (l.filePath != null) o.put("filePath", l.filePath);
                ls.put(o);
            }
            root.put("lists", ls);
            JSONArray cs = new JSONArray();
            for (Clip c : clips) {
                JSONObject o = new JSONObject();
                o.put("id", c.id);
                o.put("text", c.text);
                o.put("time", c.time);
                o.put("list", c.listId);
                cs.put(o);
            }
            root.put("clips", cs);
            File tmp = new File(app.getFilesDir(), "data.json.tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            tmp.renameTo(dataFile);
        } catch (Exception ignored) {
        }
    }
}
