package com.simog.clipmanager;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Keeps one .txt per list in the phone's Documents/ClipManager folder. */
public final class TxtExporter {

    public static final String FOLDER = "ClipManager";

    private TxtExporter() {
    }

    public static String fileName(Store.ClipList l) {
        String safe = l.name.replaceAll("[\\\\/:*?\"<>|\\n\\r\\t]", "_").trim();
        if (safe.isEmpty()) safe = "lista";
        return safe + ".txt";
    }

    public static String content(Store store, Store.ClipList l) {
        List<Store.Clip> clips = store.clipsOf(l.id);
        SimpleDateFormat df = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY);
        StringBuilder sb = new StringBuilder();
        sb.append(l.name).append('\n');
        sb.append("Aggiornato: ").append(df.format(new Date())).append("  -  ")
                .append(clips.size()).append(" clip\n");
        sb.append("========================================\n");
        for (int i = 0; i < clips.size(); i++) {
            if (i > 0) sb.append("\n----------------------------------------\n");
            sb.append('\n').append(clips.get(i).text).append('\n');
        }
        return sb.toString();
    }

    /** (Re)writes the list's .txt. Failures are swallowed: the app data is the source of truth. */
    public static void write(Context c, Store store, Store.ClipList l) {
        byte[] bytes = content(store, l).getBytes(StandardCharsets.UTF_8);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                writeMediaStore(c, store, l, bytes);
            } else {
                writeLegacy(c, store, l, bytes);
            }
        } catch (Exception ignored) {
        }
    }

    public static void delete(Context c, Store.ClipList l) {
        if (l.fileUri == null) return;
        try {
            Uri uri = Uri.parse(l.fileUri);
            if ("file".equals(uri.getScheme())) {
                File f = new File(uri.getPath());
                if (f.delete()) {
                    MediaScannerConnection.scanFile(c, new String[]{f.getAbsolutePath()}, null, null);
                }
            } else {
                c.getContentResolver().delete(uri, null, null);
            }
        } catch (Exception ignored) {
        }
    }

    private static void writeMediaStore(Context c, Store store, Store.ClipList l, byte[] bytes)
            throws Exception {
        ContentResolver cr = c.getContentResolver();
        if (l.fileUri != null) {
            // overwrite the file we created earlier (fails if the user deleted it meanwhile)
            try (OutputStream out = cr.openOutputStream(Uri.parse(l.fileUri), "wt")) {
                if (out != null) {
                    out.write(bytes);
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName(l));
        v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/" + FOLDER);
        Uri uri = cr.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
        if (uri == null) return;
        try (OutputStream out = cr.openOutputStream(uri, "wt")) {
            if (out == null) return;
            out.write(bytes);
        }
        // MediaStore may have picked another name ("Spesa (1).txt") if one already existed
        String name = fileName(l);
        try (Cursor cur = cr.query(uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cur != null && cur.moveToFirst()) name = cur.getString(0);
        } catch (Exception ignored) {
        }
        store.setFile(l, uri.toString(), Environment.DIRECTORY_DOCUMENTS + "/" + FOLDER + "/" + name);
    }

    private static void writeLegacy(Context c, Store store, Store.ClipList l, byte[] bytes)
            throws Exception {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS), FOLDER);
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        File f = new File(dir, fileName(l));
        try (OutputStream out = new FileOutputStream(f, false)) {
            out.write(bytes);
        }
        MediaScannerConnection.scanFile(c, new String[]{f.getAbsolutePath()}, null, null);
        String uri = Uri.fromFile(f).toString();
        if (!uri.equals(l.fileUri)) {
            store.setFile(l, uri, Environment.DIRECTORY_DOCUMENTS + "/" + FOLDER + "/" + f.getName());
        }
    }
}
