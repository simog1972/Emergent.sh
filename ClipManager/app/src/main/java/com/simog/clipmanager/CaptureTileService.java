package com.simog.clipmanager;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile: copy something anywhere, pull down the shade, tap "Salva clip". */
public class CaptureTileService extends TileService {

    @Override
    public void onStartListening() {
        Tile tile = getQsTile();
        if (tile == null) return;
        Store.ClipList active = Store.get(this).getActiveList();
        tile.setState(active != null ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= 29) {
            tile.setSubtitle(active != null ? active.name : "Clip");
        }
        tile.updateTile();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onClick() {
        Intent i = new Intent(this, CaptureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapse(i);
        }
    }
}
