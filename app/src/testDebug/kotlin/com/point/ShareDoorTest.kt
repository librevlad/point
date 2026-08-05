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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Главный путь Point целиком: человек «поделился» — и увидел свой объект с действиями (#239).
 *
 * До этих тестов разбор входящего intent'а не проверял никто. Чистая функция [incomingOf] под
 * тестом была, а сама дверь — нет: между «разобрали» и «показали» лежит вся связка (достать
 * `EXTRA_STREAM`, спросить систему о типе, скопировать байты, поднять экран), и любое её звено
 * можно было выломать при зелёном CI. Ломается оно один раз — зато у всех сразу: Point молча
 * ничего не делает в ответ на «Поделиться», и первым это замечает тот, кто поставил сборку.
 *
 * Проверка идёт на настоящей [ShareActivity] с настоящими intent'ами — подменять здесь нечего.
 */
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

    /**
     * Объект приехал — и с ним приехало то, ради чего человек шёл: что с этим можно сделать.
     *
     * «Поделиться» берётся из настоящего реестра, а не из списка в тесте: способность принимает
     * любой объект, лежащий файлом, и потому её отсутствие означает не «эта кнопка пропала», а
     * «действий не пришло вовсе».
     */
    @Test fun `на экране расшаренного объекта есть его действия`() {
        val intent = shared().putExtra(Intent.EXTRA_TEXT, "Пришлите договор до пятницы")

        ActivityScenario.launch<ShareActivity>(intent).use {
            compose.waitUntilAtLeastOneExists(hasText("Поделиться"), TIMEOUT_MS)
        }
    }

    /** Файл — тот же путь, но объект приезжает ссылкой в `EXTRA_STREAM`, и имя у него от файла. */
    @Test fun `расшаренный файл доезжает до экрана объекта своим именем`() {
        val file = File(context.cacheDir, "nakladnaya.txt").apply { writeText("№ 4512 · до пятницы") }
        val intent = shared().putExtra(Intent.EXTRA_STREAM, Uri.parse(file.toURI().toString()))

        ActivityScenario.launch<ShareActivity>(intent).use {
            compose.waitUntilAtLeastOneExists(hasText("nakladnaya.txt"), TIMEOUT_MS)
        }
    }

    /**
     * Разобрать не вышло — человек обязан увидеть слова.
     *
     * Раньше в этой ветке не происходило ровно ничего: чёрный экран без единой строки и без
     * выхода. Молчание в ответ на действие — худший из отказов: непонятно даже, дошло ли оно.
     */
    @Test fun `Share без содержимого объясняет отказ словами`() {
        val intent = shared() // ни текста, ни ссылки на файл — разбирать нечего

        ActivityScenario.launch<ShareActivity>(intent).use {
            compose.waitUntilAtLeastOneExists(hasText("Point не понял", substring = true), TIMEOUT_MS)
        }
    }

    /**
     * Второй объект в живое приложение (`onNewIntent`).
     *
     * Дверь объявлена `singleTop`: пока Point открыт, второй «Поделиться» не создаёт активити
     * заново, а доставляется в живую. Рядом с кодом про это написано «без этого intent теряется
     * молча» — и до сих пор эта строчка была единственной защитой.
     */
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

/**
 * Главный инвариант приватности — на диске, а не на счётчике вызовов (#239).
 *
 * «По окончании флоу рабочая копия стирается» проверялось тем, что модель позвала `clear()` на
 * подделке. Обещано при этом другое — что байтов не остаётся на диске.
 *
 * Разница между этими двумя утверждениями оказалась не теоретической. Первый же прогон этого теста
 * нашёл, что на самом частом конце флоу — человек закрыл Point — уборка не выполнялась ВООБЩЕ:
 * `endFlow` запускал её в области, которую система гасит до `onDestroy` (см. `NonCancellable` в
 * [FlowViewModel.endFlow]). Счётчик вызовов этого увидеть не мог по построению: `clear()` звали
 * честно, просто звонок никуда не доходил.
 *
 * Через эту дверь идут пароли, переписка и реквизиты, поэтому здесь смотрят в настоящие папки
 * настоящего приложения.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class ScratchWipedTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Рабочая копия объекта и временная копия расшаренного текста — обе папки Point. */
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
            // Если байтов не было и до конца флоу, то и «их не стало» ничего не значит.
            assertTrue("объект открылся, но на диске его нет — проверять нечего", leftovers().isNotEmpty())
            scenario.onActivity { it.finish() } // человек ушёл — флоу кончился
        }

        // Уборка идёт своей задачей, поэтому её ждут, а не «должно было успеть». Приговор выносит
        // не ожидание, а диск ниже: истёкшее время само по себе ничего не утверждает.
        runCatching { compose.waitUntil(TIMEOUT_MS) { leftovers().isEmpty() } }

        assertTrue(
            "флоу кончился, а байты объекта остались на диске: ${leftovers().map(File::getName)}",
            leftovers().isEmpty(),
        )
    }
}

/** Приём идёт по-настоящему и в фоне — ждём словами экрана, а не «должно было успеть». */
private const val TIMEOUT_MS = 10_000L
