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
}
