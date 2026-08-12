package com.point.desktop

import com.point.core.flow.Realizer
import com.point.core.flow.Resolver
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Аудит 2026-08-09, блоки 1.4-1.5: ПК не исследовал прибывшее и не смотрел в байты.
 * Прибывший объект сразу продолжает цикл понимания (Конституция §9); голова файла
 * спрашивается всегда (прецедент P1).
 */
class ArrivalUnderstandsItselfTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `файл без расширения опознаётся по байтам, а не мёртвым UNKNOWN`() {
        val inbox = Inbox(temp.newFolder("in"))
        val naked = temp.newFile("отчёт").apply {
            writeBytes("%PDF-1.7\n".toByteArray(Charsets.US_ASCII) + ByteArray(64))
        }

        val item = inbox.addFile(naked.absolutePath)

        assertEquals(ObjectKind.PDF, item.obj.state.kind)
    }

    @Test
    fun `прибывший текст исследует себя сам — телефон в фактах без единого клика`() {
        val st = DesktopState(
            // Автозапуск теперь берёт вопросы из реестра способностей (владелец,
            // 10.08.2026: «единообразно, а не по одному жёстко зашитому id») —
            // способность обязана быть объявлена, а не только выполнима.
            DesktopRegistry(setOf(PcEntitiesCapability())),
            DesktopResolver(setOf(PcEntitiesRealizer(com.point.core.flow.RegexEntityExtractor()))),
            clipboard = { },
        )
        val file = temp.newFile("прибыло.txt").apply { writeText("Позвони мне: +380671234567") }
        val item = InboxItem(
            PointObject("t", "text/plain", ScratchRef(file.absolutePath), ObjectState(ObjectKind.TEXT)),
        )

        st.onReceived(item, ObjectSource.PHONE_RELAY)

        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline &&
            st.items.value.first().obj.metadata["entity.phone"] == null
        ) {
            Thread.sleep(20)
        }
        val meta = st.items.value.first().obj.metadata
        assertEquals("+380671234567", meta["entity.phone"])
        assertEquals("found", meta["investigated.entities"])
    }

    @Test
    fun `уже исследованное телефоном не переспрашивается`() {
        var asked = 0
        val counting = object : Realizer {
            override val capabilityId = com.point.core.flow.KnownCapabilities.ENTITIES
            override suspend fun perform(input: PointObject, amendment: String?): com.point.core.model.ActionResult {
                asked++
                return com.point.core.model.ActionResult.Done("не должно случиться")
            }
        }
        val st = DesktopState(
            DesktopRegistry(setOf(PcEntitiesCapability())),
            object : Resolver {
                override fun realizerFor(capabilityId: CapabilityId) = counting
            },
            clipboard = { },
        )
        val file = temp.newFile("готовое.txt").apply { writeText("текст") }
        val item = InboxItem(
            PointObject(
                "t2", "text/plain", ScratchRef(file.absolutePath), ObjectState(ObjectKind.TEXT),
                metadata = mapOf("investigated.entities" to "found", "entity.phone" to "+380111111111"),
            ),
        )

        st.onReceived(item, ObjectSource.PHONE_RELAY)
        Thread.sleep(300)

        assertTrue("отвеченный вопрос не переспрашивается", asked == 0)
    }
}
