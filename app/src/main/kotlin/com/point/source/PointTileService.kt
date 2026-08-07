package com.point.source

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

class PointTileService : TileService() {

    override fun onTileAdded() {
        super.onTileAdded()
        rememberShadeTile(this, added = true)
    }

    override fun onStartListening() {
        super.onStartListening()
        rememberShadeTile(this, added = true)
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        rememberShadeTile(this, added = false)
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, SourcePickerActivity::class.java)
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
