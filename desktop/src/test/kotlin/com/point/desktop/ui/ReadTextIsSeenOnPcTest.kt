package com.point.desktop.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.point.core.flow.OoxmlOfficeTextExtractor
import com.point.core.flow.capabilities.OfficeCapability
import com.point.core.model.Feature
import com.point.desktop.DesktopRegistry
import com.point.desktop.DesktopResolver
import com.point.desktop.DesktopState
import com.point.desktop.Inbox
import com.point.desktop.InboxItem
import com.point.desktop.ObjectSource
import com.point.desktop.PcOfficeTextRealizer
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Прочитанное на компьютере видно человеку на компьютере (#995, DSK-040).
 *
 * «Извлечь текст» кладёт текст знанием на сам документ — и на этом экран замолкал: он
 * показывал текст только у объекта вида «текст» и только из его собственного файла. Человек
 * нажимал «Извлечь текст», читал «Текст прочитан — он у самого документа», дверь исчезала —
 * и текста не было нигде. Здесь экран собирается как у человека и читается то, что на нём
 * написано.
 */
class ReadTextIsSeenOnPcTest {

    @get:Rule val compose = createComposeRule()

    @get:Rule val temp = TemporaryFolder()

    private val state = DesktopState(
        registry = DesktopRegistry(setOf(OfficeCapability())),
        resolver = DesktopResolver(setOf(PcOfficeTextRealizer(OoxmlOfficeTextExtractor()))),
        clipboard = { },
    )

    private fun xlsx(cell: String): File {
        val file = temp.newFile("смета.xlsx")
        ZipOutputStream(file.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zos.write(
                """<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>$cell</t></is></c></row>
                </sheetData></worksheet>""".toByteArray(Charsets.UTF_8),
            )
            zos.closeEntry()
        }
        return file
    }

    /** Нажатие человека: то же действие, тот же исполнитель, то же приземление знания. */
    private fun extractText(): InboxItem {
        val item = Inbox(temp.newFolder("inbox")).addFile(xlsx(WRITTEN).absolutePath)
        state.onReceived(item, ObjectSource.LOCAL)
        state.runRemoteActionNow(OfficeCapability.ID.value, item)
        val read = state.items.value.first { it.obj.id == item.obj.id }
        assertTrue("документ так и не прочитан: ${read.obj.metadata}", read.obj.state.has(Feature.HAS_TEXT))
        return read
    }

    private fun show(item: InboxItem) {
        // Часы экрана не мотаются сами: у окна есть вечный тик «который час» (`rememberNow`),
        // и автоматическая перемотка искала бы конец у того, что не кончается.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PointDesktopTheme {
                CompactObject(state = state, item = item, onBack = { })
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    @Test
    fun `текст, прочитанный компьютером, виден на экране самого документа`() {
        show(extractText())

        compose.onNodeWithText(WRITTEN).assertExists()
    }

    /** Заголовок над текстом называет то, что под ним, а не вид самого документа. */
    @Test
    fun `текст документа подписан текстом, а не «PDF» и не «Документ»`() {
        show(extractText())

        compose.onNodeWithText("ТЕКСТ").assertExists()
    }

    private companion object {

        /** Короткая строка: на экране она стоит целиком, и её видно поиском по тексту. */
        const val WRITTEN = "Смета на ремонт кровли"
    }
}
