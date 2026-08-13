package com.point.knock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.point.HomeActivity
import com.point.R

/**
 * Стук компьютера: «зайди, для тебя что-то есть» (#817).
 *
 * Компьютер кладёт просьбу в свою папку, а телефон приходит за ней сам, когда человек
 * открывает Point. Без стука это могло случиться вечером — просьба не пропадала, но и не
 * происходила.
 *
 * Через Google едет одно слово. Что именно просят, телефон узнаёт у самого компьютера, и
 * только потом говорит это человеку.
 */
object Knock {

    /** Один разговор — одно уведомление: вторая просьба не плодит третью строку в шторке. */
    const val ID = 817

    private const val CHANNEL = "knock"

    fun tell(context: Context, text: String) {
        channel(context)
        val open = Intent(context, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tap = android.app.PendingIntent.getActivity(
            context,
            0,
            open,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val note = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile_point)
            .setContentTitle(text)
            .setContentText(TAP)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        // Разрешения может не быть — человек его не давал или отозвал. Это не сбой: просьба
        // ждёт на месте и разберётся, когда он откроет Point сам.
        runCatching { NotificationManagerCompat.from(context).notify(ID, note) }
    }

    private fun channel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = CHANNEL_WHAT
            }
        )
    }

    private const val CHANNEL_NAME = "Просьбы компьютера"
    private const val CHANNEL_WHAT = "Компьютер просит телефон что-то сделать с объектом"
    private const val TAP = "Нажмите, чтобы забрать объект и сделать"
}

/**
 * Что человеку сказать про просьбу компьютера, пока он её не открыл.
 *
 * Уведомление называет работу и объект — по нему видно, на что человек соглашается тапом.
 */
fun phoneRequestNotice(action: String, name: String): String =
    "Компьютер просит: $action" + name.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
