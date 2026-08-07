package com.point.core.flow

import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AtomRecognizerTest {

    private val image =
        PointObject("id", "image/png", ScratchRef("/tmp/shot.png"), ObjectState(ObjectKind.IMAGE))

    @Test
    fun `плоский текст выводится из атомов, а не читается отдельно`() = runBlocking {
        val reader = object : AtomRecognizer {
            override suspend fun read(obj: PointObject) = AtomLayer(
                listOf(
                    Atom("a2", "Нор І.А", Box(125f, 101f, 190f, 121f)),
                    Atom("a1", "Одержувач", Box(10f, 100f, 120f, 120f)),
                )
            )
        }

        assertEquals("Одержувач Нор І.А", reader.recognize(image))
    }
}
