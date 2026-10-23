package com.point.core.flow

import com.point.core.model.ActionYield
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.tr

/*
 * «Скан», «Скан с цветом» и «Сканировать в PDF» — одни на телефон и компьютер (#1379). Как
 * выпрямить страницу, решает общее зрение страницы (`PageVision`, OpenCV); как прочитать
 * снимок и сохранить ответ — органы каждого устройства.
 */

class ScanCapability : Capability {
    override val id = ID
    override val icon = "scan"
    override val meta = CapabilityMeta(latency = Latency.FAST)

    // Прочитанный снимок — это бумага или экран с текстом, и главное превращение у него скан
    // (#1044): на корпусе владельца «Скан» у фото документов прятался за «Показать ещё».
    // Выше остаются только действия над выделенной человеком областью.
    override fun priority(state: ObjectState) = if (state.has(Feature.HAS_TEXT)) READ_PAGE_PRIORITY else meta.priority
    override fun label(state: ObjectState) = tr("Скан")
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.IMAGE)

    override fun yields(state: ObjectState) =
        ActionYield.New(ObjectKind.IMAGE, tr("чёрно-белую страницу"))

    companion object {
        val ID = CapabilityId("scan")
        private const val READ_PAGE_PRIORITY = 14
    }
}

class ScanPlusCapability : Capability {
    override val id = ID
    override val icon = "scan"
    override val meta = CapabilityMeta(latency = Latency.SLOW)

    override fun label(state: ObjectState) = tr("Скан с цветом")
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.IMAGE)

    override fun yields(state: ObjectState) =
        ActionYield.New(ObjectKind.IMAGE, tr("картинку · дольше, зато на устройстве"))

    companion object { val ID = CapabilityId("scan-plus") }
}

class ScanPdfCapability : Capability {
    override val id = ID
    override val icon = "scan"

    override val meta = CapabilityMeta(latency = Latency.FAST)
    override fun label(state: ObjectState) = tr("Сканировать в PDF")
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.COLLECTION
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.PDF)

    companion object { val ID = CapabilityId("scan-pdf") }
}
