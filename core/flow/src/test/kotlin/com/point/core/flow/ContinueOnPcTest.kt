package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #147 Continue on PC: the pairing QR and the metadata codec are a PROTOCOL shared by
 * the phone (sender) and the desktop (receiver) — pure Kotlin, tested here once.
 */
class ContinueOnPcTest {

    @Test
    fun `pairing roundtrip via the QR payload`() {
        val info = PcPairing(host = "192.168.1.42", port = 8391, token = "abc123XYZ")
        assertEquals(info, parsePcPairing(info.qrPayload()))
    }

    @Test
    fun `garbage and foreign QR payloads are rejected`() {
        assertNull(parsePcPairing("https://example.com"))
        assertNull(parsePcPairing("point-pc://noport/tok"))
        assertNull(parsePcPairing(""))
    }

    @Test
    fun `metadata codec roundtrips understanding`() {
        val meta = mapOf(
            "entity.phone" to "+380671234567",
            "name" to "receipt.jpg",
            "multi line" to "clean value",
        )
        assertEquals(meta, decodePcMeta(encodePcMeta(meta)))
    }

    @Test
    fun `metadata codec drops line breaks inside values`() {
        val decoded = decodePcMeta(encodePcMeta(mapOf("k" to "a\nb")))
        assertEquals("a b", decoded["k"])
    }

    // --- Remote PC capabilities (#80): the PC advertises its actions over the pairing ---

    @Test
    fun `caps codec roundtrips id and label in order`() {
        val caps = listOf(
            PcRemoteAction("pc-open", "Открыть на компьютере"),
            PcRemoteAction("pc-copy", "В буфер компьютера"),
        )
        assertEquals(caps, decodePcCaps(encodePcCaps(caps)))
    }

    @Test
    fun `caps codec carries kind gates and keeps the bare format for gate-free actions`() {
        val caps = listOf(
            PcRemoteAction("pc-open", "Открыть на компьютере"),
            PcRemoteAction("pc-download", "Скачать видео на ПК", kinds = setOf("URL")),
        )
        val decoded = decodePcCaps(encodePcCaps(caps))
        assertEquals(caps, decoded)
        assertEquals(setOf("URL"), decoded[1].kinds)
        assertTrue(decoded[0].kinds.isEmpty()) // empty = any kind
    }

    @Test
    fun `caps codec skips garbage lines and blank ids`() {
        val decoded = decodePcCaps("pc-open=Открыть\nмусор без разделителя\n=безид\npc-copy=Копировать")
        assertEquals(listOf(PcRemoteAction("pc-open", "Открыть"), PcRemoteAction("pc-copy", "Копировать")), decoded)
    }

    // --- Недоступное с причиной (#316): «нет принтера» вместо молчания, в обе стороны ---

    /**
     * Декодер образца #80/#291 — дословно тот, что живёт на уже установленных телефонах.
     * Держим копию здесь, потому что совместимость проверяется не намерением, а поведением
     * старого кода на новых байтах.
     */
    private fun legacyDecodePcCaps(encoded: String): List<PcRemoteAction> =
        encoded.lineSequence().mapNotNull { line ->
            val eq = line.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val id = line.substring(0, eq).trim()
            val rest = line.substring(eq + 1)
            val label = rest.substringBefore('\t').trim()
            val kinds = rest.substringAfter('\t', "").split(',')
                .mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
            if (id.isEmpty() || label.isEmpty()) null else PcRemoteAction(id, label, kinds)
        }.toList()

    @Test
    fun `caps codec roundtrips the reason an action is unavailable`() {
        val caps = listOf(
            PcRemoteAction("pc-open", "Открыть на компьютере"),
            PcRemoteAction("pc-print", "Напечатать на ПК", unavailable = "на компьютере нет принтера"),
            PcRemoteAction("pc-download", "Скачать видео на ПК", kinds = setOf("URL"), unavailable = "нет yt-dlp"),
        )
        val decoded = decodePcCaps(encodePcCaps(caps))

        assertEquals(caps, decoded)
        assertNull("доступное остаётся доступным", decoded[0].unavailable)
        assertEquals("на компьютере нет принтера", decoded[1].unavailable)
        assertEquals(setOf("URL"), decoded[2].kinds) // гейт видов не теряется рядом с причиной
    }

    /** Старый телефон встречает незнакомую форму — молча её роняет и работает как раньше. */
    @Test
    fun `an old phone ignores unavailable actions instead of showing a dead button`() {
        val encoded = encodePcCaps(
            listOf(
                PcRemoteAction("pc-open", "Открыть на компьютере"),
                PcRemoteAction("pc-print", "Напечатать на ПК", unavailable = "на компьютере нет принтера"),
                PcRemoteAction("pc-download", "Скачать видео на ПК", kinds = setOf("URL"), unavailable = "нет yt-dlp"),
            ),
        )

        val old = legacyDecodePcCaps(encoded)

        assertEquals(listOf("pc-open"), old.map { it.id })
        assertEquals(listOf(PcRemoteAction("pc-open", "Открыть на компьютере")), old)
    }

    /** Старый ПК признака не шлёт — новый телефон обязан видеть ровно прежнюю картину. */
    @Test
    fun `an old PC advertisement stays fully available for a new phone`() {
        val fromOldPc = "pc-open=Открыть на компьютере\npc-download=Скачать видео на ПК\tURL"

        val decoded = decodePcCaps(fromOldPc)

        assertEquals(
            listOf(
                PcRemoteAction("pc-open", "Открыть на компьютере"),
                PcRemoteAction("pc-download", "Скачать видео на ПК", kinds = setOf("URL")),
            ),
            decoded,
        )
        assertTrue(decoded.all { it.unavailable == null })
    }

    /** Доступные строки кодируются байт-в-байт как раньше — старый ПК читает нас без сюрпризов. */
    @Test
    fun `available actions keep the byte-identical old wire format`() {
        assertEquals(
            "pc-open=Открыть на компьютере\npc-download=Скачать видео на ПК\tURL",
            encodePcCaps(
                listOf(
                    PcRemoteAction("pc-open", "Открыть на компьютере"),
                    PcRemoteAction("pc-download", "Скачать видео на ПК", kinds = setOf("URL")),
                ),
            ),
        )
    }

    /** Причина не названа — действие всё равно остаётся недоступным. «Не смог объяснить» не
     *  должно молча превращаться в «можно нажать» (тот же страх, что и в #316 целиком). */
    @Test
    fun `an unavailable action without a reason never becomes tappable`() {
        val decoded = decodePcCaps(encodePcCaps(listOf(PcRemoteAction("pc-print", "Напечатать на ПК", unavailable = ""))))

        assertEquals(1, decoded.size)
        assertEquals("", decoded[0].unavailable)
    }

    /** Форма может дорасти полями — незнакомые мы игнорируем, а не роняем всю строку. */
    @Test
    fun `unknown extra fields of a future format are ignored`() {
        val fromFuturePc = "pc-open=Открыть\tTEXT\tчто-то новое\tи ещё\n=pc-print=Напечатать\t\tнет принтера\tещё поле"

        val decoded = decodePcCaps(fromFuturePc)

        assertEquals(listOf(PcRemoteAction("pc-open", "Открыть", kinds = setOf("TEXT"))), decoded.take(1))
        assertEquals(PcRemoteAction("pc-print", "Напечатать", unavailable = "нет принтера"), decoded[1])
    }

    // --- Liquid pull (#161): the PC's outbox listing travels as id<TAB>b64(meta) lines ---

    @Test
    fun `outbox codec roundtrips ids and metadata`() {
        val entries = listOf(
            PcOutboxEntry(1, mapOf("name" to "чек.jpg", "mime" to "image/jpeg", "entity.phone" to "+380671234567")),
            PcOutboxEntry(3, mapOf("name" to "заметка.txt", "mime" to "text/plain")),
        )
        assertEquals(entries, decodePcOutbox(encodePcOutbox(entries)))
    }

    @Test
    fun `outbox codec survives garbage and empty input`() {
        assertEquals(emptyList<PcOutboxEntry>(), decodePcOutbox(""))
        assertEquals(emptyList<PcOutboxEntry>(), decodePcOutbox("мусор\nещё мусор"))
        val one = decodePcOutbox("не-число\tAAAA\n" + encodePcOutbox(listOf(PcOutboxEntry(7, mapOf("name" to "a")))))
        assertEquals(listOf(7), one.map { it.id })
    }

    // --- Исход действия едет обратно (#114): «доехало» ≠ «сделано» ---

    @Test
    fun `исход действия переживает дорогу в обе стороны`() {
        val done = PcActionOutcome.Done("В очереди «HP LaserJet» · проверьте принтер")
        assertEquals(done, decodePcReceiveReply(encodePcReceiveReply(done)))

        val failed = PcActionOutcome.Failed("На компьютере сейчас нет принтера по умолчанию")
        assertEquals(failed, decodePcReceiveReply(encodePcReceiveReply(failed)))

        assertEquals(PcActionOutcome.Done(null), decodePcReceiveReply(encodePcReceiveReply(PcActionOutcome.Done())))
    }

    /**
     * Старая сборка ПК отвечает одним «ok» — и это НЕ «готово».
     *
     * Совместимость здесь и есть честность: новый телефон обязан прочитать молчание как молчание,
     * иначе он снова начнёт объявлять напечатанным то, о чём ему никто не говорил.
     */
    @Test
    fun `старый компьютер отвечает «ok» — исход неизвестен, а не «сделано»`() {
        assertNull(decodePcReceiveReply("ok"))
        assertNull(decodePcReceiveReply(""))
        assertNull(decodePcReceiveReply("ok\nчто-то из будущей версии"))
        assertNull(encodePcReceiveReply(null).lineSequence().firstOrNull { it.startsWith(PC_ACTION_LINE) })
        assertEquals("ok", encodePcReceiveReply(null))
    }

    @Test
    fun `перенос строки в причине не ломает ответ`() {
        val decoded = decodePcReceiveReply(encodePcReceiveReply(PcActionOutcome.Failed("нет бумаги\nи тонера")))

        assertEquals(PcActionOutcome.Failed("нет бумаги и тонера"), decoded)
    }

    @Test
    fun `отказ без слов всё равно называется отказом`() {
        assertEquals(
            PcActionOutcome.Failed("причина не названа"),
            decodePcReceiveReply(encodePcReceiveReply(PcActionOutcome.Failed(""))),
        )
    }

    /** Результат реализатора компьютера превращается в исход теми же словами, что он сказал. */
    @Test
    fun `результат работы на компьютере становится исходом без сглаживания`() {
        assertEquals(
            PcActionOutcome.Done("В очереди «HP» · проверьте принтер"),
            pcActionOutcomeOf(com.point.core.model.ActionResult.Done("В очереди «HP» · проверьте принтер")),
        )
        assertEquals(
            PcActionOutcome.Failed("нет принтера"),
            pcActionOutcomeOf(com.point.core.model.ActionResult.Failure("нет принтера", recoverable = true)),
        )
        assertNull("не дождались — значит неизвестно, а не «готово»", pcActionOutcomeOf(null))
    }
}
