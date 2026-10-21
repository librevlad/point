package com.point.core.flow

import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.tr

/**
 * «Объединить в PDF» — одна способность на телефон и компьютер (#1379, волна 2).
 *
 * Жила только на телефоне: картинки в PDF собирал Android `PdfDocument`. Способность — что
 * сделать — от этого не зависит; как собрать, решает исполнитель каждой стороны: телефон —
 * `PdfDocument`, компьютер — pdfbox. Лист у обоих один ([sheetFor]).
 */
class MergePdfCapability : Capability {
    override val id = ID
    override val icon = "pdf"

    override val meta = CapabilityMeta(latency = Latency.FAST)
    override fun label(state: ObjectState) = tr("Объединить в PDF")
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.COLLECTION
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.PDF)

    companion object { val ID = CapabilityId("merge-pdf") }
}

/** Имя собранного документа — одно на оба устройства. */
val MERGED_PDF_NAME: String get() = tr("документ.pdf")
