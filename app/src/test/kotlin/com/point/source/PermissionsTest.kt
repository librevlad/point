package com.point.source

import org.junit.Assert.assertEquals
import org.junit.Test

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

        assertEquals(
            PermissionOutcome.BLOCKED,
            permissionOutcome(mapOf(place to false), willAskAgain = { false }),
        )
    }

    @Test
    fun `один закрытый навсегда решает за весь запрос`() {

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

    // ---- Android 12+: «точно или примерно» — один вопрос, а не два разрешения ----

    private val coarse = "android.permission.ACCESS_COARSE_LOCATION"

    @Test
    fun `выбранное «примерное» место — выдача, а не отказ`() {
        assertEquals(
            PermissionOutcome.GRANTED,
            permissionOutcome(
                mapOf(place to false, coarse to true),
                willAskAgain = { false },
            ),
        )
    }

    @Test
    fun `уже данное примерное место не переспрашивается точным`() {
        assertEquals(
            emptyList<String>(),
            missingPermissions(required = listOf(place, coarse), granted = setOf(coarse)),
        )
    }

    @Test
    fun `отказ по всему месту остаётся отказом`() {
        assertEquals(
            PermissionOutcome.DENIED,
            permissionOutcome(
                mapOf(place to false, coarse to false),
                willAskAgain = { true },
            ),
        )
    }
}
