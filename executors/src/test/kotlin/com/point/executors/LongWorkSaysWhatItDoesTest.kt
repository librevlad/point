package com.point.executors

import com.point.core.flow.capabilities.ImageCapability
import com.point.core.flow.capabilities.DropLinkCapability
import com.point.core.flow.Clipboard
import com.point.core.flow.DropLink
import com.point.core.flow.Entity
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
import com.point.core.flow.Exporter
import com.point.core.flow.Latency
import com.point.core.flow.ObjectStore
import com.point.core.flow.QrReader
import com.point.core.flow.Sharer
import com.point.core.flow.Viewer
import com.point.core.flow.reportStage
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Долгое действие говорит, что делает сейчас, — а быстрое честно молчит (#288).
 *
 * У среза две половины, и вторая важнее первой. Стадия помогает только пока она правдива:
 * «Собираю страницу 3 из 12» — ответ, «Обрабатываю…» — та же крутилка, только словами. Поэтому
 * тест не считает строки по всем сорока пяти действиям, а держит обе границы поимённо: кто обязан
 * говорить и кто обязан молчать. Формальное «у всех есть стадия» зелёным светом покрыло бы ровно
 * ту выдумку, от которой этот срез и лечит.
 *
 * Правило со сторожем «всё, что дольше секунды, обязано отчитываться» здесь НЕ вводится — решение
 * владельца 05.08.2026: набор действий сейчас меняется, и закреплять правило рано (#555, веха 0.4).
 */
class LongWorkSaysWhatItDoesTest {

    @get:Rule val tmp = TemporaryFolder()

    // --- Долгие: слово есть ---

    /**
     * «Дать ссылку» — единственное действие, которое возит файл наружу и при этом забирает экран
     * целиком. Там человек и смотрел на голый счётчик секунд, пока его файл шёл по сети.
     */
    @Test
    fun `«Дать ссылку» говорит, что везёт файл, пока он едет`() = runTest {
        val file = tmp.newFile("report.pdf").apply { writeText("страница") }
        val obj = PointObject("id", "application/pdf", ScratchRef(file.absolutePath), ObjectState(ObjectKind.PDF))
        val drop = object : DropLink {
            override suspend fun give(path: String, fileName: String, mime: String) = "https://p.nt/abc"
        }

        val heard = stagesHeard { DropLinkRealizer(store(), drop).perform(obj, null) }

        assertEquals(listOf("Загружаю файл"), heard)
    }

    /**
     * У «Сжать» работы правда две — развернуть снимок в память и закодировать обратно, — и первое
     * слово сказано ДО первого касания пикселей. Дальше на JVM пути нет: `android.graphics` здесь
     * заглушка, декод обрывается, и «Сжимаю снимок» проверяется живьём на телефоне. Тот же приём и
     * та же оговорка, что в `ScanActionTest`, — сказано вслух, а не спрятано.
     */
    @Test
    fun `«Сжать» называет чтение снимка раньше, чем возьмётся за пиксели`() = runTest {
        val image = PointObject("id", "image/jpeg", ScratchRef("/tmp/фото.jpg"), ObjectState(ObjectKind.IMAGE))

        val heard = stagesHeard { ImageRealizer(store()).perform(image, null) }

        assertEquals("Читаю изображение", heard.firstOrNull())
    }

    /** Заговорившее действие не остаётся объявленным мгновенным — иначе слово негде показать. */
    @Test
    fun `оба заговоривших действия объявлены небыстрыми`() {
        assertTrue("«Сжать»", ImageCapability().meta.latency != Latency.INSTANT)
        assertTrue("«Дать ссылку»", DropLinkCapability().meta.latency != Latency.INSTANT)
    }

    // --- Быстрые: слова нет, и это не упущение ---

    /**
     * Ни одно мгновенное действие не обзавелось выдуманной стадией (критерий #288 №3).
     *
     * Здесь перечислены именно те, кому её было бы легче всего приписать «для полноты»: они не
     * мгновенны на глаз (читают файл, зовут движок, обходят каталог), но делают **одно неделимое
     * движение**. Разбить его нечем, а назвать целиком — значит сказать человеку ровно то, что он
     * и так прочитал на пузырьке, которого коснулся секунду назад.
     *
     * Список читается как решение, а не как перечень: каждому здесь молчание оставлено осознанно.
     */
    @Test
    fun `быстрые действия не обзавелись выдуманной стадией`() = runTest {
        val invented = mutableListOf<String>()
        suspend fun mustBeSilent(name: String, work: suspend () -> Unit) {
            val heard = stagesHeard { work() }
            if (heard.isNotEmpty()) invented += "«$name» выдумало стадию: $heard"
        }

        // Одно движение системы: буфер, экспорт, лист «Поделиться», чужое приложение.
        mustBeSilent("Скопировать") { CopyRealizer(clipboard(), com.point.core.flow.CircleClipboard.None).perform(textObject("телефон 050"), null) }
        mustBeSilent("Сохранить") { SaveRealizer(exporter()).perform(textObject("а"), null) }
        mustBeSilent("Поделиться") { ShareRealizer(sharer()).perform(textObject("а"), null) }
        mustBeSilent("Открыть") { OpenRealizer(viewer()).perform(textObject("а"), null) }
        // Обход коллекции здесь — не работа: собираются имена файлов, байты везёт система.
        mustBeSilent("Поделиться всем") { ShareAllRealizer(sharer()).perform(collection(), null) }
        // Слой слов уже прочитан, поиск идёт в памяти — стадия мигнула бы и исчезла.
        mustBeSilent("Найти в документе") { FindRealizer().perform(textObject("а"), "накладная") }
        // Один проход движка по тексту и запись списка: делить нечего.
        mustBeSilent("Собрать данные") { ExtractAllRealizer(store(), extractor()).perform(textObject("050"), null) }
        // Один декод кадра чужим кодом, который о своём ходе молчит.
        mustBeSilent("Считать QR") { ReadQrRealizer(store(), qrReader()).perform(textObject("а"), null) }

        assertTrue(invented.joinToString("\n"), invented.isEmpty())
    }

    /**
     * Сторож обязан что-то охранять.
     *
     * Проверка выше зелена и тогда, когда слух сломан: список пустых списков неотличим от списка
     * несказанного. Самая дорогая из возможных поломок — та, которой не видно ни в одном прогоне,
     * — поэтому здесь через тот же канал говорят заведомо.
     */
    @Test
    fun `сторож правда слышит сказанное, а не тишину`() = runTest {
        val heard = stagesHeard { reportStage("проверка слуха") }

        assertEquals(listOf("проверка слуха"), heard)
    }

    // --- подставные ---

    private fun store() = object : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("unused")
        override suspend fun ingestMultiple(sources: List<String>) = error("unused")
        override suspend fun put(result: ResultObject) = error("unused")
        override suspend fun children(collection: PointObject, limit: Int) = error("unused")
        override suspend fun readText(obj: PointObject, limit: Int) = error("unused")
        override suspend fun newScratchFile(extension: String) =
            ScratchRef(tmp.newFile("out-${System.nanoTime()}.$extension").absolutePath)
        override suspend fun clear() = Unit
    }

    private fun clipboard() = object : Clipboard {
        override suspend fun copy(text: String, label: String) = Unit
    }

    private fun exporter() = object : Exporter {
        override suspend fun export(obj: PointObject) = "Downloads/файл"
    }

    private fun sharer() = object : Sharer {
        override suspend fun share(obj: PointObject) = Unit
        override suspend fun shareAll(objs: List<PointObject>) = Unit
    }

    private fun viewer() = object : Viewer {
        override suspend fun view(obj: PointObject) = Unit
    }

    private fun extractor() = object : EntityExtractor {
        override suspend fun extract(text: String) = listOf(Entity(EntityType.PHONE, "050"))
    }

    private fun qrReader() = object : QrReader {
        override suspend fun decode(imagePath: String) = "https://p.nt/qr"
    }

    private fun textObject(content: String): PointObject {
        val f = tmp.newFile("text-${System.nanoTime()}.txt").apply { writeText(content) }
        return PointObject("id", "text/plain", ScratchRef(f.absolutePath), ObjectState(ObjectKind.TEXT))
    }

    private fun collection(): PointObject {
        val dir = tmp.newFolder("collection-${System.nanoTime()}")
        File(dir, "a.txt").writeText("раз")
        return PointObject(
            "id", "inode/directory", ScratchRef(dir.absolutePath), ObjectState(ObjectKind.COLLECTION),
        )
    }
}
