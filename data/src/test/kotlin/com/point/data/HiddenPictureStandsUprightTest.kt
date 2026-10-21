package com.point.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Замазанный снимок стоит, как его сняли (#1379).
 *
 * Места мажутся в пикселях файла — Focus живёт в сыром кадре (#1013), — а результат пишется
 * PNG, куда метка поворота камеры не переезжает. Без разворота перед записью портретный
 * снимок выходил замазанным, но лежащим на боку: так же, как прежде «Очистить метаданные».
 * Пиксели Android в JVM-тесте не поднять, поэтому сторож стоит на самом шве.
 */
class HiddenPictureStandsUprightTest {

    private val redactor = File(repo, "data/src/main/kotlin/com/point/data/AndroidImageRedactor.kt").readText()

    @Test
    fun `перед записью кадр ставится по метке камеры`() {
        assertTrue("метку поворота никто не читает", "TAG_ORIENTATION" in redactor)
        assertTrue("кадр не разворачивают перед записью", "postRotate" in redactor)

        val turned = redactor.indexOf("upright(page, imagePath)")
        val written = redactor.indexOf(".compress(")
        assertTrue("разворот стоит не перед записью", turned in 0 until written)
    }
}
