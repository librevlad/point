package com.point.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import com.point.core.flow.CalendarInserter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

class AndroidCalendarInserter @Inject constructor(
    @ApplicationContext private val context: Context,
) : CalendarInserter {

    override suspend fun insertEvent(title: String, day: LocalDate?) {
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // Найденный день доезжает до календаря (#1035). Событие на весь день: часа в знании
        // нет, а выдумывать его Point не станет.
        day?.let {
            val from = it.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, from)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, from + DAY_MS)
                .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
        }
        // Слово об отсутствии календаря звучит до чужого экрана (#1131): система отказывает
        // в запуске раньше, чем что-то откроется, и человек остаётся в Point с честным отказом,
        // а не возвращается ни с чем из системного входа. Спрашивать список приложений заранее
        // нельзя: Android 11+ прячет календарь от такого вопроса, но не от запуска, — и живой
        // календарь получал бы «нет календаря».
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            error(NO_CALENDAR)
        }
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
        const val NO_CALENDAR = "На этом устройстве нет календаря"
    }
}
