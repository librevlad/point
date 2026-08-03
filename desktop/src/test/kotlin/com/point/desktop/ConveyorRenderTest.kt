package com.point.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.point.core.flow.PcRemoteAction
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.desktop.ui.Conveyor
import com.point.desktop.ui.PointColors
import com.point.desktop.ui.PointDesktopTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Конвейер рисуется в картинку без запуска приложения (#285).
 *
 * Зачем так: дать десктопу объект «снаружи» нечем — синтетический клик и хоткей до Compose
 * Desktop не доходят, а перетаскивание файла руками машина повторить не может. Без этого теста
 * экран уезжал бы к человеку непроверенным вовсе.
 *
 * Тест судит не красоту, а то, что композиция вообще собирается и что-то рисует: падение внутри
 * `Conveyor` (несобранный layout, нехватка данных) уронит его здесь, а не в руках у владельца.
 * Снимок кладётся в `build/` — смотреть глазами.
 */
class ConveyorRenderTest {

    private val phoneActions = listOf(
        PcRemoteAction("call", "Позвонить", kinds = setOf("TEXT")),
        PcRemoteAction("share", "Поделиться", kinds = emptySet()),
    )

    private fun state() = DesktopState(
        registry = DesktopRegistry(emptySet()),
        resolver = DesktopResolver(emptySet()),
        clipboard = { },
    ).apply { setPhoneCaps(phoneActions) }

    private fun item() = InboxItem(
        PointObject(
            id = "obj",
            mime = "text/plain",
            uri = ScratchRef("/tmp/накладная.txt"),
            state = ObjectState(ObjectKind.TEXT),
            metadata = mapOf(
                "name" to "накладная.txt",
                "entity.track" to "20 4514 9154 9395",
                "entity.amount" to "320 грн",
                "entity.email" to "vlad@example.com",
            ),
        ),
    )

    @Test
    fun `конвейер собирается и рисует объект вместе с действиями телефона`() {
        val scene = ImageComposeScene(width = 1100, height = 720, density = Density(1f)) {
            PointDesktopTheme {
                Box(Modifier.fillMaxSize().background(PointColors.window).padding(24.dp)) {
                    Conveyor(state(), item())
                }
            }
        }

        val image = scene.render()
        scene.close()

        assertTrue("сцена не того размера", image.width == 1100 && image.height == 720)

        // Снимок — для глаз; в репозиторий не попадает, только в build/.
        val out = File("build/render/conveyor.png").apply { parentFile.mkdirs() }
        out.writeBytes(
            image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)?.bytes
                ?: error("не удалось закодировать снимок"),
        )
        assertTrue("снимок не записан", out.length() > 0)
    }
}
