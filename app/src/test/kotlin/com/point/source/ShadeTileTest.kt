package com.point.source

import android.app.StatusBarManager
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadeTileTest {

    @Test fun `до Android 13 предлагать нечего`() {

        assertFalse(tileOfferVisible(Build.VERSION_CODES.S_V2, known = false))
        assertTrue(tileOfferVisible(Build.VERSION_CODES.TIRAMISU, known = false))
    }

    @Test fun `когда плитка уже стоит, предложения нет`() {
        assertFalse(tileOfferVisible(Build.VERSION_CODES.UPSIDE_DOWN_CAKE, known = true))
    }

    @Test fun `итог просьбы назван словами, а не кодом системы`() {
        assertEquals(
            TileAddOutcome.ADDED,
            tileAddOutcome(StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED),
        )
        assertEquals(
            TileAddOutcome.ALREADY,
            tileAddOutcome(StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED),
        )
        assertEquals(
            TileAddOutcome.DECLINED,
            tileAddOutcome(StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED),
        )
    }

    @Test fun `незнакомый ответ системы это отказ системы, а не отказ человека`() {

        assertEquals(TileAddOutcome.FAILED, tileAddOutcome(1004))
        assertEquals(TileAddOutcome.FAILED, tileAddOutcome(-1))
    }

    @Test fun `плитка считается стоящей только после согласия или если уже стояла`() {
        assertTrue(TileAddOutcome.ADDED.tilePresent)
        assertTrue(TileAddOutcome.ALREADY.tilePresent)
        assertFalse(TileAddOutcome.DECLINED.tilePresent)

        assertFalse(TileAddOutcome.FAILED.tilePresent)
    }

    @Test fun `молчим там, где человек и так всё видел`() {
        assertNull(tileAddMessage(TileAddOutcome.ADDED))
        assertNull(tileAddMessage(TileAddOutcome.DECLINED))

        assertNotNull(tileAddMessage(TileAddOutcome.ALREADY))
        assertNotNull(tileAddMessage(TileAddOutcome.FAILED))
    }
}
