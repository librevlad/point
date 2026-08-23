package com.point.desktop

import com.point.core.flow.advertisedActions
import com.point.core.flow.encodePcCaps
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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

    /** То самое объявление, что компьютер шлёт телефону (Main), а не собранное заново рядом. */
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
     * Телефон в своих тестах читает объявление компьютера из снимка (`:executors`,
     * OneButtonPerCapabilityTest), а не переписывает его от руки (#1094): рукописная копия
     * синхронизировалась вручную и к боевому набору отношения не имела. Снимок — тот самый
     * провод, которым объявление едет телефону; здесь он сверяется с живым.
     */
    @Test fun `снимок объявления для тестов телефона не отстаёт от живого`() {

        val live = encodePcCaps(advertised)
        val kept = File(SNAPSHOT).takeIf { it.isFile }?.readLines()?.joinToString("\n").orEmpty()

        assertEquals(
            "объявление компьютера изменилось — перепишите $SNAPSHOT этим текстом:\n$live",
            live,
            kept,
        )
    }

    private companion object {

        /** Единственное слово для места результата в именах умений компьютера. */
        const val PLACE = "компьютер"

        /** Вторая форма того же места — из списка ушла (#1094). */
        const val SHORT_FORM = "ПК"

        /** Объявление компьютера по проводу, снятое в файл для тестов телефона. */
        const val SNAPSHOT = "src/test/resources/phone-facing-actions.txt"
    }
}
