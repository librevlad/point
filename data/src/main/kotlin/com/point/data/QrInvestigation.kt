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

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        com.point.core.flow.investigated { findings(input) }

    private suspend fun findings(obj: PointObject): Findings {
        val found = reader.scan(obj.uri.value) ?: return Findings()

        // Штрихкод на упаковке — не QR (#445). Признак и слово на экране идут от вида кода,
        // иначе Point говорит про EAN-13 «Есть QR-код» и врёт о том, что сам же увидел.
        if (found.kind == com.point.core.flow.CodeKind.PRODUCT) {
            return Findings(
                setOf(Feature.HAS_BARCODE),
                mapOf(META_ENTITY_PREFIX + "barcode" to found.text),
            )
        }

        // QR-ссылка — это и есть ссылка объекта: без этого «Открыть ссылку»
        // требовало «сначала распознайте текст» при уже показанном адресе.
        val link = found.text.trim().takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        return Findings(
            setOf(Feature.HAS_QR) + if (link != null) setOf(Feature.HAS_URL) else emptySet(),
            buildMap {
                put(META_ENTITY_PREFIX + "qr", found.text)
                link?.let { put(META_ENTITY_PREFIX + "url", it) }
            },
        )
    }
}

