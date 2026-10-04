package com.simog.clipmanager;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/** Quick Settings tile: open a Facebook post, pull down the shade, tap "Commenti FB". */
public class FbTileService extends TileService {

    @Override
    public void onStartListening() {
        Tile tile = getQsTile();
        if (tile == null) return;
        FbCommentService s = FbCommentService.get();
        tile.setState(s != null && s.isRecording() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onClick() {
        FbCommentService s = FbCommentService.get();
        if (s == null) {
            // service not switched on yet: open the app, which explains how
            Intent i = new Intent(this, MainActivity.class)
                    .putExtra(MainActivity.EXTRA_FB_HELP, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 1, i,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            } else {
                startActivityAndCollapse(i);
            }
            return;
        }
        if (s.isRecording()) {
            s.stop();
        } else {
            s.start();
            Toast.makeText(this, "Modalità Facebook avviata: scorri i commenti, poi STOP",
                    Toast.LENGTH_LONG).show();
        }
        onStartListening();
    }
}
