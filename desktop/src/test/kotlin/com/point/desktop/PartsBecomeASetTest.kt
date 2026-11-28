package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.OfficeTextExtractor
import com.point.core.flow.PagesCapability
import com.point.core.flow.PagesRealizer
import com.point.core.flow.Realizer
import com.point.core.flow.SlidesCapability
import com.point.core.flow.SlidesRealizer
import com.point.core.flow.collectionOrder
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * «Страницы» и «Слайды» на компьютере кончаются набором, в который можно войти (#1407).
 *
 * С тех пор как оба действия стали общими с телефоном, их результат — папка частей, а
 * компьютер принимал результат только файлом: папку молча выбрасывало, и человек читал
 * «Готово», не увидев ни одной страницы. Проверяется та же дорога, по которой идёт нажатие в
 * окне: `DesktopState` → общий исполнитель → приём результата `Inbox.land`, проведённый так
 * же, как в `Main.kt`.
 */
class PartsBecomeASetTest {

    @get:Rule val temp = TemporaryFolder()

    private fun pdfOfPages(count: Int): File {
        val file = temp.newFile("договор.pdf")
        PDDocument().use { doc ->
            repeat(count) { doc.addPage(PDPage()) }
            doc.save(file)
        }
        return file
    }

    private fun window(inbox: Inbox, capability: Capability, realizer: Realizer) = DesktopState(
        DesktopRegistry(setOf(capability)),
        DesktopResolver(setOf(realizer)),
        clipboard = { },
        reopenPath = { path -> File(path).takeIf(File::isFile)?.let { inbox.addFile(it.absolutePath) } },
        landResult = inbox::land,
    )

    /**
     * Нажать дверь — и дождаться, пока шаг ляжет в путь объекта.
     *
     * Запись шага идёт после приёма результата, поэтому дожидаемся события, а не срока: срок
     * здесь — только страховка от зависания, на здоровом коде он не наступает.
     */
    private fun DesktopState.pressAndWait(item: InboxItem, capability: CapabilityId) = runBlocking {
        onBubble(item, bubblesFor(item).single { it.capabilityId == capability })
        withTimeout(60_000) {
            journal.first { entries ->
                entries.any { entry ->
                    entry.path == item.obj.uri.value && entry.steps.any { it.capabilityId == capability.value }
                }
            }
        }
    }

    @Test
    fun `«Страницы» у PDF кончаются набором страниц в порядке документа, а не пустым «Готово»`() {
        val inbox = Inbox(temp.newFolder("inbox"))
        val st = window(inbox, PagesCapability(), PagesRealizer(PcPdfRasterizer(dpi = 18f)))
        val document = inbox.addFile(pdfOfPages(3).absolutePath)
        st.onReceived(document)

        st.pressAndWait(document, PagesCapability.ID)

        val set = st.items.value.singleOrNull { it.obj.state.kind == ObjectKind.COLLECTION }
        assertTrue("страницы не стали объектом — человеку осталось одно «Готово»", set != null)
        val pages = collectionChildren(set!!.obj)
        assertEquals(listOf("page-0001.png", "page-0002.png", "page-0003.png"), pages.map { File(it).name })
        assertTrue("в наборе путь к странице, которой нет на диске", pages.all { File(it).isFile })
        assertTrue("набор пришёл молча — его не отметили новым", set.obj.id in st.fresh.value)
        assertEquals("документ остался тем же объектом", document.obj.id, st.items.value.last().obj.id)
    }

    /**
     * Порядок слайдов — знание самого набора (#1207): по имени «Слайд 10» встал бы между
     * первым и вторым, и человек читал бы доклад не в том порядке.
     */
    @Test
    fun `«Слайды» ложатся набором в порядке презентации — десятый после второго`() {
        val inbox = Inbox(temp.newFolder("inbox-deck"))
        val said = listOf(1 to "Итого 320 грн", 2 to "Контакт: Ольга", 10 to "Спасибо")
        val deck = object : OfficeTextExtractor {
            override suspend fun extractText(obj: PointObject) = ""
            override suspend fun slides(obj: PointObject) = said
        }
        val st = window(inbox, SlidesCapability(), SlidesRealizer(PcScratchStore(temp.newFolder("scratch")), deck))
        val talk = inbox.addFile(temp.newFile("доклад.pptx").apply { writeText("слайды") }.absolutePath)
        st.onReceived(talk)

        st.pressAndWait(talk, SlidesCapability.ID)

        val set = st.items.value.singleOrNull { it.obj.state.kind == ObjectKind.COLLECTION }
        assertTrue("слайды не стали объектом — человеку осталось одно «Готово»", set != null)
        val expected = listOf(1, 2, 10).map(SlidesRealizer::slideName)
        assertEquals(expected, collectionChildren(set!!.obj).map { File(it).name })
        assertEquals("порядок потерялся вместе с папкой", expected, collectionOrder(set.obj.metadata))
        assertEquals("слайд потерял свои слова", said.map { it.second }, collectionChildren(set.obj).map { File(it).readText() })
    }

    /** Однострочный документ разобран так же честно: действие обещало набор, а не файл. */
    @Test
    fun `набор из одной страницы — тоже набор`() {
        val inbox = Inbox(temp.newFolder("inbox-one"))
        val st = window(inbox, PagesCapability(), PagesRealizer(PcPdfRasterizer(dpi = 18f)))
        val document = inbox.addFile(pdfOfPages(1).absolutePath)
        st.onReceived(document)

        st.pressAndWait(document, PagesCapability.ID)

        val set = st.items.value.single { it.obj.state.kind == ObjectKind.COLLECTION }
        assertEquals(1, collectionChildren(set.obj).size)
    }

    /**
     * Набор, в который легли страницы, — объект, с которым дальше работают, а не витрина
     * (#1407). Первый шаг с ним — «На телефон», и уехать должны страницы: прежде в очередь
     * вставал файл-манифест со списком путей этого компьютера, а окно отвечало «Ждёт
     * телефона». «Открыть» набору не предлагается — как и на телефоне: одного файла у набора
     * нет, и Windows отвечала «Открыть нечем».
     */
    @Test
    fun `«На телефон» у лёгших страниц отправляет сами страницы по порядку, «Открыть» набору не предлагается`() {
        val inbox = Inbox(temp.newFolder("inbox-send"))
        val outbox = Outbox(temp.newFolder("outbox"))
        val st = DesktopState(
            DesktopRegistry(setOf(PagesCapability(), PcToPhoneCapability(), PcOpenCapability())),
            DesktopResolver(
                setOf(
                    PagesRealizer(PcPdfRasterizer(dpi = 18f)),
                    PcToPhoneRealizer(outbox),
                    PcOpenRealizer({ error("набору нечего открывать одним файлом") }, { }),
                ),
            ),
            clipboard = { },
            reopenPath = { path -> File(path).takeIf(File::isFile)?.let { inbox.addFile(it.absolutePath) } },
            landResult = inbox::land,
        )
        val document = inbox.addFile(pdfOfPages(3).absolutePath)
        st.onReceived(document)
        st.pressAndWait(document, PagesCapability.ID)
        val set = st.items.value.single { it.obj.state.kind == ObjectKind.COLLECTION }

        val doors = st.bubblesFor(set).map { it.capabilityId.value }
        assertTrue("набору предложили «Открыть» одним файлом", "pc-open" !in doors)
        st.pressAndWait(set, CapabilityId(PC_TO_PHONE))

        val sent = outbox.entries().single()
        assertEquals("телефону уехал не архив страниц", "application/zip", sent.meta["mime"])
        val pages = collectionChildren(set.obj).map(::File)
        val shipped = java.util.zip.ZipFile(outbox.file(sent.id)!!).use { zip ->
            zip.entries().toList().map { it.name to zip.getInputStream(it).readBytes() }
        }
        assertEquals("порядок страниц потерялся в дороге", pages.map { it.name }, shipped.map { it.first })
        assertTrue(
            "вместо страниц уехало что-то другое",
            shipped.map { it.second }.zip(pages.map(File::readBytes)).all { (got, page) -> got.contentEquals(page) },
        )
    }
}
