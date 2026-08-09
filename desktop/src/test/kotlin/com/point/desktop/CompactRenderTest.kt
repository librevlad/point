package com.point.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.point.core.flow.PcRemoteAction
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.desktop.ui.CompactList
import com.point.desktop.ui.CompactObject
import com.point.desktop.ui.PeekCard
import com.point.desktop.ui.PointColors
import com.point.desktop.ui.PointDesktopTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Компакт-окно (решение владельца 2026-08-09): сцена объекта, список и peek-плашка
 * собираются в размере компакта. Снимки — build/render/compact-*.png.
 */
class CompactRenderTest {

    private val phoneActions = listOf(
        PcRemoteAction("call", "Позвонить", kinds = setOf("TEXT"), priority = 10),
        PcRemoteAction("share", "Поделиться", priority = 80),
    )

    private fun state(journal: List<JournalEntry> = emptyList()) = DesktopState(
        registry = DesktopRegistry(emptySet()),
        resolver = DesktopResolver(emptySet()),
        clipboard = { },
        journalStore = object : JournalStore {
            override fun load() = journal
            override fun save(entries: List<JournalEntry>) = Unit
        },
    ).apply { setPhoneCaps(phoneActions) }

    private fun rich(): InboxItem {
        val file = File.createTempFile("компакт-", ".txt").apply {
            writeText("Оплатите счёт 4411 до 26.04.2026.\nТел: +380671234567")
            deleteOnExit()
        }
        return InboxItem(
            PointObject(
                id = "rich",
                mime = "text/plain",
                uri = ScratchRef(file.absolutePath),
                state = ObjectState(ObjectKind.TEXT),
                metadata = mapOf(
                    "name" to "Счёт 4411",
                    "semantic.summary" to "Оплата счёта до срока",
                    "entity.phone" to "+380671234567",
                    "entity.phone.more" to "+380509876543",
                    "entity.amount" to "500",
                    "entity.amount.alt" to "0.00",
                    "investigated.qr-content" to "not_found",
                ),
            ),
        )
    }

    private fun snap(name: String, width: Int, height: Int, content: @androidx.compose.runtime.Composable () -> Unit) {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            PointDesktopTheme {
                Box(Modifier.fillMaxSize().background(PointColors.window)) { content() }
            }
        }
        val image = scene.render()
        scene.close()
        val out = File("build/render/$name.png").apply { parentFile.mkdirs() }
        out.writeBytes(
            image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)?.bytes
                ?: error("не удалось закодировать снимок"),
        )
        assertTrue("снимок не записан", out.length() > 0)
    }

    @Test
    fun `сцена объекта помещается в компакт — превью, знание, действия, путь`() {
        val st = state(
            listOf(
                JournalEntry(
                    path = rich().obj.uri.value,
                    name = "Счёт 4411",
                    kind = ObjectKind.TEXT.name,
                    mime = "text/plain",
                    source = ObjectSource.PHONE_RELAY,
                    at = 1L,
                ),
            ),
        )
        val item = rich()
        st.onReceived(item)

        snap("compact-object", COMPACT_WIDTH, COMPACT_HEIGHT) {
            CompactObject(state = st, item = item, onBack = {})
        }
    }

    @Test
    fun `список компакта — сейчас, было раньше, двери входа`() {
        val st = state(
            listOf(
                JournalEntry(
                    path = "/tmp/старое.pdf",
                    name = "счёт-март.pdf",
                    kind = ObjectKind.PDF.name,
                    mime = "application/pdf",
                    source = ObjectSource.PHONE_RELAY,
                    at = 1L,
                ),
            ),
        )
        st.onReceived(rich())

        snap("compact-list", COMPACT_WIDTH, COMPACT_HEIGHT) {
            CompactList(
                state = st,
                items = st.items.value,
                onOpen = {},
                onTakeClipboard = {},
                onGrabScreen = {},
                onSettings = {},
                onHide = {},
            )
        }
    }

    @Test
    fun `peek-плашка собирается в своём размере`() {
        snap("compact-peek", PEEK_WIDTH, PEEK_HEIGHT) {
            PeekCard(item = rich(), onOpen = {}, onDismiss = {})
        }
    }
}
