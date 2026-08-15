package com.point.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import com.point.core.flow.CalendarInserter
import com.point.core.flow.NewEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import javax.inject.Inject

class AndroidCalendarInserter @Inject constructor(
    @ApplicationContext private val context: Context,
) : CalendarInserter {

    override suspend fun insertEvent(event: NewEvent) {
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, event.title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // Найденный день доезжает до календаря (#1035). Раньше уходило одно название, и
        // событие вставало на текущий момент: человек руками переставлял дату, ради которой
        // и нажал. Часа Point не знает — значит день целиком, а не выдуманное «в 12:00».
        event.on?.let { day ->
            val from = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val to = day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            intent
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, from)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, to)
                .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
        }

        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            error("Нет приложения-календаря")
        }
    }
}
