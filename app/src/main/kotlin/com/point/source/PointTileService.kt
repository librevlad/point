package com.point.source

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * Плитка «Point» в шторке (#246): один тап открывает экран выбора источника.
 *
 * Отдельная от плитки «Общий буфер» (#161): та синхронизирует буфер с ПК и объекта не рождает.
 */
class PointTileService : TileService() {

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
