package com.point.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import com.point.core.flow.CalendarInserter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Opens the calendar's "new event" screen via ACTION_INSERT from the application context (no
 * Activity), pre-filled with the title. Mirrors [AndroidUrlOpener], but ACTION_INSERT on the
 * calendar's content URI instead of a scheme. Throws a clean message if no calendar app exists.
 */
class AndroidCalendarInserter @Inject constructor(
    @ApplicationContext private val context: Context,
) : CalendarInserter {

    override suspend fun insertEvent(title: String) {
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            error("Нет приложения-календаря")
        }
    }
}
