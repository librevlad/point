package com.point

import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import com.point.data.AndroidCalendarInserter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate
import java.time.ZoneId

/**
 * Вторая половина #1131: слово о календаре звучит до чужого экрана.
 *
 * Мир Robolectric устроен как Android 11+: список приложений о календаре не знает
 * (`resolveActivity` отвечает «никого»), а запуск до календаря доходит. Ровно из-за этой
 * асимметрии вопрос «есть ли кому отдать?» перед запуском превращал живой календарь в ложный
 * отказ (находка адверсарного аудита): слово должно звучать от отказа системы в запуске,
 * а не от списка, который календаря не видит.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidCalendarInserterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app get() = shadowOf(context as Application)
    private val inserter = AndroidCalendarInserter(context)

    /**
     * Календаря нет: система отказывает в запуске, человек слышит об этом, оставаясь в Point,
     * — а не после дороги в системный вход, из которого он вернётся ни с чем.
     */
    @Test fun `без календаря слово звучит до чужого экрана`() {
        app.checkActivities(true)

        val outcome = runCatching {
            runBlocking { inserter.insertEvent("Счёт 4417", LocalDate.of(2026, 8, 18)) }
        }

        assertEquals(AndroidCalendarInserter.NO_CALENDAR, outcome.exceptionOrNull()?.message)
        assertNull("чужой экран не открывался", app.nextStartedActivity)
    }

    /**
     * Календарь стоит, но списку приложений не виден — как на Android 11+ без объявления в
     * манифесте: намерение всё равно уезжает в него вместе с найденным днём (#1035). Вопрос
     * списку перед запуском здесь отвечал «нет календаря», и человек оставался без события.
     */
    @Test fun `календарь, невидимый списку приложений, всё равно получает событие`() {
        val title = "Счёт 4417"
        val day = LocalDate.of(2026, 8, 18)

        runBlocking { inserter.insertEvent(title, day) }

        val started = app.nextStartedActivity
        assertEquals(Intent.ACTION_INSERT, started?.action)
        assertEquals(title, started?.getStringExtra(CalendarContract.Events.TITLE))
        val from = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(from, started?.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1))
        assertTrue(
            "событие на весь день: часа в знании нет",
            started?.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false) == true,
        )
    }
}
