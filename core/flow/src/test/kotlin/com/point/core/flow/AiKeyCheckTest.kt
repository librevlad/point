package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Приговор проверки ключа (#465) — то место, где «ошибка» превращается в понятную человеку причину.
 *
 * Судится здесь, а не глазами на телефоне: воспроизвести 402 или кончившуюся квоту руками нельзя,
 * а разница между «ключ не тот» и «квота кончилась» — это разница между «перевставь» и «подожди».
 */
class AiKeyCheckTest {

    private fun refusal(probe: KeyProbe): KeyVerdict.Refused =
        keyVerdict(probe) as KeyVerdict.Refused

    @Test
    fun `ответ модели и есть доказательство — его показывают человеку`() {
        val verdict = keyVerdict(KeyProbe(status = 200, reply = "  Готово  "))
        assertEquals(KeyVerdict.Works("Готово"), verdict)
    }

    @Test
    fun `неверный ключ назван неверным ключом, а не ошибкой`() {
        listOf(401, 403).forEach { code ->
            val verdict = refusal(KeyProbe(status = code, error = "unauthorized"))
            assertTrue("код $code: ${verdict.what}", verdict.what.contains("не подошёл"))
            assertTrue("коду $code нечего посоветовать", verdict.fix.isNotBlank())
        }
    }

    @Test
    fun `кончившаяся квота — это не сломанный ключ, и человек должен видеть разницу`() {
        val quota = refusal(KeyProbe(status = 429))
        val wrong = refusal(KeyProbe(status = 401))
        assertTrue(quota.what.contains("квота"))
        assertTrue("квота не должна читаться как неверный ключ", quota.what.contains("верный"))
        assertFalse("два разных отказа говорят одно и то же", quota.what == wrong.what)
    }

    /**
     * Совет на исчерпанную квоту обещал очередь провайдеров, которой нет (#535).
     *
     * Стояло: «возьмите второй ключ у другого сервиса, Point переключится на него сам». Слот ключа
     * ОДИН — [UserAiConfig] хранит один ключ, один адрес, одну модель, — и переключаться не на что.
     * Человек, упёршийся в квоту, шёл заводить второй аккаунт ради обещанного, возвращался и
     * обнаруживал, что вписать второй ключ некуда: продукт назначил ему работу за себя.
     *
     * Проверка идёт по признаку «обещано переключение», а не по дословному тексту: перефразировать
     * то же обещание другими словами так же неправда, и такая правка обязана уронить тест.
     */
    @Test
    fun `совет на исчерпанной квоте не обещает второго ключа и переключения`() {
        val quota = refusal(KeyProbe(status = 429))
        assertFalse("второго слота под ключ в Point нет: ${quota.fix}", quota.fix.contains("второй"))
        assertFalse("очереди провайдеров нет: ${quota.fix}", quota.fix.contains("переключ"))
        // Осталось то, что человек и правда может сделать: подождать — или сменить сервис здесь же.
        assertTrue("совет без продолжения бесполезен: ${quota.fix}", quota.fix.contains("Подождите"))
        assertTrue("сменить сервис можно только вписав ключ сюда: ${quota.fix}", quota.fix.contains("впишите"))
    }

    /** Ни один совет во всей таблице не обещает, что Point сам пойдёт к другому сервису. */
    @Test
    fun `ни один отказ не обещает автоматической подмены сервиса`() {
        listOf(400, 401, 402, 403, 404, 429, 500, 418).forEach { code ->
            val said = refusal(KeyProbe(status = code)).fix
            assertFalse("код $code обещает переключение: $said", said.contains("переключ"))
            assertFalse("код $code обещает «сам»: $said", said.contains("Point сам"))
        }
    }

    @Test
    fun `просьба об оплате отправляет к бесплатному соседу, а не в кассу`() {
        val verdict = refusal(KeyProbe(status = 402))
        assertTrue(verdict.what.contains("оплат"))
        // Опора на бесплатные квоты — решение проекта: на 402 предлагаем другого, а не покупку.
        assertTrue("на 402 надо звать к другому сервису", verdict.fix.contains("другого сервиса"))
    }

    @Test
    fun `нет связи и отказ сервиса — разные новости`() {
        val offline = refusal(KeyProbe())
        val refused = refusal(KeyProbe(status = 500))
        assertTrue(offline.what.contains("дозвонились"))
        assertTrue(refused.what.contains("не отвечает"))
        assertTrue("отказ сервиса нельзя вешать на ключ человека", refused.fix.contains("не про ваш ключ"))
    }

    @Test
    fun `незнакомая модель и непонятый запрос ведут к выбору сервиса из списка`() {
        assertTrue(refusal(KeyProbe(status = 404)).fix.contains("списке выше"))
        assertTrue(refusal(KeyProbe(status = 400)).fix.contains("списке выше"))
    }

    @Test
    fun `принятый ключ без ответа не выдаётся за работающий`() {
        // 200 с пустым текстом — не успех: следующее действие человека всё равно не сработает.
        val verdict = refusal(KeyProbe(status = 200, reply = "   "))
        assertTrue(verdict.what.contains("ответа не прислали"))
    }

    @Test
    fun `незнакомый код пересказывает сервис, но коротко`() {
        val verdict = refusal(KeyProbe(status = 418, error = "я".repeat(500)))
        assertTrue(verdict.what.contains("418"))
        assertTrue("дамп ответа вместо довода", verdict.fix.length < 220)
    }

    @Test
    fun `ключ вычёркивается из всего, что пришло от сервиса`() {
        val key = "sk-очень-секретный-ключ"
        val said = withoutKey("bad request for key $key at /v1", key)
        assertFalse("секрет уехал бы на экран", said.contains(key))
        assertTrue(said.contains("bad request"))
    }

    @Test
    fun `короткое за секрет не принимается — иначе вычеркнули бы половину текста`() {
        assertEquals("ключ ok", withoutKey("ключ ok", "ok"))
    }

    @Test
    fun `вставка из буфера отличает ключ от скопированного абзаца`() {
        assertTrue(looksLikeApiKey("sk-or-v1-0123456789abcdef0123"))
        assertTrue("пробелы по краям — обычное дело для буфера", looksLikeApiKey("  gsk_0123456789abcdef  "))
        assertFalse(looksLikeApiKey("короткий"))
        assertFalse(looksLikeApiKey("это скопированное предложение, а не ключ"))
        assertFalse(looksLikeApiKey(null))
        assertFalse(looksLikeApiKey(""))
    }

    // --- Задан ключ или нет — видно, не нажимая ничего (#447) ---

    @Test
    fun `маска показывает начало и хвост, а середину закрывает`() {
        assertEquals("sk-o…3456", maskedKey("sk-or-v1-abcdef123456"))
        assertEquals("sk-o…3456", maskedKey("  sk-or-v1-abcdef123456  "))
        assertEquals("", maskedKey(""))
        assertFalse("у короткого ключа нечего показать, не открыв половину", maskedKey("sk-123").contains("sk"))
    }

    @Test
    fun `три состояния ключа, а не два`() {
        assertTrue(keySetLabel("", saved = true).contains("Ключа пока нет"))
        assertTrue(keySetLabel("sk-or-v1-abcdef123456", saved = true).contains("на устройстве"))
        assertTrue(keySetLabel("sk-or-v1-abcdef123456", saved = false).contains("ещё не сохранён"))
    }

    @Test
    fun `состояние ключа не обещает, что он работает`() {
        // Это знает только сервис (`keyVerdict`). Вид ключа не говорит о нём ничего: он бывает
        // отозван, исчерпан или от другого сервиса — и выглядит при этом точно так же.
        val said = keySetLabel("sk-or-v1-abcdef123456", saved = true)
        assertFalse(said.contains("работа"))
        assertFalse(said.contains("Работа"))
    }

    @Test
    fun `середина ключа не попадает на экран`() {
        assertFalse(keySetLabel("sk-or-v1-abcdef123456", saved = true).contains("abcdef"))
    }
}
