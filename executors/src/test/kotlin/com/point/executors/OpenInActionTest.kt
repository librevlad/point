package com.point.executors

import com.point.core.flow.AppLauncher
import com.point.core.flow.AppTarget
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenInActionTest {

    private val obj = PointObject("id", "image/png", ScratchRef("/x.png"), ObjectState(ObjectKind.IMAGE))

    private class FakeLauncher(private val apps: List<AppTarget>) : AppLauncher {
        var launched: AppTarget? = null
        override suspend fun handlers(obj: PointObject) = apps
        override suspend fun launch(target: AppTarget, obj: PointObject) { launched = target }
    }

    @Test
    fun `accepts a file object but not a url or collection`() {
        val cap = OpenInCapability()
        assertTrue(cap.accepts(ObjectState(ObjectKind.IMAGE)))
        assertFalse(cap.accepts(ObjectState(ObjectKind.URL)))
        assertFalse(cap.accepts(ObjectState(ObjectKind.COLLECTION)))
    }

    @Test
    fun `realizer fallback opens the first available handler`() = runTest {
        val chrome = AppTarget("Chrome", "com.chrome", "A")
        val launcher = FakeLauncher(listOf(chrome))
        val result = OpenInRealizer(launcher).perform(obj)
        assertTrue(result is ActionResult.Done)
        assertEquals(chrome, launcher.launched)
    }

    @Test
    fun `realizer fails cleanly when nothing handles the object`() = runTest {
        val result = OpenInRealizer(FakeLauncher(emptyList())).perform(obj)
        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).recoverable)
    }
}
