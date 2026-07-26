package com.point.executors

import com.point.core.flow.AppTarget
import com.point.core.flow.ChosenApp
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #66 slice 4: a remembered app pick becomes a REAL capability in the derived graph —
 * same ranking, same usage learning as built-in actions. One pair per (app, kind).
 */
class AppActionsTest {

    private val telegram = ChosenApp(ObjectKind.IMAGE, "org.tg", "org.tg.Main", "Telegram")

    @Test
    fun `accepts only its own kind`() {
        val cap = AppCapability(telegram)
        assertTrue(cap.accepts(ObjectState(ObjectKind.IMAGE)))
        assertFalse(cap.accepts(ObjectState(ObjectKind.PDF)))
    }

    @Test
    fun `is labelled by the app and instant`() {
        val cap = AppCapability(telegram)
        assertEquals("Telegram", cap.label(ObjectState(ObjectKind.IMAGE)))
        assertEquals(com.point.core.flow.Latency.INSTANT, cap.meta.latency)
    }

    @Test
    fun `icon key carries the package so the UI can show the real app icon`() {
        assertEquals("app:org.tg", AppCapability(telegram).icon)
    }

    @Test
    fun `id is stable per app and kind`() {
        assertEquals(AppCapability(telegram).id, AppCapability(telegram).id)
        val pdfPick = telegram.copy(kind = ObjectKind.PDF)
        assertFalse(AppCapability(telegram).id == AppCapability(pdfPick).id)
    }

    @Test
    fun `realizer launches the app and finishes the flow`() = runTest {
        val launcher = FakeAppLauncher()
        val result = AppOpenRealizer(telegram, launcher).perform(obj(ObjectKind.IMAGE), null)

        assertEquals("org.tg", launcher.launched?.packageName)
        assertTrue(result is ActionResult.Done)
    }

    private class FakeAppLauncher : com.point.core.flow.AppLauncher {
        var launched: AppTarget? = null
        override suspend fun handlers(obj: com.point.core.model.PointObject) = emptyList<AppTarget>()
        override suspend fun handlersForMime(mime: String) = emptyList<AppTarget>()
        override suspend fun launch(target: AppTarget, obj: com.point.core.model.PointObject) {
            launched = target
        }
    }

    private fun obj(kind: ObjectKind) = com.point.core.model.PointObject(
        id = "o",
        mime = "image/png",
        uri = com.point.core.model.ScratchRef("/x"),
        state = ObjectState(kind),
    )
}
