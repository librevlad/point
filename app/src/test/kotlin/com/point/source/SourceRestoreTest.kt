package com.point.source

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Снятый кадр переживает выгрузку экрана (#454).
 *
 * Экран выбора источника прозрачный и лёгкий, а ждёт он за камерой — самым тяжёлым приложением
 * телефона. Прижало по памяти — выгружают первым его, и до этой починки возвращение выглядело так:
 * фотография снята, файл записан, а `pending` и путь к кадру исчезли вместе с экраном. Колбэк
 * видел пустоту и молча закрывался. Здесь судятся обе половины: экран помнит, КОГО ждёт, а камера
 * — КУДА писала.
 */
class SourceRestoreTest {

    /** Тот же набор, что собирает Hilt: экран ищет в нём по имени, а не по месту. */
    private val camera = CameraSource()
    private val sources = listOf(ClipboardSource(), camera, VoiceSource())

    @Test fun `сохранённое имя возвращает того самого, кого ждали`() {
        assertSame(camera, restoredSource(sources, "camera"))
    }

    @Test fun `без сохранённого имени ждать некого`() {
        assertNull(restoredSource(sources, null))
        assertNull(restoredSource(sources, ""))
        assertNull(restoredSource(sources, "   "))
    }

    @Test fun `имени, которого в наборе нет, никто не ждёт`() {
        // Источник могли убрать между версиями — это не повод достать чужой объект из набора.
        assertNull(restoredSource(sources, "телепатия"))
    }

    @Test fun `камера помнит, куда писала кадр, и после пересоздания`() {
        val path = File(File("point-cache", "capture"), "shot-1754300000000.jpg").absolutePath

        // Так это выглядит на телефоне: экран выгрузили с одним объектом камеры, вернули с другим.
        val beforeUnload = CameraSource().apply { restoreState(path) }
        val saved = beforeUnload.saveState()
        val afterUnload = CameraSource().apply { restoreState(saved) }

        assertEquals(path, saved)
        assertEquals(path, afterUnload.saveState())
    }

    @Test fun `пустая строка кадром не считается`() {
        // Сохранять было нечего — значит и восстанавливать нечего: файл с пустым именем это не файл.
        assertNull(CameraSource().apply { restoreState("") }.saveState())
        assertNull(CameraSource().apply { restoreState(null) }.saveState())
    }

    @Test fun `источнику без своей памяти сохранять нечего`() {
        // Буфер и голос ничего не держат между «запросили» и «вернулись» — умолчание контракта.
        val clipboard = ClipboardSource()
        assertNull(clipboard.saveState())
        clipboard.restoreState("что-то чужое")
        assertNull(clipboard.saveState())
    }

    @Test fun `круг через сохранённое состояние экрана возвращает и источник, и кадр`() {
        val path = File("point-cache", "shot.jpg").absolutePath
        camera.restoreState(path)

        // Всё, что уезжает в Bundle экрана, — две строки: кто и что он помнил.
        val savedId = camera.id
        val savedState = camera.saveState()

        // Возвращение: набор собран заново (Hilt отдаёт новые объекты), экран ищет по имени.
        val fresh = CameraSource()
        val restored = restoredSource(listOf(ClipboardSource(), fresh, VoiceSource()), savedId)
        restored?.restoreState(savedState)

        assertSame(fresh, restored)
        assertEquals(path, restored?.saveState())
    }
}
