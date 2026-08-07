package com.point.source

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SourceRestoreTest {

    private val camera = CameraSource()
    private val sources = listOf(ClipboardSource(com.point.FakeSharedTexts()), camera, VoiceSource())

    @Test fun `сохранённое имя возвращает того самого, кого ждали`() {
        assertSame(camera, restoredSource(sources, "camera"))
    }

    @Test fun `без сохранённого имени ждать некого`() {
        assertNull(restoredSource(sources, null))
        assertNull(restoredSource(sources, ""))
        assertNull(restoredSource(sources, "   "))
    }

    @Test fun `имени, которого в наборе нет, никто не ждёт`() {

        assertNull(restoredSource(sources, "телепатия"))
    }

    @Test fun `камера помнит, куда писала кадр, и после пересоздания`() {
        val path = File(File("point-cache", "capture"), "shot-1754300000000.jpg").absolutePath

        val beforeUnload = CameraSource().apply { restoreState(path) }
        val saved = beforeUnload.saveState()
        val afterUnload = CameraSource().apply { restoreState(saved) }

        assertEquals(path, saved)
        assertEquals(path, afterUnload.saveState())
    }

    @Test fun `пустая строка кадром не считается`() {

        assertNull(CameraSource().apply { restoreState("") }.saveState())
        assertNull(CameraSource().apply { restoreState(null) }.saveState())
    }

    @Test fun `источнику без своей памяти сохранять нечего`() {

        val clipboard = ClipboardSource(com.point.FakeSharedTexts())
        assertNull(clipboard.saveState())
        clipboard.restoreState("что-то чужое")
        assertNull(clipboard.saveState())
    }

    @Test fun `круг через сохранённое состояние экрана возвращает и источник, и кадр`() {
        val path = File("point-cache", "shot.jpg").absolutePath
        camera.restoreState(path)

        val savedId = camera.id
        val savedState = camera.saveState()

        val fresh = CameraSource()
        val restored = restoredSource(listOf(ClipboardSource(com.point.FakeSharedTexts()), fresh, VoiceSource()), savedId)
        restored?.restoreState(savedState)

        assertSame(fresh, restored)
        assertEquals(path, restored?.saveState())
    }
}
