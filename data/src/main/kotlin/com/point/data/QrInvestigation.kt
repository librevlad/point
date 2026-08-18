package com.point.data

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.Findings
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.QrReader
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import javax.inject.Inject

class QrInvestigation @Inject constructor() : Capability {

    override val id = ID

    override val icon = ""

    override val meta = CapabilityMeta(
        investigation = true,
        latency = Latency.FAST,
        mayYield = setOf(Feature.HAS_QR, Feature.HAS_BARCODE),
    )

    override fun label(state: ObjectState) = ""

    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE

    override fun produces(state: ObjectState) = state

    companion object {

        // Не «qr»: этот id носит действие «QR-код» (сделать QR из текста). Общий id
        // сталкивает реализаторы в резолвере — как у пары «ocr»/«Распознать текст».
        val ID = com.point.core.model.CapabilityId("qr-content")
    }
}

class QrInvestigationRealizer @Inject constructor(
    private val reader: QrReader,
) : Realizer {

    override val capabilityId = QrInvestigation.ID

    override val meta = com.point.core.flow.RealizerMeta(actor = "qr-reader")

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        com.point.core.flow.investigated { findings(input) }

    private val READ_FROM_FRAME = com.point.core.model.Provenance.OCR.wire

    private suspend fun findings(obj: PointObject): Findings {
        val found = reader.scan(obj.uri.value) ?: return Findings()

        // Штрихкод на упаковке — не QR (#445). Признак и слово на экране идут от вида кода,
        // иначе Point говорит про EAN-13 «Есть QR-код» и врёт о том, что сам же увидел.
        if (found.kind == com.point.core.flow.CodeKind.PRODUCT) {

            // Код товара сходится сам с собой или его нет (#940): на фотографии автомобиля
            // сканер «прочитал» штрихкод 13821702, и он встал на экран галочкой рядом с
            // настоящей датой съёмки. Не сошлось — это не находка, а узор.
            if (!com.point.core.flow.productCodeChecks(found.text)) return Findings()
            return Findings(
                setOf(Feature.HAS_BARCODE),
                mapOf(
                    META_ENTITY_PREFIX + "barcode" to found.text,
                    // Откуда значение: прочитано с кадра (#948). Без этого оно молча
                    // становилось «дано» и получало галочку наравне с датой из Exif.
                    META_ENTITY_PREFIX + "barcode" + com.point.core.flow.META_SOURCE_SUFFIX to
                        com.point.core.model.Provenance.OCR.wire,
                ),
            )
        }

        // QR-ссылка — это и есть ссылка объекта: без этого «Открыть ссылку»
        // требовало «сначала распознайте текст» при уже показанном адресе.
        //
        // И это ОДНО знание, а не два (#1119). Прежде ссылка из кода записывалась дважды —
        // ссылкой и содержимым QR, — и человек видел две строки об одном: «Нашёл ссылку
        // point.leerio.app/health» и «Есть QR-код point.leerio.app/health». Код здесь не
        // второй факт, а путь, которым ссылка получена: то, что на кадре есть QR, живёт
        // признаком состояния, а прочитанное — знанием.
        val link = found.text.trim().takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        val key = META_ENTITY_PREFIX + if (link != null) "url" else "qr"
        return Findings(
            setOf(Feature.HAS_QR) + if (link != null) setOf(Feature.HAS_URL) else emptySet(),
            buildMap {
                put(key, link ?: found.text)

                // Откуда значение: прочитано с кадра (#948, #941). Штрихкод это говорил, а
                // сам QR и его ссылка молчали — и знание, прочитанное с кадра, стояло на
                // экране без галочки, как будто неизвестно откуда взялось.
                put(key + com.point.core.flow.META_SOURCE_SUFFIX, READ_FROM_FRAME)
            },
        )
    }
}

