package com.simog.clipmanager;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
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
        String[] res = writeFile(c, l.fileUri, fileName(l), bytes);
        if (res != null && (!res[0].equals(l.fileUri) || !res[1].equals(l.filePath))) {
            store.setFile(l, res[0], res[1]);
        }
    }

    /** Writes a loose file (e.g. the Facebook debug dump), overwriting it on later calls. */
    public static void writeRaw(Context c, String fileName, String content) {
        SharedPreferences prefs = c.getSharedPreferences("raw_files", Context.MODE_PRIVATE);
        String[] res = writeFile(c, prefs.getString(fileName, null), fileName,
                content.getBytes(StandardCharsets.UTF_8));
        if (res != null) prefs.edit().putString(fileName, res[0]).apply();
    }

    /**
     * Writes Documents/ClipManager/fileName, overwriting oldUri if it is still there.
     * Returns {uri, readable path} or null on failure.
     */
    private static String[] writeFile(Context c, String oldUri, String fileName, byte[] bytes) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                return writeMediaStore(c, oldUri, fileName, bytes);
            } else {
                return writeLegacy(c, fileName, bytes);
            }
        } catch (Exception e) {
            return null;
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

    private static String[] writeMediaStore(Context c, String oldUri, String fileName, byte[] bytes)
            throws Exception {
        ContentResolver cr = c.getContentResolver();
        String dir = Environment.DIRECTORY_DOCUMENTS + "/" + FOLDER;
        if (oldUri != null) {
            // overwrite the file we created earlier (fails if the user deleted it meanwhile)
            Uri uri = Uri.parse(oldUri);
            try (OutputStream out = cr.openOutputStream(uri, "wt")) {
                if (out != null) {
                    out.write(bytes);
                    return new String[]{oldUri, dir + "/" + displayName(cr, uri, fileName)};
                }
            } catch (Exception ignored) {
            }
        }
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, dir);
        Uri uri = cr.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
        if (uri == null) return null;
        try (OutputStream out = cr.openOutputStream(uri, "wt")) {
            if (out == null) return null;
            out.write(bytes);
        }
        return new String[]{uri.toString(), dir + "/" + displayName(cr, uri, fileName)};
    }

    /** MediaStore may have picked another name ("Spesa (1).txt") if one already existed. */
    private static String displayName(ContentResolver cr, Uri uri, String fallback) {
        try (Cursor cur = cr.query(uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cur != null && cur.moveToFirst() && cur.getString(0) != null) return cur.getString(0);
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static String[] writeLegacy(Context c, String fileName, byte[] bytes) throws Exception {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS), FOLDER);
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        File f = new File(dir, fileName);
        try (OutputStream out = new FileOutputStream(f, false)) {
            out.write(bytes);
        }
        MediaScannerConnection.scanFile(c, new String[]{f.getAbsolutePath()}, null, null);
        return new String[]{Uri.fromFile(f).toString(),
                Environment.DIRECTORY_DOCUMENTS + "/" + FOLDER + "/" + f.getName()};
    }
}
