package com.simog.clipmanager;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * Invisible screen used to save a clip without opening the app:
 * - text shared to us (ACTION_SEND) or picked from the text-selection menu (PROCESS_TEXT);
 * - otherwise (Quick Settings tile) it reads the clipboard as soon as it gets window focus,
 *   which Android 10+ requires for clipboard access.
 */
public class CaptureActivity extends Activity {

    private boolean done;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent i = getIntent();
        CharSequence text = null;
        if (Intent.ACTION_SEND.equals(i.getAction())) {
            text = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
        } else if (Intent.ACTION_PROCESS_TEXT.equals(i.getAction())) {
            text = i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        }
        if (text != null) {
            if (text.toString().trim().isEmpty()) finishWith("Niente da salvare");
            else finishWith("Clip salvato in «" + Store.get(this).addText(text.toString()) + "»");
            return;
        }
        // safety net in case focus never arrives
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                finishWith(null);
            }
        }, 2000);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || done) return;
        String where = Store.get(this).captureClipboard(this);
        finishWith(where != null ? "Clip salvato in «" + where + "»"
                : "Negli appunti non c'è niente di nuovo");
    }

    private void finishWith(String message) {
        if (done) return;
        done = true;
        if (message != null) Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
        finish();
        overridePendingTransition(0, 0);
    }
}
