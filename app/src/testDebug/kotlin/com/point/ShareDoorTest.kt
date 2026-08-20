package com.point

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowToast
import java.io.File

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class ShareDoorTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun shared(): Intent =
        Intent(context, ShareActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")

    @Test fun `расшаренный текст доезжает до экрана объекта`() {
        val intent = shared().putExtra(Intent.EXTRA_TEXT, "Пришлите договор до пятницы")

        ActivityScenario.launch<ShareActivity>(intent).use {
            compose.waitUntilAtLeastOneExists(hasText("Пришлите договор до пятницы"), TIMEOUT_MS)
        }
    }

    @Test fun `на экране расшаренного объекта есть его действия`() {
        val intent = shared().putExtra(Intent.EXTRA_TEXT, "Пришлите договор до пятницы")

        ActivityScenario.launch<ShareActivity>(intent).use {
            compose.waitUntilAtLeastOneExists(hasText("Поделиться"), TIMEOUT_MS)
        }
    }

    @Test fun `расшаренный файл доезжает до экрана объекта своим именем`() {
        val file = File(context.cacheDir, "nakladnaya.txt").apply { writeText("№ 4512 · до пятницы") }
        val intent = shared().putExtra(Intent.EXTRA_STREAM, Uri.parse(file.toURI().toString()))

        ActivityScenario.launch<ShareActivity>(intent).use {
            compose.waitUntilAtLeastOneExists(hasText("nakladnaya.txt"), TIMEOUT_MS)
        }
    }

    @Test fun `Share без содержимого объясняет отказ словами`() {
        val intent = Intent(context, ShareActivity::class.java).setAction(Intent.ACTION_SEND).setType("image/*")

        ActivityScenario.launch<ShareActivity>(intent).use {
            compose.waitUntilAtLeastOneExists(hasText("Point не понял", substring = true), TIMEOUT_MS)
        }
    }

    // Пустой текстовый вход отвечает словом, без объекта и экрана (#1096,
    // решение владельца 20.08.2026) — тем же, что и пустое выделение в меню текста.

    @Test fun `Share текста без текста отвечает словом — объекта нет, экран не поднимается`() {
        val activity = Robolectric.buildActivity(ShareActivity::class.java, shared()).create().get()

        assertEquals("Выделение пустое — выделите текст", ShadowToast.getTextOfLatestToast())
        assertTrue("экран не должен подниматься", activity.isFinishing)
        assertTrue("объект из пустоты: появились байты", sharedTextLeftovers().isEmpty())
    }

    @Test fun `Share пробелов не рождает объект из пробелов`() {
        val intent = shared().putExtra(Intent.EXTRA_TEXT, "   ")
        val activity = Robolectric.buildActivity(ShareActivity::class.java, intent).create().get()

        assertEquals("Выделение пустое — выделите текст", ShadowToast.getTextOfLatestToast())
        assertTrue("экран не должен подниматься", activity.isFinishing)
        assertTrue("объект из пробелов: появились байты", sharedTextLeftovers().isEmpty())
    }

    private fun sharedTextLeftovers(): List<File> =
        File(context.cacheDir, "shared-text").walkTopDown().filter(File::isFile).toList()

    @Test fun `второй объект в живое приложение открывает второй объект`() {
        val first = shared().putExtra(Intent.EXTRA_TEXT, "Первый объект")
        val controller = Robolectric.buildActivity(ShareActivity::class.java, first).setup()

        compose.waitUntilAtLeastOneExists(hasText("Первый объект"), TIMEOUT_MS)
        controller.newIntent(shared().putExtra(Intent.EXTRA_TEXT, "Второй объект"))

        compose.waitUntilAtLeastOneExists(hasText("Второй объект"), TIMEOUT_MS)
        compose.onNodeWithText("Первый объект").assertDoesNotExist()
        controller.destroy()
    }
}

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class ScratchWipedTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun leftovers(): List<File> =
        listOf(File(context.filesDir, "scratch"), File(context.cacheDir, "shared-text"))
            .flatMap { it.walkTopDown().filter(File::isFile) }

    @Test fun `конец флоу уносит байты объекта с диска`() {
        val intent = Intent(context, ShareActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Пароль от почты — 4512")

        ActivityScenario.launch<ShareActivity>(intent).use { scenario ->
            compose.waitUntilAtLeastOneExists(hasText("Пароль от почты", substring = true), TIMEOUT_MS)

            assertTrue("объект открылся, но на диске его нет — проверять нечего", leftovers().isNotEmpty())
            scenario.onActivity { it.finish() }
        }

        // Очистка уходит на фоновый поток уже закрытого экрана: compose-часы здесь мертвы,
        // поэтому ждём сами — прокачивая главный looper до дедлайна, без гонки с диском.
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (leftovers().isNotEmpty() && System.currentTimeMillis() < deadline) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(20)
        }

        assertTrue(
            "флоу кончился, а байты объекта остались на диске: ${leftovers().map(File::getName)}",
            leftovers().isEmpty(),
        )
    }
}

private const val TIMEOUT_MS = 10_000L
