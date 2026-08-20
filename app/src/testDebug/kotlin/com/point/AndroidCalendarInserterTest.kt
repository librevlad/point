package com.point

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import com.point.data.AndroidCalendarInserter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class AndroidCalendarInserterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val inserter = AndroidCalendarInserter(context)

    private fun insertIntent() =
        Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)

    /**
     * Вторая половина #1131: если календаря на устройстве нет, честное слово звучит
     * до чужого экрана — а не после дороги в системный вход, из которого человек
     * вернётся ни с чем.
     */
    @Test fun `без календаря слово звучит до чужого экрана`() {
        val outcome = runCatching {
            runBlocking { inserter.insertEvent("Счёт 4417", LocalDate.of(2026, 8, 18)) }
        }

        assertEquals(AndroidCalendarInserter.NO_CALENDAR, outcome.exceptionOrNull()?.message)
        assertNull("чужой экран не открывался", shadowOf(context as Application).nextStartedActivity)
    }

    @Test fun `с календарём намерение уезжает в него`() {
        shadowOf(context.packageManager).addResolveInfoForIntent(
            insertIntent(),
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = "com.android.calendar"
                    name = "EditEventActivity"
                    applicationInfo = ApplicationInfo().apply { packageName = "com.android.calendar" }
                }
            },
        )

        val title = "Счёт 4417"
        runBlocking { inserter.insertEvent(title, null) }

        val started = shadowOf(context as Application).nextStartedActivity
        assertEquals(Intent.ACTION_INSERT, started?.action)
        assertEquals(title, started?.getStringExtra(CalendarContract.Events.TITLE))
    }
}
