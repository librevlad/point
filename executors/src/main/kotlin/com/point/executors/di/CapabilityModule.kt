package com.point.executors.di

import com.point.core.flow.BubblePolicy
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityRegistry
import com.point.core.flow.Realizer
import com.point.core.flow.Resolver
import com.point.executors.AiCapability
import com.point.executors.AiRealizer
import com.point.executors.CallCapability
import com.point.executors.CallRealizer
import com.point.executors.EmailCapability
import com.point.executors.EmailRealizer
import com.point.executors.OpenInCapability
import com.point.executors.OpenInRealizer
import com.point.executors.SmsCapability
import com.point.executors.SmsRealizer
import com.point.executors.ArchiveCapability
import com.point.executors.ArchiveRealizer
import com.point.executors.DefaultBubblePolicy
import com.point.executors.DefaultCapabilityRegistry
import com.point.executors.DefaultResolver
import com.point.executors.ExcelCapability
import com.point.executors.ExcelRealizer
import com.point.executors.LearningBubblePolicy
import com.point.executors.ImageCapability
import com.point.executors.ImageRealizer
import com.point.executors.MergePdfCapability
import com.point.executors.MergePdfRealizer
import com.point.executors.CloudOcrRealizer
import com.point.executors.DeviceOcrRealizer
import com.point.executors.OcrCapability
import com.point.executors.OfficeCapability
import com.point.executors.OfficeRealizer
import com.point.executors.OpenCapability
import com.point.executors.OpenRealizer
import com.point.executors.OpenUrlCapability
import com.point.executors.OpenUrlRealizer
import com.point.executors.PagesCapability
import com.point.executors.PagesRealizer
import com.point.executors.PdfCapability
import com.point.executors.PdfRealizer
import com.point.executors.ScanCapability
import com.point.executors.ScanRealizer
import com.point.executors.ScanPdfCapability
import com.point.executors.ScanPdfRealizer
import com.point.executors.SaveAllCapability
import com.point.executors.SaveAllRealizer
import com.point.executors.SaveCapability
import com.point.executors.SaveRealizer
import com.point.executors.ShareAllCapability
import com.point.executors.ShareAllRealizer
import com.point.executors.ShareCapability
import com.point.executors.ShareRealizer
import com.point.executors.TranslateCapability
import com.point.executors.TranslateRealizer
import com.point.executors.VCardCapability
import com.point.executors.VCardRealizer
import com.point.core.flow.ObjectStore
import com.point.executors.OpenCvScanRealizer
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Wires the capability layer. Adding a new action = one `@Binds @IntoSet`
 * capability + one realizer here (or, later, a Capability Pack registering
 * itself). The Flow Graph and bubbles follow automatically.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CapabilityModule {

    @Binds abstract fun registry(impl: DefaultCapabilityRegistry): CapabilityRegistry
    @Binds abstract fun resolver(impl: DefaultResolver): Resolver
    @Binds abstract fun bubblePolicy(impl: LearningBubblePolicy): BubblePolicy

    // --- Capabilities (declarations) ---
    @Binds @IntoSet abstract fun shareCap(c: ShareCapability): Capability
    @Binds @IntoSet abstract fun saveCap(c: SaveCapability): Capability
    @Binds @IntoSet abstract fun saveAllCap(c: SaveAllCapability): Capability
    @Binds @IntoSet abstract fun shareAllCap(c: ShareAllCapability): Capability
    @Binds @IntoSet abstract fun mergePdfCap(c: MergePdfCapability): Capability
    @Binds @IntoSet abstract fun scanPdfCap(c: ScanPdfCapability): Capability
    @Binds @IntoSet abstract fun openCap(c: OpenCapability): Capability
    @Binds @IntoSet abstract fun openInCap(c: OpenInCapability): Capability
    @Binds @IntoSet abstract fun openUrlCap(c: OpenUrlCapability): Capability
    // Entity actions (on-device detection → targeted action) — "right-click" for text.
    @Binds @IntoSet abstract fun callCap(c: CallCapability): Capability
    @Binds @IntoSet abstract fun smsCap(c: SmsCapability): Capability
    @Binds @IntoSet abstract fun emailCap(c: EmailCapability): Capability
    @Binds @IntoSet abstract fun vcardCap(c: VCardCapability): Capability
    @Binds @IntoSet abstract fun imageCap(c: ImageCapability): Capability
    @Binds @IntoSet abstract fun pdfCap(c: PdfCapability): Capability
    @Binds @IntoSet abstract fun pagesCap(c: PagesCapability): Capability
    @Binds @IntoSet abstract fun officeCap(c: OfficeCapability): Capability
    @Binds @IntoSet abstract fun archiveCap(c: ArchiveCapability): Capability
    @Binds @IntoSet abstract fun translateCap(c: TranslateCapability): Capability
    @Binds @IntoSet abstract fun excelCap(c: ExcelCapability): Capability
    @Binds @IntoSet abstract fun scanCap(c: ScanCapability): Capability
    @Binds @IntoSet abstract fun ocrCap(c: OcrCapability): Capability
    @Binds @IntoSet abstract fun aiCap(c: AiCapability): Capability

    // --- Realizers (behaviour) ---
    @Binds @IntoSet abstract fun shareR(r: ShareRealizer): Realizer
    @Binds @IntoSet abstract fun saveR(r: SaveRealizer): Realizer
    @Binds @IntoSet abstract fun saveAllR(r: SaveAllRealizer): Realizer
    @Binds @IntoSet abstract fun shareAllR(r: ShareAllRealizer): Realizer
    @Binds @IntoSet abstract fun mergePdfR(r: MergePdfRealizer): Realizer
    @Binds @IntoSet abstract fun scanPdfR(r: ScanPdfRealizer): Realizer
    @Binds @IntoSet abstract fun openR(r: OpenRealizer): Realizer
    @Binds @IntoSet abstract fun openInR(r: OpenInRealizer): Realizer
    @Binds @IntoSet abstract fun openUrlR(r: OpenUrlRealizer): Realizer
    @Binds @IntoSet abstract fun callR(r: CallRealizer): Realizer
    @Binds @IntoSet abstract fun smsR(r: SmsRealizer): Realizer
    @Binds @IntoSet abstract fun emailR(r: EmailRealizer): Realizer
    @Binds @IntoSet abstract fun vcardR(r: VCardRealizer): Realizer
    @Binds @IntoSet abstract fun imageR(r: ImageRealizer): Realizer
    @Binds @IntoSet abstract fun pdfR(r: PdfRealizer): Realizer
    @Binds @IntoSet abstract fun pagesR(r: PagesRealizer): Realizer
    @Binds @IntoSet abstract fun officeR(r: OfficeRealizer): Realizer
    @Binds @IntoSet abstract fun archiveR(r: ArchiveRealizer): Realizer
    @Binds @IntoSet abstract fun translateR(r: TranslateRealizer): Realizer
    @Binds @IntoSet abstract fun excelR(r: ExcelRealizer): Realizer
    @Binds @IntoSet abstract fun scanR(r: ScanRealizer): Realizer
    // OCR has two realizers behind one capability — the Resolver ranks device before
    // cloud and chains them (device recognises nothing -> cloud). Roadmap #1 in prod.
    @Binds @IntoSet abstract fun deviceOcrR(r: DeviceOcrRealizer): Realizer
    @Binds @IntoSet abstract fun cloudOcrR(r: CloudOcrRealizer): Realizer
    @Binds @IntoSet abstract fun aiR(r: AiRealizer): Realizer

    companion object {
        // @Provides (not @Binds) keeps the concrete OpenCV realizer out of the binding
        // signature, so Dagger's KSP aggregation never has to resolve the native OpenCV AAR
        // types (which it can't, even though kotlinc can) — the pack still lands @IntoSet (#45).
        @Provides @IntoSet
        fun openCvScanR(store: ObjectStore): Realizer = OpenCvScanRealizer(store)
    }
}
