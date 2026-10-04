package com.simog.clipmanager;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Grabs the clipboard every time one of our screens gets focus, plus shared dialog helpers. */
public abstract class BaseActivity extends Activity {

    public interface TextCallback {
        void onText(String text, boolean checked);
    }

    protected Store store;

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = Store.get(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) return;
        String where = store.captureClipboard(this);
        if (where != null) toast("Clip salvato in «" + where + "»");
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    protected abstract void refresh();

    protected void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    protected int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    protected static String formatTime(long t) {
        return new SimpleDateFormat("dd/MM HH:mm", Locale.ITALY).format(new Date(t));
    }

    /** Text input dialog; checkboxLabel != null adds a checkbox (checked by default). */
    protected void askText(String title, String initial, boolean multiLine, String checkboxLabel,
                           final TextCallback cb) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText input = new EditText(this);
        if (multiLine) {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            input.setMaxLines(10);
        } else {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            input.setSingleLine(true);
            input.setHint("Nome");
        }
        if (initial != null) {
            input.setText(initial);
            input.setSelection(initial.length());
        }
        box.addView(input);
        final CheckBox check = new CheckBox(this);
        if (checkboxLabel != null) {
            check.setText(checkboxLabel);
            check.setChecked(true);
            box.addView(check);
        }
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setNegativeButton("Annulla", null)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String t = input.getText().toString();
                        if (!multiLine && t.trim().isEmpty()) {
                            toast("Il nome non può essere vuoto");
                            return;
                        }
                        cb.onText(multiLine ? t : t.trim(), check.isChecked());
                    }
                })
                .create();
        if (d.getWindow() != null) {
            d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        d.show();
        input.requestFocus();
    }

    protected void confirm(String message, final Runnable onYes) {
        new AlertDialog.Builder(this)
                .setMessage(message)
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Sì", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        onYes.run();
                    }
                })
                .show();
    }
}
