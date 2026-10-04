package com.simog.clipmanager;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * "Modalità Facebook": while recording, reads the comments visible in the Facebook app as the
 * user scrolls, and on STOP saves the top-level ones into a new list. Does nothing otherwise.
 */
public class FbCommentService extends AccessibilityService {

    public static final String FB_PACKAGE = "com.facebook.katana";
    private static final int MAX_DEBUG_LINES = 4000;

    private static FbCommentService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean recording;
    private boolean scanPending;
    private FbParser.Collector collector;
    private final LinkedHashSet<String> debug = new LinkedHashSet<>();
    private View overlay;
    private TextView counter;

    public static FbCommentService get() {
        return instance;
    }

    /** True if the user switched the service on in the accessibility settings. */
    public static boolean isEnabled(Context c) {
        String on = Settings.Secure.getString(c.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (on == null) return false;
        ComponentName me = new ComponentName(c, FbCommentService.class);
        for (String s : on.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(s);
            if (me.equals(cn)) return true;
        }
        return false;
    }

    public boolean isRecording() {
        return recording;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public void onDestroy() {
        removeOverlay();
        recording = false;
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!recording || scanPending) return;
        scanPending = true;
        handler.postDelayed(scan, 300);
    }

    public void start() {
        if (recording) return;
        recording = true;
        collector = new FbParser.Collector();
        debug.clear();
        showOverlay();
        updateCounter();
        handler.postDelayed(scan, 300);
    }

    public void stop() {
        if (!recording) return;
        handler.removeCallbacks(scan);
        scan.run(); // last look at the current screen
        recording = false;
        scanPending = false;
        removeOverlay();
        save();
    }

    private final Runnable scan = new Runnable() {
        @Override
        public void run() {
            scanPending = false;
            if (!recording) return;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            if (root.getPackageName() == null || !FB_PACKAGE.equals(root.getPackageName().toString())) {
                return;
            }
            ArrayList<FbParser.Item> items = new ArrayList<>();
            collect(root, items, 0);
            int w = getResources().getDisplayMetrics().widthPixels;
            List<FbParser.Comment> found = FbParser.parse(items, w);
            collector.add(found, w);
            for (FbParser.Item it : items) {
                if (debug.size() >= MAX_DEBUG_LINES) break;
                debug.add("[" + it.l + "," + it.t + "-" + it.r + "," + it.b + "] "
                        + it.s.replace("\n", " ⏎ "));
            }
            updateCounter();
        }
    };

    /** Collects visible text; a node's description only counts if nothing below it has text. */
    private boolean collect(AccessibilityNodeInfo n, List<FbParser.Item> out, int depth) {
        if (n == null || depth > 60) return false;
        boolean childHasText = false;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo ch = n.getChild(i);
            if (ch != null && collect(ch, out, depth + 1)) childHasText = true;
        }
        if (!n.isVisibleToUser()) return childHasText;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        CharSequence text = n.getText();
        CharSequence desc = n.getContentDescription();
        String s = null;
        if (text != null && text.toString().trim().length() > 0) {
            s = text.toString().trim();
        } else if (!childHasText && desc != null && desc.toString().trim().length() > 0) {
            s = desc.toString().trim();
        }
        if (s != null) out.add(new FbParser.Item(s, r.left, r.top, r.right, r.bottom));
        return childHasText || s != null;
    }

    private void save() {
        List<FbParser.Comment> comments = collector.mainComments();
        String stamp = new SimpleDateFormat("dd-MM HH.mm", Locale.ITALY).format(new Date());
        StringBuilder dbg = new StringBuilder("Clip Manager – dati grezzi modalità Facebook " + stamp + "\n"
                + "Commenti riconosciuti: " + comments.size() + "\n\n");
        for (String line : debug) dbg.append(line).append('\n');
        TxtExporter.writeRaw(this, "facebook_debug.txt", dbg.toString());

        if (comments.isEmpty()) {
            Toast.makeText(this, "Nessun commento riconosciuto. Hai aperto un post e scorso i commenti?",
                    Toast.LENGTH_LONG).show();
            return;
        }
        ArrayList<String> texts = new ArrayList<>();
        for (FbParser.Comment c : comments) {
            String n = FbParser.shortName(c.name);
            texts.add(n.isEmpty() ? c.text : n + ": " + c.text);
        }
        Store store = Store.get(this);
        Store.ClipList l = store.createList("Commenti FB " + stamp);
        store.addToList(l.id, texts);
        Toast.makeText(this, comments.size() + " commenti salvati in «" + l.name + "»",
                Toast.LENGTH_LONG).show();
        Intent open = new Intent(this, ListActivity.class)
                .putExtra(ListActivity.EXTRA_ID, l.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(open);
    }

    // ---------------------------------------------------------------- floating counter

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void showOverlay() {
        if (overlay != null) return;
        final WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(4), dp(4), dp(4));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE0283593);
        bg.setCornerRadius(dp(24));
        bar.setBackground(bg);

        counter = new TextView(this);
        counter.setTextColor(0xFFFFFFFF);
        counter.setTextSize(14);
        counter.setPadding(0, 0, dp(8), 0);
        bar.addView(counter);

        Button stop = new Button(this);
        stop.setText("STOP");
        stop.setTextColor(0xFFFFFFFF);
        GradientDrawable sb = new GradientDrawable();
        sb.setColor(0xFFFF7043);
        sb.setCornerRadius(dp(20));
        stop.setBackground(sb);
        stop.setMinWidth(0);
        stop.setMinHeight(0);
        stop.setPadding(dp(14), dp(6), dp(14), dp(6));
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stop();
            }
        });
        bar.addView(stop);

        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = dp(8);
        lp.y = dp(96);

        // drag the counter up/down if it covers something
        counter.setOnTouchListener(new View.OnTouchListener() {
            float downY;
            int startY;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    downY = e.getRawY();
                    startY = lp.y;
                } else if (e.getAction() == MotionEvent.ACTION_MOVE && overlay != null) {
                    lp.y = Math.max(0, startY + (int) (e.getRawY() - downY));
                    wm.updateViewLayout(overlay, lp);
                }
                return true;
            }
        });
        try {
            wm.addView(bar, lp);
            overlay = bar;
        } catch (Exception e) {
            overlay = null;
        }
    }

    private void updateCounter() {
        if (counter == null || collector == null) return;
        counter.setText("● FB  " + collector.mainComments().size() + " commenti");
    }

    private void removeOverlay() {
        if (overlay == null) return;
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(overlay);
        } catch (Exception ignored) {
        }
        overlay = null;
        counter = null;
    }
}
