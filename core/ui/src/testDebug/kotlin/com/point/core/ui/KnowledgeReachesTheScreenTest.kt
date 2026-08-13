package com.point.core.ui

import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.understoodName
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Знание, которое Point получил, человек видит (#935).
 *
 * Сумма счёта лежала в графе разобранная — `7 800`, валюта `грн`, прочитано с текста, — а на
 * экране телефона её не было вовсе. Строки знания собирались перечислением признаков, и у
 * суммы своей строки в перечислении не оказалось. Компьютер тот же объект показывал целиком:
 * у него список общий. Один объект — и два разных знания, смотря с какого устройства смотреть.
 *
 * Сторож стоит на классе: **каждый** вид знания, у которого есть человеческое имя, обязан
 * доходить до экрана. Новое имя без строки на экране — падение, а не тишина.
 */
class KnowledgeReachesTheScreenTest {

    /** Значение каждого вида — такое, каким оно приходит с настоящего документа. */
    private val known = mapOf(
        "phone" to "+380 67 636 05 60",
        "email" to "info@epicentrk.ua",
        "url" to "https://epicentrk.ua",
        "address" to "вул. Соборна, 12",
        "date" to "30.09.2026",
        "card" to "5169 3351 0912 3456",
        "amount" to "12 500",
        "track" to "20450749113295",
        "meter" to "00001154",
        "qr" to "https://epicentrk.ua/qr",
        "geo" to "47.8388, 35.1396",
        "place" to "Відділення №5",
        "receipt" to "1234567",
        "subject" to "Оплата рахунку",
    )

    private fun document(metadata: Map<String, String>) = PointObject(
        id = "id",
        mime = "text/plain",
        uri = ScratchRef("/scratch/рахунок.txt"),
        state = ObjectState(ObjectKind.TEXT),
        metadata = metadata,
    )

    @Test fun `каждый вид знания доходит до экрана`() {
        val unseen = known.filterNot { (name, value) ->
            val facts = understoodFacts(document(mapOf(META_ENTITY_PREFIX + name to value)))
            facts.any { it.key == name && !it.value.isNullOrBlank() }
        }.keys

        assertTrue("Point знает, а человек не видит: $unseen", unseen.isEmpty())
    }

    @Test fun `у каждого названного вида знания есть свой пример`() {
        val named = known.keys.filter { understoodName(META_ENTITY_PREFIX + it) != null }

        assertTrue(
            "вид знания назван человеку, а сторож его не проверяет: ${known.keys - named.toSet()}",
            named.size == known.size,
        )
    }
}
