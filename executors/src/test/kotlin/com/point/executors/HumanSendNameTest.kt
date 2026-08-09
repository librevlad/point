package com.point.executors

import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Живой прогон 2026-08-09: безымянный объект уезжал на компьютер как «point-6e9a92c3» —
 * внутренний идентификатор наружу (P2). Имя отправки — человеческое всегда.
 */
class HumanSendNameTest {

    @get:Rule val temp = TemporaryFolder()

    private fun obj(kind: ObjectKind, path: String, metadata: Map<String, String> = emptyMap()) =
        PointObject("6e9a92c3-id", "text/plain", ScratchRef(path), ObjectState(kind), metadata)

    @Test
    fun `своё имя всегда важнее выдуманного`() {
        val file = temp.newFile("а.txt")
        assertEquals(
            "Квитанция",
            humanSendName(obj(ObjectKind.TEXT, file.absolutePath, mapOf("name" to "Квитанция"))),
        )
    }

    @Test
    fun `безымянный текст называется своей первой строкой, а не внутренним номером`() {
        val file = temp.newFile("б.txt").apply {
            writeText("\nПозвонить менеджеру завтра\nвторая строка")
        }

        assertEquals("Позвонить менеджеру завтра", humanSendName(obj(ObjectKind.TEXT, file.absolutePath)))
    }

    @Test
    fun `пустой текст и снимок называются словами вида — id не выходит наружу`() {
        val empty = temp.newFile("в.txt")

        assertEquals("Текст", humanSendName(obj(ObjectKind.TEXT, empty.absolutePath)))
        assertEquals("Снимок", humanSendName(obj(ObjectKind.IMAGE, empty.absolutePath)))
    }
}
