package com.point

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * The «Общий буфер» Quick Settings tile (#161). One tap from the shade launches the momentary
 * [ClipboardSyncActivity], which is what gets the clipboard access Android denies a background app —
 * so the clipboard syncs with the PC from anywhere, without keeping Point open.
 */
class ClipboardTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, ClipboardSyncActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
