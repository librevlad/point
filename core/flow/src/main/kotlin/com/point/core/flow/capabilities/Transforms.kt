package com.point.core.flow.capabilities

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Cost
import com.point.core.flow.Latency
import com.point.core.model.ActionYield
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.isFileBacked

const val OFFICE_PDF_SUBSTANCE = "PDF с текстом документа"

class QrCapability  : Capability {
    override val id = ID
    override val icon = "qr"
    override val meta = CapabilityMeta(priority = 45)
    override fun label(state: ObjectState) = "QR-код"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.TEXT || state.kind == ObjectKind.URL
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.IMAGE)
    override fun intents(state: ObjectState) = setOf(Intent.PREPARE)

    companion object { val ID = CapabilityId("qr") }
}

class ArchiveCapability  : Capability {
    override val id = ID
    override val icon = "unzip"

    override val meta = CapabilityMeta(latency = Latency.FAST)
    override fun label(state: ObjectState) = "Распаковать"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.ZIP
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.COLLECTION)

    companion object { val ID = CapabilityId("archive") }
}

class OfficeCapability  : Capability {
    override val id = ID
    override val icon = "office"

    override val meta = CapabilityMeta(latency = Latency.FAST)
    override fun label(state: ObjectState) = "Извлечь текст"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.OFFICE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)

    companion object { val ID = CapabilityId("office") }
}

class ImageCapability  : Capability {
    override val id = ID
    override val icon = "compress"

    override val meta = CapabilityMeta(latency = Latency.FAST)

    override fun label(state: ObjectState) = "Сжать"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.IMAGE)

    companion object { val ID = CapabilityId("image") }
}

class DropLinkCapability  : Capability {
    override val id = ID
    override val icon = "link"
    override val meta = CapabilityMeta(
        priority = 35,
        cost = Cost.FREE,
        latency = Latency.SLOW,
        network = true,
    )

    override fun label(state: ObjectState) = "Дать ссылку"

    override fun accepts(state: ObjectState) =
        state.kind.isFileBacked && state.kind != ObjectKind.URL

    override fun produces(state: ObjectState) = ObjectState(ObjectKind.URL)

    companion object { val ID = CapabilityId("drop-link") }
}

class PdfCapability : Capability {
    override val id = ID
    override val icon = "pdf"

    override val meta = CapabilityMeta(latency = Latency.FAST)
    override fun label(state: ObjectState) =
        if (state.kind == ObjectKind.PDF) "Извлечь текст" else "В PDF"
    override fun accepts(state: ObjectState) =
        state.kind in setOf(ObjectKind.IMAGE, ObjectKind.TEXT, ObjectKind.OFFICE) ||

            (state.kind == ObjectKind.PDF && !state.has(Feature.IS_IMAGE_PDF))
    override fun produces(state: ObjectState) =
        if (state.kind == ObjectKind.PDF) ObjectState(ObjectKind.TEXT) else ObjectState(ObjectKind.PDF)

    override fun yields(state: ObjectState) = when (state.kind) {
        ObjectKind.PDF -> ActionYield.New(ObjectKind.TEXT)
        ObjectKind.OFFICE -> ActionYield.New(ObjectKind.PDF, "$OFFICE_PDF_SUBSTANCE · без оформления")
        else -> ActionYield.New(ObjectKind.PDF)
    }

    companion object { val ID = CapabilityId("pdf") }
}
