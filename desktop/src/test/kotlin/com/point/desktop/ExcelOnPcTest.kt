package com.point.desktop

import com.point.core.flow.AiReadiness
import com.point.core.flow.ClipboardPayload
import com.point.core.flow.ExcelCapability
import com.point.core.flow.PcRemoteAction
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «В Excel» исполняется на компьютере (#1369, решение владельца 01.09.2026: «файловые
 * действия работают там, где человек»). Живой прогон владельца: PDF на ПК → скриншот
 * файлом → «В Excel на телефоне» → ручной форграунд → минута ожидания — шесть шагов
 * ради одного файла.
 */
class ExcelOnPcTest {

    private val keySet = AiReadiness { true }

    private fun excelRealizer() = PcExcelRealizer(object : Realizer {
        override val capabilityId = ExcelCapability.ID
        override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
            ActionResult.Failure("тестовый исполнитель не выполняет", recoverable = true)
    })

    private fun registryWithExcel(): Pair<DesktopRegistry, DesktopResolver> {
        val resolver = DesktopResolver(setOf(excelRealizer()))
        val registry = DesktopRegistry(
            desktopCapabilities { true } + ExcelCapability(keySet),
            runnable = resolver::canRun,
        )
        return registry to resolver
    }

    private fun item(kind: ObjectKind, mime: String) =
        InboxItem(PointObject("id", mime, ScratchRef("/tmp/объект"), ObjectState(kind)))

    @Test
    fun `снимку на компьютере предлагается В Excel`() {
        val (registry, _) = registryWithExcel()

        val doors = registry.bubblesFor(ObjectState(ObjectKind.IMAGE)).map { it.title }

        assertTrue("среди дверей: $doors", doors.any { it.startsWith("В Excel") })
    }

    @Test
    fun `набор страниц на компьютере В Excel не получает — его детей здесь читать нечем`() {
        val (registry, _) = registryWithExcel()

        val doors = registry.bubblesFor(ObjectState(ObjectKind.COLLECTION)).map { it.title }

        assertFalse("среди дверей: $doors", doors.any { it.startsWith("В Excel") })
    }

    /**
     * Частичное умение компьютера не съедает целое умение соседа (#1369).
     *
     * Телефонная дверь прячется только там, где компьютер предложит своё той же самой вещи:
     * «В Excel» здесь читает одиночную страницу, но не набор — и для набора дверь телефона
     * обязана остаться. Прежний фильтр «id есть в реестре» скрывал её и там.
     */
    @Test
    fun `телефонное В Excel для набора остаётся, для снимка сливается со здешним`() {
        val (registry, resolver) = registryWithExcel()
        val s = DesktopState(
            registry = registry,
            resolver = resolver,
            clipboard = { },
            phoneRunsRequests = true,
        )
        s.setPhoneCaps(
            listOf(
                PcRemoteAction(
                    "excel", "В Excel",
                    kinds = setOf("IMAGE", "PDF", "TEXT", "COLLECTION"),
                ),
            ),
            persist = false,
        )

        assertEquals(
            "для набора страниц телефонная дверь остаётся",
            listOf("excel"),
            s.phoneActionsFor(item(ObjectKind.COLLECTION, "application/x-point-collection")).map { it.id },
        )
        assertEquals(
            "для снимка компьютер предлагает своё — дубль телефона скрыт",
            emptyList<String>(),
            s.phoneActionsFor(item(ObjectKind.IMAGE, "image/png")).map { it.id },
        )
    }

    // ---- дверь буфера (#1370) ----

    @Test
    fun `область экрана в буфере — объект, а не «В буфере пусто»`() {
        assertTrue(worthTaking(ClipboardPayload("image/png", "clipboard.png", byteArrayOf(1, 2, 3))))
    }

    @Test
    fun `файл из буфера — объект`() {
        assertTrue(worthTaking(ClipboardPayload("application/pdf", "скан.pdf", byteArrayOf(1))))
    }

    @Test
    fun `пустой буфер и пустой текст объектом не становятся`() {
        assertFalse(worthTaking(null))
        assertFalse(worthTaking(ClipboardPayload.ofText("   ")))
    }

    @Test
    fun `текст из буфера остаётся объектом`() {
        assertTrue(worthTaking(ClipboardPayload.ofText("накладная 1187")))
    }

    /**
     * Решение #701 остаётся в силе (#1369 его не отменяет): действия, чей результат —
     * знание, на компьютере не появляются. Файловое исключение — только «В Excel».
     */
    @Test
    fun `Понять и Перевести на компьютере не появляются`() {
        val (registry, _) = registryWithExcel()

        val everywhere = listOf(ObjectKind.IMAGE, ObjectKind.PDF, ObjectKind.TEXT)
            .flatMap { registry.bubblesFor(ObjectState(it)) }
            .map { it.title }

        assertFalse("среди дверей: $everywhere", everywhere.any { it.startsWith("Понять") })
        assertFalse("среди дверей: $everywhere", everywhere.any { it.startsWith("Перевести") })
        assertFalse("среди дверей: $everywhere", everywhere.any { it.startsWith("Спросить") })
    }

    @Test
    fun `объявление компьютера телефону несёт excel — телефон сольёт его со своим`() {
        val (registry, _) = registryWithExcel()

        val advertised = phoneFacingActions(registry.all()).map { it.id }

        assertTrue("объявлено: $advertised", "excel" in advertised)
    }
}
