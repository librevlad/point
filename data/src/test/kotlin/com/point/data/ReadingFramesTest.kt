package com.point.data

import com.point.core.flow.Atom
import com.point.core.flow.AtomCodec
import com.point.core.flow.AtomLayer
import com.point.core.flow.Box
import com.point.core.flow.FrameTransform
import com.point.core.flow.META_CLOUD_ATOMS_REF
import com.point.core.flow.META_OCR_ATOMS_REF
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * «Плотность текста» правила увеличения (#273) — та её половина, что живёт на Android-стороне:
 * откуда берётся высота слова и что происходит, когда её взять неоткуда.
 *
 * Проверяется без единого пикселя: сам вход — сайдкар слоя, который пишет `OcrEnricher`, и он
 * обычный текстовый файл. Ресайз битмапа за этим швом не стоит и проверяться здесь не может —
 * его порядок закреплён фейком в `:core:flow` (`ReadingUpscaleTest`).
 *
 * Зачем вообще: неизвестная плотность обязана означать **незнание**, а не «буквы крупные». Соврав
 * тут в другую сторону, мы бы тихо выключили увеличение на всех свежих кадрах — то есть ровно на
 * тех, ради которых оно заведено.
 */
class ReadingFramesTest {

    private fun image(metadata: Map<String, String> = emptyMap()) =
        PointObject("id", "image/jpeg", ScratchRef("/tmp/page.jpg"), ObjectState(ObjectKind.IMAGE), metadata)

    private fun sidecar(layer: AtomLayer): String =
        File.createTempFile("atoms", ".tsv").apply {
            deleteOnExit()
            writeText(AtomCodec.encode(layer))
        }.absolutePath

    private fun words(heightPx: Float, count: Int = 12) = (1..count).map {
        Atom("w$it", "слово", Box(0f, it * 100f, 60f, it * 100f + heightPx), 0.9f, "tesseract", "5.3", 0)
    }

    @Test
    fun `высота слова берётся из уже прочитанного слоя`() {
        val ref = sidecar(AtomLayer(words(heightPx = 24f)))

        assertEquals(24, knownTextHeightPx(image(mapOf(META_OCR_ATOMS_REF to ref))))
    }

    /** Офлайновый слой идёт первым: он читал ровно ту копию, которую мы сейчас готовим, а
     *  облачный — свою, объявленную сервисом. */
    @Test
    fun `свой слой предпочитается облачному`() {
        val obj = image(
            mapOf(
                META_OCR_ATOMS_REF to sidecar(AtomLayer(words(heightPx = 24f))),
                META_CLOUD_ATOMS_REF to sidecar(AtomLayer(words(heightPx = 96f))),
            ),
        )

        assertEquals(24, knownTextHeightPx(obj))
    }

    @Test
    fun `облачный слой годится, когда своего нет`() {
        val ref = sidecar(AtomLayer(words(heightPx = 96f)))

        assertEquals(96, knownTextHeightPx(image(mapOf(META_CLOUD_ATOMS_REF to ref))))
    }

    /** Свежий кадр никто не читал — и это незнание, а не «буквы крупные»: правило вернётся к
     *  размеру кадра, то есть к увеличению. */
    @Test
    fun `кадр, который никто не читал, плотности не сообщает`() {
        assertNull(knownTextHeightPx(image()))
    }

    /** Сайдкар не дожил (scratch чистится по концу флоу, объект приехал из истории) — молча к
     *  размеру кадра. Ронять здесь чтение из-за пропавшего вспомогательного файла нечем. */
    @Test
    fun `пропавший или битый сайдкар не роняет подготовку кадра`() {
        assertNull(knownTextHeightPx(image(mapOf(META_OCR_ATOMS_REF to "/нет/такого.tsv"))))

        val broken = File.createTempFile("atoms", ".tsv").apply { deleteOnExit(); writeText("не дамп вовсе") }
        assertNull(knownTextHeightPx(image(mapOf(META_OCR_ATOMS_REF to broken.absolutePath))))
    }

    /** Множитель прошлого чтения переживает сайдкар (иначе увеличенный кадр во второй раз сам себе
     *  доложил бы, что буквы уже крупные) — и здесь проверяется вся дорога целиком: слой → файл →
     *  разбор → высота. */
    @Test
    fun `увеличение прошлого чтения доезжает через сайдкар`() {
        // Движок видел слова по 72 px — но только потому, что кадр перед ним растянули втрое.
        // Честная плотность кадра — 24, и именно её обязан вернуть сайдкар; 72 означало бы, что
        // увеличенный кадр во второй раз сам себе доложил «буквы уже крупные».
        val ref = sidecar(
            AtomLayer(
                words(heightPx = 24f),
                transform = FrameTransform(sample = 1, uprightWidth = 3000, uprightHeight = 2250, upscale = 3),
            ),
        )

        assertEquals(24, knownTextHeightPx(image(mapOf(META_OCR_ATOMS_REF to ref))))
    }
}
