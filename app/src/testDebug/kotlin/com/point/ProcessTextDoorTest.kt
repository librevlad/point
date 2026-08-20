package com.point

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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

/**
 * Дверь из системного меню выделения текста (PROCESS_TEXT).
 *
 * Пустое выделение отвечает словом, а не молчанием (#1096, решение владельца 20.08.2026):
 * короткий тост, объект не заводится, экран не поднимается — и предыдущий объект молча
 * не восстанавливается.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class ProcessTextDoorTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun selection(text: CharSequence): Intent =
        Intent(context, ProcessTextActivity::class.java)
            .setAction(Intent.ACTION_PROCESS_TEXT)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, text)

    @Test fun `выделенный текст доезжает до экрана объекта`() {
        ActivityScenario.launch<ProcessTextActivity>(selection("Пришлите договор до пятницы")).use {
            compose.waitUntilAtLeastOneExists(hasText("Пришлите договор до пятницы"), TIMEOUT_MS)
        }
    }

    @Test fun `пустое выделение отвечает словом — объекта нет, экран не поднимается`() {
        val activity = Robolectric.buildActivity(ProcessTextActivity::class.java, selection("   ")).create().get()

        assertEquals("Выделение пустое — выделите текст", ShadowToast.getTextOfLatestToast())
        assertTrue("экран не должен подниматься", activity.isFinishing)
        assertTrue("объект из пустоты: появились байты", sharedTextLeftovers().isEmpty())
    }

    private fun sharedTextLeftovers(): List<File> =
        File(context.cacheDir, "shared-text").walkTopDown().filter(File::isFile).toList()

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
