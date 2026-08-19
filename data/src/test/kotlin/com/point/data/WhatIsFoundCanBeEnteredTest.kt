package com.point.data

import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertTrue
import org.junit.Test
import com.point.core.flow.entityObjects

/**
 * В найденное можно войти (#947).
 *
 * Объектом становились пять видов сущностей из пятнадцати: телефон, почта, ссылка, адрес,
 * дата. Прочитанный с кадра штрихкод, сумма счёта, показание счётчика, номер квитанции,
 * координаты и место оставались строкой знания — войти в них было нельзя и продолжить их
 * понимание тоже. Конституция говорит обратное: человек входит в найденный объект и
 * продолжает понимать его тем же механизмом.
 *
 * Решение владельца 13.08.2026: «Всё вместе, до релиза» — в том числе повышение остальных
 * сущностей до объектов.
 */
class WhatIsFoundCanBeEnteredTest {

    private val page = PointObject(
        id = "page",
        mime = "image/jpeg",
        uri = ScratchRef("/scratch/авто.jpg"),
        state = ObjectState(ObjectKind.IMAGE),
    )

    /** Значения с настоящих кадров прогона: штрихкод с фотографии, суммы и даты со счетов. */
    private val found = mapOf(
        "barcode" to "13821702",
        "amount" to "12 500",
        "meter" to "00001154",
        "receipt" to "1234567",
        "geo" to "47.8388, 35.1396",
        "place" to "Відділення №5",
    )

    @Test fun `каждый найденный вид становится объектом, в который можно войти`() {
        val stayed = found.filter { (name, value) ->
            val (objects, _) = entityObjects(page, mapOf(META_ENTITY_PREFIX + name to value), "t")
            objects.none { it.uri.value == value }
        }.keys

        assertTrue("знание есть, а объекта нет — войти некуда: $stayed", stayed.isEmpty())
    }

    @Test fun `у вошедшего объекта названо, из чего он вычитан`() {
        val (objects, relations) = entityObjects(
            page,
            mapOf(META_ENTITY_PREFIX + "amount" to "12 500"),
            "identifiers",
        )

        val amount = objects.single()
        assertTrue(amount.sourceObjects == listOf("page"))
        assertTrue(relations.single().toId == "page")
    }
}
