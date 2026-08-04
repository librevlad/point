package com.point.source

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * Плитка «Point» в шторке (#246): один тап открывает экран выбора источника.
 *
 * Отдельная от плитки «Общий буфер» (#161): та синхронизирует буфер с ПК и объекта не рождает.
 *
 * Плитка ещё и **сама рассказывает о себе** (#456). Спросить систему «а стоит ли моя плитка»
 * нельзя — такого API нет, — но плитка узнаёт об этом первой: её добавили, шторка её слушает, её
 * убрали. Без этого экран выбора предлагал бы поставить плитку тому, у кого она уже стоит.
 */
class PointTileService : TileService() {

    override fun onTileAdded() {
        super.onTileAdded()
        rememberShadeTile(this, added = true)
    }

    /** Шторка открылась и плитка в ней есть — самый надёжный признак, что она на месте. */
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
