package com.point.core.flow

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Зеркало `SameActionSameFaceTest` компьютера — для телефона (#1263).
 *
 * У компьютера такой сторож есть с #879, у телефона его не было: ключ `crop` действия «Взять
 * фрагмент» не попал в общую таблицу и молча уходил в `else` — дежурная молния и серый тон
 * «неизвестно что». Человек только что показал область ради этого действия и видел его самым
 * неуверенным в списке.
 *
 * Сторож сверяет не картинки, а ключи: каждый ключ, которым действие телефона зовёт свой знак,
 * обязан быть известен обеим таблицам — и значка, и тона. Пустые ключи исследований (`icon = ""`)
 * знака в списке действий не рисуют, а `app:` — префикс стороннего приложения, у него своя ветка.
 */
class SameActionSameFaceOnPhoneTest {

    private val repo = File("../..")

    private val shared = File(repo, "core/ui/src/shared/kotlin/com/point/core/ui/BubbleIcons.kt").readText()

    /** Ключи одной таблицы: тело `when` от объявления функции до следующей. */
    private fun keysOf(from: String, until: String): Set<String> {
        val body = shared.substringAfter(from).substringBefore(until)
        return Regex(""""([a-z0-9:_-]+)" ->""").findAll(body).map { it.groupValues[1] }.toSet()
    }

    private fun keysKnownToIcons(): Set<String> = keysOf("fun bubbleIcon(", "fun bubbleColor(")

    private fun keysKnownToColors(): Set<String> = keysOf("fun bubbleColor(", "fun kindIcon(")

    /** Действия телефона живут в executors, app и core/flow — там же лежат их ключи знака. */
    private fun keysUsedByPhone(): Map<String, String> =
        listOf("executors/src/main", "app/src/main", "core/flow/src/main")
            .map { File(repo, it) }
            .flatMap { dir ->
                dir.walkTopDown()
                    .filter { it.extension == "kt" }
                    .flatMap { file ->
                        Regex("""override val icon = "([^"]*)"""").findAll(file.readText())
                            .map { it.groupValues[1] to file.name }
                    }
                    .toList()
            }
            .filter { (key, _) -> key.isNotBlank() && !key.startsWith("app:") }
            .toMap()

    @Test
    fun `каждый значок телефона известен общей таблице`() {
        val known = keysKnownToIcons()

        val strangers = keysUsedByPhone().filterKeys { it !in known }

        assertTrue("значок нарисуется заглушкой-молнией: $strangers", strangers.isEmpty())
    }

    @Test
    fun `у каждого значка телефона есть свой тон`() {
        val known = keysKnownToColors()

        val strangers = keysUsedByPhone().filterKeys { it !in known }

        assertTrue("действие получит серый тон «неизвестно что»: $strangers", strangers.isEmpty())
    }

    /** Знак и тон — одна таблица на два устройства: расхождение наборов и есть источник дефекта. */
    @Test
    fun `таблицы значка и тона знают одни и те же ключи`() {
        val onlyIcon = keysKnownToIcons() - keysKnownToColors()
        val onlyColor = keysKnownToColors() - keysKnownToIcons()

        assertTrue("ключ есть у значка, но не у тона: $onlyIcon", onlyIcon.isEmpty())
        assertTrue("ключ есть у тона, но не у значка: $onlyColor", onlyColor.isEmpty())
    }

    /** «Взять фрагмент» — то действие, ради которого человек только что показал область. */
    @Test
    fun `у взятия фрагмента есть свой знак`() {
        val used = keysUsedByPhone().keys

        assertTrue("взятие фрагмента больше не зовёт свой знак", "crop" in used)
        assertTrue("знака нет в таблице", "crop" in keysKnownToIcons())
        assertTrue("тона нет в таблице", "crop" in keysKnownToColors())
    }
}
