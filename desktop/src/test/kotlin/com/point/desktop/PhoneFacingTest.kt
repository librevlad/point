package com.point.desktop

import com.point.core.flow.RelayRpc
import com.point.core.flow.advertisedActions
import com.point.core.flow.decodePcCaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * #628, решение владельца — «одна способность — одна кнопка».
 *
 * Компьютер объявляет телефону свои умения. Имя, собранное приклеиванием «на ПК» к чужому
 * имени, ставило в список вторую строку на то же самое умение. Теперь имя либо совпадает с
 * телефонным (одна способность, компьютер — второй исполнитель), либо названо целиком — и
 * место в нём стоит только там, где от места зависит внешний результат (ADR-0001 §7).
 *
 * Место зовётся одним словом — «компьютер» (#1094, решение владельца «везде компьютер»):
 * две формы одного места («на ПК» / «на компьютере») в одном списке читались как два
 * разных места. Сторож, пропускавший форму «ПК», пропустил бы и её возвращение.
 */
class PhoneFacingTest {

    @get:Rule val tmp = TemporaryFolder()

    /**
     * То самое объявление, что компьютер шлёт телефону, а не собранное заново рядом: Main
     * зовёт `phoneFacingActions(registry.all())`, а в реестре окна лежит ровно этот набор.
     */
    private val advertised = phoneFacingActions(desktopCapabilities())

    @Test fun `умение, общее с телефоном, едет своим именем — без приписки об устройстве`() {

        val renamed = advertisedActions(desktopCapabilities()).zip(advertised)
            .filterNot { (own, _) -> own.id.startsWith("pc-") }
            .filter { (own, sent) -> own.label != sent.label }

        assertTrue(
            "общее умение переименовано по дороге к телефону- ${renamed.map { (own, sent) -> own.label + " → " + sent.label }}",
            renamed.isEmpty(),
        )
    }

    @Test fun `расшифровка едет телефону той же способностью, а не второй кнопкой`() {

        val ids = advertised.map { it.id }

        assertTrue("у компьютера снова своё умение расшифровки- $ids", "transcribe" in ids)
        assertTrue("осталось имя, под которым телефон её не узнает- $ids", "pc-transcribe" !in ids)
    }

    @Test fun `у каждого умения, привязанного к компьютеру, место названо словом «компьютер»`() {

        val nameless = advertised
            .filter { it.id.startsWith("pc-") }
            .filterNot { it.label.contains(PLACE) }

        assertTrue(
            "телефон увидит имя, не сказавшее, где будет результат- ${nameless.map { it.label }}",
            nameless.isEmpty(),
        )
    }

    @Test fun `форма «ПК» из списка ушла и не возвращается ни одним именем`() {

        val twoForms = advertised.filter { it.label.contains(SHORT_FORM) }

        assertTrue(
            "место названо второй формой, и список снова читается как два места- ${twoForms.map { it.label }}",
            twoForms.isEmpty(),
        )
    }

    /**
     * Телефон в своих тестах читает объявление компьютера из файла (`:executors`,
     * OneButtonPerCapabilityTest), а не переписывает его от руки (#1094): рукописная копия
     * синхронизировалась вручную и к боевому набору отношения не имела.
     *
     * Файл — не сам провод, а копия ответа в дереве, и живёт она ровно этим сторожем: здесь
     * телефон спрашивает «что ты умеешь» тем же вызовом, что и по сети (`RelayRequests`,
     * RelayRpc.CAPS), и с телом ответа сверяется разобранное объявление — то самое, что
     * телефон получает по сети и что его тесты берут из копии (перевод строки в файле
     * остаётся делом git). Изменится ответ, а копия нет — падает этот тест и печатает текст,
     * которым копию переписать.
     */
    @Test fun `копия ответа «что ты умеешь» не отстаёт от того, что компьютер отвечает`() {

        val onWire = String(capsReply(), Charsets.UTF_8)
        val kept = javaClass.getResource("/$SNAPSHOT")?.readText()
            ?: throw AssertionError(
                "копии $SNAPSHOT_IN_TREE нет — телефонным тестам нечего читать; заведите её этим текстом:\n$onWire",
            )

        assertEquals(
            "ответ компьютера изменился — перепишите $SNAPSHOT_IN_TREE этим текстом:\n$onWire",
            decodePcCaps(onWire),
            decodePcCaps(kept),
        )
    }

    /** Ответ компьютера телефону на вопрос «что ты умеешь» — тем же путём, что и по сети. */
    private fun capsReply(): ByteArray = RelayRequests(
        remoteActions = { advertised },
        outbox = Outbox(tmp.newFolder()),
        onPhoneCaps = { },
        clipboardGet = { null },
        clipboardSet = { },
        onObject = { _, _, _, _, _ -> null },
    ).answer(RelayRpc.CAPS, emptyMap(), ByteArray(0))!!.body

    private companion object {

        /** Единственное слово для места результата в именах умений компьютера. */
        const val PLACE = "компьютер"

        /** Вторая форма того же места — из списка ушла (#1094). */
        const val SHORT_FORM = "ПК"

        /** Копия ответа на classpath: её же сборка `:executors` кладёт тестам телефона. */
        const val SNAPSHOT = "phone-facing-actions.txt"

        /** Где эта копия лежит в дереве — чтобы сообщение сказало, что именно переписать. */
        const val SNAPSHOT_IN_TREE = "desktop/src/test/resources/$SNAPSHOT"
    }
}
