package com.point.executors.di

import com.point.core.flow.BubblePolicy
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityRegistry
import com.point.core.flow.Realizer
import com.point.core.flow.Resolver
import com.point.executors.AiCapability
import com.point.executors.AiRealizer
import com.point.executors.ArchiveCapability
import com.point.executors.ArchiveRealizer
import com.point.executors.DefaultBubblePolicy
import com.point.executors.DefaultCapabilityRegistry
import com.point.executors.DefaultResolver
import com.point.executors.ImageCapability
import com.point.executors.ImageRealizer
import com.point.executors.OcrCapability
import com.point.executors.OcrRealizer
import com.point.executors.OfficeCapability
import com.point.executors.OfficeRealizer
import com.point.executors.OpenCapability
import com.point.executors.OpenRealizer
import com.point.executors.OpenUrlCapability
import com.point.executors.OpenUrlRealizer
import com.point.executors.PdfCapability
import com.point.executors.PdfRealizer
import com.point.executors.ScanCapability
import com.point.executors.ScanRealizer
import com.point.executors.SaveAllCapability
import com.point.executors.SaveAllRealizer
import com.point.executors.SaveCapability
import com.point.executors.SaveRealizer
import com.point.executors.ShareCapability
import com.point.executors.ShareRealizer
import com.point.executors.TranslateCapability
import com.point.executors.TranslateRealizer
import dagger.Binds
import dagger.Module
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
    @Binds abstract fun bubblePolicy(impl: DefaultBubblePolicy): BubblePolicy

    // --- Capabilities (declarations) ---
    @Binds @IntoSet abstract fun shareCap(c: ShareCapability): Capability
    @Binds @IntoSet abstract fun saveCap(c: SaveCapability): Capability
    @Binds @IntoSet abstract fun saveAllCap(c: SaveAllCapability): Capability
    @Binds @IntoSet abstract fun openCap(c: OpenCapability): Capability
    @Binds @IntoSet abstract fun openUrlCap(c: OpenUrlCapability): Capability
    @Binds @IntoSet abstract fun imageCap(c: ImageCapability): Capability
    @Binds @IntoSet abstract fun pdfCap(c: PdfCapability): Capability
    @Binds @IntoSet abstract fun officeCap(c: OfficeCapability): Capability
    @Binds @IntoSet abstract fun archiveCap(c: ArchiveCapability): Capability
    @Binds @IntoSet abstract fun translateCap(c: TranslateCapability): Capability
    @Binds @IntoSet abstract fun scanCap(c: ScanCapability): Capability
    @Binds @IntoSet abstract fun ocrCap(c: OcrCapability): Capability
    @Binds @IntoSet abstract fun aiCap(c: AiCapability): Capability

    // --- Realizers (behaviour) ---
    @Binds @IntoSet abstract fun shareR(r: ShareRealizer): Realizer
    @Binds @IntoSet abstract fun saveR(r: SaveRealizer): Realizer
    @Binds @IntoSet abstract fun saveAllR(r: SaveAllRealizer): Realizer
    @Binds @IntoSet abstract fun openR(r: OpenRealizer): Realizer
    @Binds @IntoSet abstract fun openUrlR(r: OpenUrlRealizer): Realizer
    @Binds @IntoSet abstract fun imageR(r: ImageRealizer): Realizer
    @Binds @IntoSet abstract fun pdfR(r: PdfRealizer): Realizer
    @Binds @IntoSet abstract fun officeR(r: OfficeRealizer): Realizer
    @Binds @IntoSet abstract fun archiveR(r: ArchiveRealizer): Realizer
    @Binds @IntoSet abstract fun translateR(r: TranslateRealizer): Realizer
    @Binds @IntoSet abstract fun scanR(r: ScanRealizer): Realizer
    @Binds @IntoSet abstract fun ocrR(r: OcrRealizer): Realizer
    @Binds @IntoSet abstract fun aiR(r: AiRealizer): Realizer
}
