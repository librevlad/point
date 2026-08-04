package com.point.source

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Чего источнику не хватает, чтобы начать. Спрашивать разрешение, которое уже дано, — то же
 * назойливое трение, от которого Point уходит.
 */
class PermissionsTest {

    @Test
    fun `нужного нет — просим именно его`() {
        assertEquals(
            listOf("android.permission.ACCESS_FINE_LOCATION"),
            missingPermissions(
                required = listOf("android.permission.ACCESS_FINE_LOCATION"),
                granted = emptySet(),
            ),
        )
    }

    @Test
    fun `всё уже дано — не спрашиваем ничего`() {
        assertEquals(
            emptyList<String>(),
            missingPermissions(
                required = listOf("android.permission.ACCESS_FINE_LOCATION"),
                granted = setOf("android.permission.ACCESS_FINE_LOCATION"),
            ),
        )
    }

    @Test
    fun `источнику ничего не нужно — спрашивать нечего`() {
        assertEquals(emptyList<String>(), missingPermissions(emptyList(), emptySet()))
    }

    // --- «Больше не спрашивать» отличается от «не сейчас» (#455) -----------------------------

    private val place = "android.permission.ACCESS_FINE_LOCATION"

    @Test
    fun `дали всё — источник начинает работу`() {
        assertEquals(
            PermissionOutcome.GRANTED,
            permissionOutcome(mapOf(place to true), willAskAgain = { error("спрашивать уже не о чем") }),
        )
    }

    @Test
    fun `отказали сейчас — путь прежний, тапнуть ещё раз`() {
        assertEquals(
            PermissionOutcome.DENIED,
            permissionOutcome(mapOf(place to false), willAskAgain = { true }),
        )
    }

    @Test
    fun `выбрали «больше не спрашивать» — дорога только через настройки`() {
        // Система вернёт отказ мгновенно и окна не покажет. До #455 человек получал здесь тот же
        // тост при каждом тапе и не мог узнать, что решение переехало в настройки.
        assertEquals(
            PermissionOutcome.BLOCKED,
            permissionOutcome(mapOf(place to false), willAskAgain = { false }),
        )
    }

    @Test
    fun `один закрытый навсегда решает за весь запрос`() {
        // Согласие по остальным не сдвинет источник с места — значит и разговор о нём тот же.
        assertEquals(
            PermissionOutcome.BLOCKED,
            permissionOutcome(
                mapOf("android.permission.CAMERA" to false, place to false),
                willAskAgain = { it == "android.permission.CAMERA" },
            ),
        )
    }

    @Test
    fun `спрашивать было нечего — это удача, а не отказ`() {
        assertEquals(PermissionOutcome.GRANTED, permissionOutcome(emptyMap(), willAskAgain = { false }))
    }
}
