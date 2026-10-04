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
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Floating START/STOP bar. Between START and STOP it reads the text of whatever is on screen
 * (all windows except our own bar and the system bars), recognises Facebook-style comments
 * and on STOP saves them into a new list. If no comment is recognised it saves all the text read.
 */
public class FbCommentService extends AccessibilityService {

    public static final String FB_PACKAGE = "com.facebook.katana";
    private static final int MAX_DEBUG_LINES = 6000;
    private static final long SCAN_EVERY_MS = 700;

    private static FbCommentService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean recording;
    private FbParser.Collector collector;
    /** Every useful text read, in reading order: saved when no comment is recognised. */
    private final LinkedHashSet<String> texts = new LinkedHashSet<>();
    private final LinkedHashSet<String> debug = new LinkedHashSet<>();
    private int scans;

    private View bar;
    private WindowManager.LayoutParams barParams;
    private TextView label;
    private Button mainButton;
    private Button autoButton;
    /** AUTO: open "Altro"/more comments, read, scroll, repeat until the end, then save. */
    private boolean auto;
    private int autoSteps, openRounds, sameCount, noFbCount;
    private String lastSig = "", prevSig = null;
    private boolean lastHadFacebook;
    /** Collapsed comment texts already tapped, so a tap that doesn't expand isn't repeated. */
    private final java.util.HashSet<String> tapped = new java.util.HashSet<>();

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
            if (me.equals(ComponentName.unflattenFromString(s))) return true;
        }
        return false;
    }

    public boolean isRecording() {
        return recording;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        showBar();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        recording = false;
        hideBar();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // the timer does the work; events just make it react sooner after a scroll
        if (recording && event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            handler.removeCallbacks(tick);
            handler.postDelayed(tick, 250);
        }
    }

    // ---------------------------------------------------------------- start / stop

    public void start() {
        if (recording) return;
        recording = true;
        scans = 0;
        collector = new FbParser.Collector();
        texts.clear();
        debug.clear();
        tapped.clear();
        showBar();
        updateBar();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    /** Hands-free: reads the whole post by itself and saves when it reaches the end. */
    public void startAuto() {
        start();
        auto = true;
        autoSteps = 0;
        openRounds = 0;
        sameCount = 0;
        noFbCount = 0;
        prevSig = null;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(autoStep);
        handler.postDelayed(autoStep, 600);
        updateBar();
    }

    public boolean isAuto() {
        return auto;
    }

    public void stop() {
        if (!recording) return;
        auto = false;
        handler.removeCallbacks(autoStep);
        handler.removeCallbacks(tick);
        scanNow(); // last look at the current screen
        recording = false;
        updateBar();
        save();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!recording || auto) return;
            // manual mode: open any "Altro" in view; the next tick reads the full texts
            expandMore();
            scanNow();
            handler.removeCallbacks(tick);
            handler.postDelayed(tick, SCAN_EVERY_MS);
        }
    };

    private final Runnable autoStep = new Runnable() {
        @Override
        public void run() {
            if (!recording || !auto) return;
            autoSteps++;
            int opened = expandMore();
            if (opened > 0 && openRounds < 3) {
                openRounds++;
                handler.postDelayed(autoStep, 1000); // let the texts/comments load
                return;
            }
            openRounds = 0;
            scanNow();
            // left Facebook (or it navigated away): stop rather than scroll something else
            noFbCount = lastHadFacebook ? 0 : noFbCount + 1;
            sameCount = lastSig.equals(prevSig) ? sameCount + 1 : 0;
            prevSig = lastSig;
            if (sameCount >= 3 || noFbCount >= 2 || autoSteps > 600) {
                stop();
                return;
            }
            scrollDown();
            handler.postDelayed(autoStep, 1400);
        }
    };

    /** Swipes up by ~40% of the screen (slow, no fling) so consecutive screens overlap. */
    private void scrollDown() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        android.graphics.Path p = new android.graphics.Path();
        float x = dm.widthPixels * 0.35f;
        p.moveTo(x, dm.heightPixels * 0.72f);
        p.lineTo(x, dm.heightPixels * 0.32f);
        try {
            android.accessibilityservice.GestureDescription g =
                    new android.accessibilityservice.GestureDescription.Builder()
                            .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(p, 0, 500))
                            .build();
            if (dispatchGesture(g, null, null)) return;
        } catch (Exception ignored) {
        }
        scrollForwardLargest();
    }

    /** Fallback when gestures are unavailable: scroll the biggest scrollable thing on screen. */
    private void scrollForwardLargest() {
        AccessibilityNodeInfo best = null;
        long bestArea = 0;
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null || isOurs(root)) continue;
                java.util.ArrayDeque<AccessibilityNodeInfo> q = new java.util.ArrayDeque<>();
                q.add(root);
                int seen = 0;
                while (!q.isEmpty() && seen++ < 3000) {
                    AccessibilityNodeInfo n = q.poll();
                    if (n.isScrollable() && n.isVisibleToUser()) {
                        Rect r = new Rect();
                        n.getBoundsInScreen(r);
                        long area = (long) r.width() * r.height();
                        if (area > bestArea) {
                            bestArea = area;
                            best = n;
                        }
                    }
                    for (int i = 0; i < n.getChildCount(); i++) {
                        AccessibilityNodeInfo c = n.getChild(i);
                        if (c != null) q.add(c);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (best != null) best.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
    }

    private void scanNow() {
        ArrayList<FbParser.Item> items = new ArrayList<>();
        ArrayList<String> sources = new ArrayList<>();
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            for (AccessibilityWindowInfo w : windows) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                        || w.getType() == AccessibilityWindowInfo.TYPE_SYSTEM) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null || isOurs(root)) continue;
                sources.add(String.valueOf(root.getPackageName()));
                collect(root, items, 0);
            }
            if (sources.isEmpty()) {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null && !isOurs(root)) {
                    sources.add(String.valueOf(root.getPackageName()));
                    collect(root, items, 0);
                }
            }
        } catch (Exception ignored) {
        }
        scans++;
        lastHadFacebook = false;
        for (String src : sources) if (src.startsWith("com.facebook")) lastHadFacebook = true;
        StringBuilder sig = new StringBuilder();
        for (FbParser.Item it : items) sig.append(it.s).append('|').append(it.t).append('\n');
        lastSig = Integer.toHexString(sig.toString().hashCode()) + ":" + items.size();
        int w = getResources().getDisplayMetrics().widthPixels;
        collector.add(FbParser.parse(items, w), w);

        ArrayList<FbParser.Item> sorted = new ArrayList<>(items);
        Collections.sort(sorted, new Comparator<FbParser.Item>() {
            @Override
            public int compare(FbParser.Item a, FbParser.Item b) {
                return a.t != b.t ? Integer.compare(a.t, b.t) : Integer.compare(a.l, b.l);
            }
        });
        for (FbParser.Item it : sorted) {
            if (!FbParser.isJunk(it.s) && !FbParser.isAction(it.s)) texts.add(FbParser.stripMore(it.s));
            if (debug.size() < MAX_DEBUG_LINES) {
                debug.add("[" + it.l + "," + it.t + "-" + it.r + "," + it.b + "] "
                        + it.s.replace("\n", " ⏎ "));
            }
        }
        if (debug.size() < MAX_DEBUG_LINES) debug.add("--- app: " + sources);
        updateBar();
    }

    private boolean isOurs(AccessibilityNodeInfo root) {
        CharSequence p = root.getPackageName();
        return p == null || getPackageName().contentEquals(p) || "com.android.systemui".contentEquals(p);
    }

    /** Collects visible text; a node's description only counts if nothing below it has text. */
    private boolean collect(AccessibilityNodeInfo n, List<FbParser.Item> out, int depth) {
        if (n == null || depth > 80) return false;
        boolean childHasText = false;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo ch = n.getChild(i);
            if (ch != null && collect(ch, out, depth + 1)) childHasText = true;
        }
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.width() <= 0 || r.height() <= 0) return childHasText;
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

    // ---------------------------------------------------------------- "Altro" expander

    /**
     * Taps every "Altro" / "See more" visible on screen (not in our bar, not in system UI).
     * Returns how many were tapped.
     */
    public int expandMore() {
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int[] count = {0};
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            boolean any = false;
            for (AccessibilityWindowInfo w : windows) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                        || w.getType() == AccessibilityWindowInfo.TYPE_SYSTEM) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null || isOurs(root)) continue;
                any = true;
                expandIn(root, screenH, count, 0);
            }
            if (!any) {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null && !isOurs(root)) expandIn(root, screenH, count, 0);
            }
        } catch (Exception ignored) {
        }
        return count[0];
    }

    private void expandIn(AccessibilityNodeInfo n, int screenH, int[] count, int depth) {
        if (n == null || depth > 80) return;
        CharSequence text = n.getText();
        CharSequence desc = n.getContentDescription();
        String t = text != null ? text.toString() : "";
        String d = desc != null ? desc.toString() : "";
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        boolean onScreen = r.height() > 0 && r.bottom > 0 && r.top < screenH;
        if (onScreen && (FbParser.isLoadMoreComments(t) || (t.isEmpty() && FbParser.isLoadMoreComments(d)))) {
            int cy = r.centerY();
            if (cy > screenH * 6 / 100 && cy < screenH * 92 / 100 && clickUp(n, 3)) count[0]++;
            return;
        }
        if (onScreen && (FbParser.isMoreLabel(t) || (t.isEmpty() && FbParser.isMoreLabel(d)))) {
            // a standalone link: skip the very top/bottom of the screen (tab bars, toolbars)
            int cy = r.centerY();
            if (cy > screenH * 6 / 100 && cy < screenH * 92 / 100 && clickUp(n, 3)) count[0]++;
            return;
        }
        if (onScreen && (FbParser.endsWithMore(t) || (t.isEmpty() && FbParser.endsWithMore(d)))) {
            // "… Altro" inside the comment text: tap the link span, else the text itself (once)
            String key = t.isEmpty() ? d : t;
            if (tapped.add(key) && (clickMoreSpan(text) || clickUp(n, 2))) count[0]++;
            return;
        }
        for (int i = 0; i < n.getChildCount(); i++) expandIn(n.getChild(i), screenH, count, depth + 1);
    }

    /** Clicks the node, or its nearest clickable ancestor within maxUp levels. */
    private static boolean clickUp(AccessibilityNodeInfo n, int maxUp) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; cur != null && i <= maxUp; i++) {
            if (cur.isClickable()) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            cur = cur.getParent();
        }
        return false;
    }

    /** Android 8+: links inside a node's text are exposed as clickable spans we can trigger. */
    private static boolean clickMoreSpan(CharSequence text) {
        if (!(text instanceof Spanned)) return false;
        Spanned sp = (Spanned) text;
        ClickableSpan[] spans = sp.getSpans(0, sp.length(), ClickableSpan.class);
        for (int i = spans.length - 1; i >= 0; i--) {
            int a = sp.getSpanStart(spans[i]);
            int b = sp.getSpanEnd(spans[i]);
            if (a < 0 || b <= a) continue;
            if (FbParser.isMoreLabel(sp.subSequence(a, b).toString())) {
                try {
                    spans[i].onClick(null);
                    return true;
                } catch (Exception ignored) {
                    return false;
                }
            }
        }
        return false;
    }

    private void save() {
        List<FbParser.Comment> comments = collector.mainComments();
        String stamp = new SimpleDateFormat("dd-MM HH.mm", Locale.ITALY).format(new Date());
        StringBuilder dbg = new StringBuilder("Clip Manager – dati grezzi " + stamp + "\n"
                + "Letture schermo: " + scans + " – testi: " + texts.size()
                + " – commenti riconosciuti: " + comments.size() + "\n\n");
        for (String line : debug) dbg.append(line).append('\n');
        TxtExporter.writeRaw(this, "facebook_debug.txt", dbg.toString());

        ArrayList<String> out = new ArrayList<>();
        String title;
        if (!comments.isEmpty()) {
            title = "Commenti FB " + stamp;
            for (FbParser.Comment c : comments) {
                String n = FbParser.shortName(c.name);
                out.add(n.isEmpty() ? c.text : n + ": " + c.text);
            }
        } else {
            title = "Testo schermo " + stamp;
            out.addAll(texts);
        }
        if (out.isEmpty()) {
            Toast.makeText(this, "Non ho letto nessun testo (" + scans + " letture). "
                    + "Prova a disattivare e riattivare il servizio.", Toast.LENGTH_LONG).show();
            return;
        }
        Store store = Store.get(this);
        Store.ClipList l = store.createList(title);
        store.addToList(l.id, out);
        Toast.makeText(this, comments.isEmpty()
                ? "Nessun commento riconosciuto: salvati " + out.size() + " testi in «" + l.name + "»"
                : comments.size() + " commenti salvati in «" + l.name + "»", Toast.LENGTH_LONG).show();
        startActivity(new Intent(this, ListActivity.class)
                .putExtra(ListActivity.EXTRA_ID, l.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    // ---------------------------------------------------------------- floating bar

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static GradientDrawable round(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private Button smallButton(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(0xFFFFFFFF);
        b.setBackground(round(color, dp(20)));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(14), dp(6), dp(14), dp(6));
        return b;
    }

    public void showBar() {
        if (bar != null) {
            updateBar();
            return;
        }
        final WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(12), dp(4), dp(4), dp(4));
        box.setBackground(round(0xE0283593, dp(24)));

        label = new TextView(this);
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(14);
        label.setPadding(0, 0, dp(8), 0);
        box.addView(label);

        autoButton = smallButton("AUTO", 0xFF5C6BC0);
        autoButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startAuto();
                Toast.makeText(FbCommentService.this,
                        "AUTO: apro gli «Altro», leggo e scorro fino in fondo. Non toccare lo schermo.",
                        Toast.LENGTH_LONG).show();
            }
        });
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gap.rightMargin = dp(6);
        box.addView(autoButton, gap);

        mainButton = smallButton("START", 0xFF43A047);
        mainButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (recording) stop();
                else start();
            }
        });
        box.addView(mainButton);

        Button close = smallButton("✕", 0x00000000);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (recording) stop();
                hideBar();
                Toast.makeText(FbCommentService.this,
                        "Barra chiusa. Riaprila da Clip Manager o dal riquadro «Commenti FB».",
                        Toast.LENGTH_LONG).show();
            }
        });
        box.addView(close);

        barParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        barParams.gravity = Gravity.TOP | Gravity.END;
        barParams.x = dp(8);
        barParams.y = dp(96);

        // drag the label to move the bar up/down
        label.setOnTouchListener(new View.OnTouchListener() {
            float downY;
            int startY;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    downY = e.getRawY();
                    startY = barParams.y;
                } else if (e.getAction() == MotionEvent.ACTION_MOVE && bar != null) {
                    barParams.y = Math.max(0, startY + (int) (e.getRawY() - downY));
                    wm.updateViewLayout(bar, barParams);
                }
                return true;
            }
        });
        try {
            wm.addView(box, barParams);
            bar = box;
        } catch (Exception e) {
            bar = null;
        }
        updateBar();
    }

    private void updateBar() {
        if (bar == null) return;
        if (recording) {
            int n = collector == null ? 0 : collector.mainComments().size();
            label.setText((auto ? "AUTO ● " : "● ") + n + " commenti · " + texts.size() + " testi");
            autoButton.setVisibility(View.GONE);
            mainButton.setText("STOP");
            mainButton.setBackground(round(0xFFFF7043, dp(20)));
        } else {
            label.setText("Clip  ⇕");
            autoButton.setVisibility(View.VISIBLE);
            mainButton.setText("START");
            mainButton.setBackground(round(0xFF43A047, dp(20)));
        }
    }

    public void hideBar() {
        if (bar == null) return;
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(bar);
        } catch (Exception ignored) {
        }
        bar = null;
        label = null;
        mainButton = null;
        autoButton = null;
    }

    public boolean isBarVisible() {
        return bar != null;
    }
}
