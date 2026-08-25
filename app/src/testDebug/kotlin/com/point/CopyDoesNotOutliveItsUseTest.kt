package com.point

import androidx.test.core.app.ApplicationProvider
import com.point.core.flow.FrameForModel
import com.point.core.flow.InlineFrame
import com.point.core.flow.ObjectClassifier
import com.point.core.flow.oncePerPath
import com.point.data.ScratchObjectStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Копия объекта не живёт вечно (#1012, #1245).
 *
 * Решение владельца 15.08.2026: копия живёт до следующего запуска, но не дольше суток.
 * Свежую трогать нельзя — к ней Point намеренно возвращает человека после смерти процесса,
 * и пустой scratch на старте оставил бы граф без байтов, то есть ровно того призрака,
 * которого чинит #998.
 */
@RunWith(RobolectricTestRunner::class)
class CopyDoesNotOutliveItsUseTest {

    @Test fun `брошенная копия убирается, свежая остаётся`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "scratch-${System.nanoTime()}")
        dir.mkdirs()
        val abandoned = File(dir, "вчерашний").apply { writeText("байты") }
        val fresh = File(dir, "сегодняшний").apply { writeText("байты") }
        val abandonedSet = File(dir, "набор").apply { mkdirs() }
        File(abandonedSet, "страница").writeText("байты")

        val dayAgo = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        abandoned.setLastModified(dayAgo - 60_000)
        abandonedSet.setLastModified(dayAgo - 60_000)

        val store = ScratchObjectStore(
            ApplicationProvider.getApplicationContext(),
            ObjectClassifier(),
            dir,
            FrameForModel.NONE,
        )
        store.forgetOlderThan(dayAgo)

        assertFalse("брошенная копия осталась лежать на диске", abandoned.exists())
        assertFalse("брошенный набор остался лежать на диске", abandonedSet.exists())
        assertTrue("свежая копия убрана — человеку некуда возвращаться", fresh.exists())
    }

    /**
     * Кадр для модели не переживает копию, из которой сделан (#1245).
     *
     * Готовый кадр помнится, чтобы цепочка провайдеров не кодировала один снимок по разу на
     * каждого. Но это строка base64 — заметно больше самого файла, у которого потолок 15 МБ,
     * — и без общего срока она осталась бы висеть после того, как работа закончилась:
     * `clear()` стирал байты с диска, а память готовилки не трогал никто. Решение владельца
     * по #1245 — срок жизни памяти до `ObjectStore.clear()`.
     */
    @Test fun `копия отпущена — кадр отпущен вместе с ней`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "scratch-${System.nanoTime()}")
        dir.mkdirs()
        val photo = File(dir, "снимок.jpg").apply { writeText("байты снимка") }

        var preparations = 0
        val frames = FrameForModel { _, mime ->
            preparations++
            InlineFrame("кадр в base64", mime)
        }.oncePerPath()

        val store = ScratchObjectStore(
            ApplicationProvider.getApplicationContext(),
            ObjectClassifier(),
            dir,
            frames,
        )

        frames.of(photo.absolutePath, "image/jpeg")
        store.clear()
        frames.of(photo.absolutePath, "image/jpeg")

        assertEquals("кадр остался в памяти после того, как копия убрана", 2, preparations)
    }
}
