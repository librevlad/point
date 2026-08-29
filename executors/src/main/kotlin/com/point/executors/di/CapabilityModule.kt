package com.point.executors.di

import com.point.core.flow.ActionAvailability
import com.point.core.flow.BubblePolicy
import com.point.core.flow.AppLauncher
import com.point.core.flow.Capability
import com.point.core.flow.ChosenApps
import com.point.core.flow.CapabilityRegistry
import com.point.core.flow.Realizer
import com.point.core.flow.AiChatResponder
import com.point.core.flow.Resolver
import com.point.executors.ExtractAllCapability
import com.point.executors.AppCapability
import com.point.executors.PcCapability
import com.point.executors.PcRealizer
import com.point.executors.AppOpenRealizer
import com.point.executors.AiCapability
import com.point.executors.remotePcCapabilities
import com.point.executors.WordPlusCapability
import com.point.executors.WordPlusRealizer
import com.point.executors.remotePcRealizers
import com.point.executors.JobReplyCapability
import com.point.executors.JobReplyRealizer
import com.point.executors.FixErrorsCapability
import com.point.executors.FixErrorsRealizer
import com.point.executors.FixErrorsStrongerCapability
import com.point.executors.FixErrorsStrongerRealizer
import com.point.executors.UnderstandCapability
import com.point.executors.UnderstandRealizer
import com.point.executors.ShoppingListCapability
import com.point.executors.ShoppingListRealizer
import com.point.executors.AiChatResponderImpl
import com.point.executors.AiRealizer
import com.point.executors.CallCapability
import com.point.executors.CallRealizer
import com.point.executors.EmailCapability
import com.point.executors.EmailRealizer
import com.point.executors.EventCapability
import com.point.executors.EventRealizer
import com.point.executors.MapCapability
import com.point.executors.MapRealizer
import com.point.executors.OpenInCapability
import com.point.executors.OpenInRealizer
import com.point.executors.PhoneAppsCapability
import com.point.executors.PhoneAppsRealizer
import com.point.executors.SmsCapability
import com.point.executors.SmsRealizer
import com.point.executors.ArchiveRealizer
import com.point.executors.BlurBgCapability
import com.point.executors.HideAreaCapability
import com.point.executors.HideAreaRealizer
import com.point.executors.TakeFragmentCapability
import com.point.executors.TakeFragmentRealizer
import com.point.executors.BlurBgRealizer
import com.point.executors.DefaultBubblePolicy
import com.point.executors.DefaultCapabilityRegistry
import com.point.executors.DefaultResolver
import com.point.executors.ExcelCapability
import com.point.executors.ExcelRealizer
import com.point.executors.ExtractAllRealizer
import com.point.executors.FindCapability
import com.point.executors.FindRealizer
import com.point.executors.LearningBubblePolicy
import com.point.executors.ImageRealizer
import com.point.executors.MergePdfCapability
import com.point.executors.MergePdfRealizer
import com.point.executors.CloudOcrCapability
import com.point.executors.CloudOcrDirectRealizer
import com.point.executors.ExternalEyeCloudOcrRealizer
import com.point.executors.ExternalEyeOcrRealizer
import com.point.executors.CloudOcrRealizer
import com.point.executors.CopyCapability
import com.point.executors.CopyCardCapability
import com.point.executors.CopyCardRealizer
import com.point.executors.CopyRealizer
import com.point.executors.CutoutCapability
import com.point.executors.CutoutRealizer
import com.point.executors.DeviceOcrRealizer
import com.point.executors.OfficeRealizer
import com.point.executors.OpenCapability
import com.point.executors.OpenRealizer
import com.point.executors.OpenUrlCapability
import com.point.executors.OpenUrlRealizer
import com.point.executors.PagesCapability
import com.point.executors.ReadDocumentCapability
import com.point.executors.ReadDocumentRealizer
import com.point.executors.PagesRealizer
import com.point.executors.SlidesCapability
import com.point.executors.SlidesRealizer
import com.point.executors.PdfRealizer
import com.point.executors.ReplaceBgCapability
import com.point.executors.ReplaceBgRealizer
import com.point.executors.QrRealizer
import com.point.executors.ReadQrCapability
import com.point.executors.ReadQrRealizer
import com.point.executors.RenewPeriodCapability
import com.point.executors.RenewPeriodRealizer
import com.point.executors.ScanCapability
import com.point.executors.ScanRealizer
import com.point.executors.ScanPlusCapability
import com.point.executors.PageScanRealizer
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
import com.point.executors.TranscribeCapability
import com.point.executors.TranscribeRealizer
import com.point.executors.TranslateCapability
import com.point.executors.TranslateRealizer
import com.point.executors.WordCapability
import com.point.executors.WordRealizer
import com.point.executors.SaveContactCapability
import com.point.executors.SaveContactRealizer
import com.point.executors.VCardCapability
import com.point.executors.VCardRealizer
import com.point.core.flow.ObjectClassifier
import com.point.core.flow.ObjectStore
import com.point.executors.OpenCvStraightFrame
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class CapabilityModule {

    @Binds abstract fun registry(impl: DefaultCapabilityRegistry): CapabilityRegistry
    @Binds abstract fun resolver(impl: DefaultResolver): Resolver

    @Binds abstract fun actionAvailability(impl: com.point.executors.RealizerAvailability): ActionAvailability
    @Binds abstract fun bubblePolicy(impl: LearningBubblePolicy): BubblePolicy

    @Binds @IntoSet abstract fun dropLinkReal(r: com.point.executors.DropLinkRealizer): Realizer

    @Binds @IntoSet @OwnCapabilities abstract fun correctValueCap(c: com.point.executors.CorrectValueCapability): Capability
    @Binds @IntoSet abstract fun correctValueReal(r: com.point.executors.CorrectValueRealizer): Realizer

    @Binds @IntoSet @OwnCapabilities abstract fun pcCap(c: PcCapability): Capability
    @Binds @IntoSet @OwnCapabilities abstract fun shareCap(c: ShareCapability): Capability
    @Binds @IntoSet @OwnCapabilities abstract fun saveCap(c: SaveCapability): Capability
    @Binds @IntoSet @OwnCapabilities abstract fun saveAllCap(c: SaveAllCapability): Capability
    @Binds @IntoSet @OwnCapabilities abstract fun shareAllCap(c: ShareAllCapability): Capability
    @Binds @IntoSet @OwnCapabilities abstract fun mergePdfCap(c: MergePdfCapability): Capability
    @Binds @IntoSet @OwnCapabilities abstract fun scanPdfCap(c: ScanPdfCapability): Capability
    @Binds @IntoSet @OwnCapabilities abstract fun openCap(c: OpenCapability): Capability
    @Binds @IntoSet @OwnCapabilities abstract fun extractAllCap(c: ExtractAllCapability): Capability
    @Binds @IntoSet @OwnCapabilities abstract fun openInCap(c: OpenInCapability): Capability
    @Binds @IntoSet @OwnCapabilities abstract fun openUrlCap(c: OpenUrlCapability): Capability

    @Binds @IntoSet @OwnCapabilities abstract fun callCap(c: CallCapability): Capability
    @Binds @IntoSet @OwnCapabilities abstract fun smsCap(c: SmsCapability): Capability

    // Действие приехало 10.08.2026 (#466), но в реестр не попало — и для человека его не
    // существовало: «не могу отправить в гетконтакт» (живой прогон 12.08.2026). Регистрация
    // и есть вся починка.
    @Binds @IntoSet @OwnCapabilities abstract fun phoneAppsCap(c: PhoneAppsCapability): Capability
    @Binds @IntoSet @OwnCapabilities abstract fun emailCap(c: EmailCapability): Capability
    @Binds @IntoSet @OwnCapabilities abstract fun mapCap(c: MapCapability): Capability
    @Binds @IntoSet @OwnCapabilities abstract fun eventCap(c: EventCapability): Capability
    @Binds @IntoSet @OwnCapabilities abstract fun copyCardCap(c: CopyCardCapability): Capability
    @Binds @IntoSet @OwnCapabilities abstract fun vcardCap(c: VCardCapability): Capability

    @Binds @IntoSet @OwnCapabilities abstract fun saveContactCap(c: SaveContactCapability): Capability
    @Binds @IntoSet @OwnCapabilities abstract fun copyCap(c: CopyCapability): Capability

    @Binds @IntoSet @OwnCapabilities abstract fun findCap(c: FindCapability): Capability
    @Binds @IntoSet @OwnCapabilities abstract fun pagesCap(c: PagesCapability): Capability

    // «Слайды» раскладывают презентацию так же, как «Страницы» — PDF (#1105).
    @Binds @IntoSet @OwnCapabilities abstract fun slidesCap(c: SlidesCapability): Capability

    // Сканированный PDF читается одним действием (#1014): страницы → чтение → знание на PDF.
    @Binds @IntoSet @OwnCapabilities abstract fun readDocumentCap(c: ReadDocumentCapability): Capability
    @Binds @IntoSet abstract fun readDocumentReal(r: ReadDocumentRealizer): Realizer
    @Binds @IntoSet @OwnCapabilities abstract fun translateCap(c: TranslateCapability): Capability

    @Binds @IntoSet @OwnCapabilities abstract fun transcribeCap(c: TranscribeCapability): Capability
    @Binds @IntoSet @OwnCapabilities abstract fun speakCap(c: com.point.executors.SpeakCapability): Capability
    @Binds @IntoSet @OwnCapabilities abstract fun excelCap(c: ExcelCapability): Capability

    @Binds @IntoSet @OwnCapabilities abstract fun renewPeriodCap(c: RenewPeriodCapability): Capability
    @Binds @IntoSet @OwnCapabilities abstract fun wordCap(c: WordCapability): Capability
    @Binds @IntoSet @OwnCapabilities abstract fun readQrCap(c: ReadQrCapability): Capability
    @Binds @IntoSet @OwnCapabilities abstract fun scanCap(c: ScanCapability): Capability
    @Binds @IntoSet @OwnCapabilities abstract fun scanPlusCap(c: ScanPlusCapability): Capability
    @Binds @IntoSet @OwnCapabilities abstract fun cutoutCap(c: CutoutCapability): Capability
    @Binds @IntoSet @OwnCapabilities abstract fun blurBgCap(c: BlurBgCapability): Capability

    @Binds @IntoSet @OwnCapabilities abstract fun takeFragmentCap(c: TakeFragmentCapability): Capability

    @Binds @IntoSet @OwnCapabilities abstract fun hideAreaCap(c: HideAreaCapability): Capability
    @Binds @IntoSet @OwnCapabilities abstract fun replaceBgCap(c: ReplaceBgCapability): Capability

    @Binds @IntoSet @OwnCapabilities abstract fun cloudOcrCap(c: CloudOcrCapability): Capability
    @Binds @IntoSet @OwnCapabilities abstract fun aiCap(c: AiCapability): Capability
    @Binds @IntoSet @OwnCapabilities abstract fun shoppingListCap(c: ShoppingListCapability): Capability

    @Binds @IntoSet @OwnCapabilities abstract fun understandCap(c: UnderstandCapability): Capability

    @Binds @IntoSet @OwnCapabilities abstract fun fixErrorsCap(c: FixErrorsCapability): Capability
    @Binds @IntoSet @OwnCapabilities abstract fun fixErrorsStrongerCap(c: FixErrorsStrongerCapability): Capability
    @Binds @IntoSet @OwnCapabilities abstract fun wordPlusCap(c: WordPlusCapability): Capability
    @Binds @IntoSet @OwnCapabilities abstract fun jobReplyCap(c: JobReplyCapability): Capability

    // «Очистить метаданные» — такое же своё умение, как остальные (#1256): без квалификатора
    // оно не попадало в набор для сверки с компьютером, и в день, когда компьютер объявит
    // `clean-metadata`, телефон счёл бы своё же умение чужим и показал вторую строку на то же
    // действие. Способность графики Android не трогает — её тут держит @Binds, в отличие от
    // реализатора ниже.
    @Binds @IntoSet @OwnCapabilities
    abstract fun cleanMetadataCap(c: com.point.executors.CleanMetadataCapability): Capability

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
    @Binds @IntoSet abstract fun mapR(r: MapRealizer): Realizer
    @Binds @IntoSet abstract fun eventR(r: EventRealizer): Realizer
    @Binds @IntoSet abstract fun copyCardR(r: CopyCardRealizer): Realizer
    @Binds @IntoSet abstract fun vcardR(r: VCardRealizer): Realizer
    @Binds @IntoSet abstract fun saveContactR(r: SaveContactRealizer): Realizer
    @Binds @IntoSet abstract fun copyR(r: CopyRealizer): Realizer
    @Binds @IntoSet abstract fun extractAllR(r: ExtractAllRealizer): Realizer
    @Binds @IntoSet abstract fun findR(r: FindRealizer): Realizer
    @Binds @IntoSet abstract fun imageR(r: ImageRealizer): Realizer
    @Binds @IntoSet abstract fun pdfR(r: PdfRealizer): Realizer
    @Binds @IntoSet abstract fun pagesR(r: PagesRealizer): Realizer
    @Binds @IntoSet abstract fun slidesR(r: SlidesRealizer): Realizer
    @Binds @IntoSet abstract fun officeR(r: OfficeRealizer): Realizer
    @Binds @IntoSet abstract fun archiveR(r: ArchiveRealizer): Realizer
    @Binds @IntoSet abstract fun translateR(r: TranslateRealizer): Realizer
    @Binds @IntoSet abstract fun transcribeR(r: TranscribeRealizer): Realizer
    @Binds @IntoSet abstract fun speakR(r: com.point.executors.SpeakRealizer): Realizer
    @Binds @IntoSet abstract fun excelR(r: ExcelRealizer): Realizer
    @Binds @IntoSet abstract fun renewPeriodR(r: RenewPeriodRealizer): Realizer
    @Binds @IntoSet abstract fun wordR(r: WordRealizer): Realizer
    @Binds @IntoSet abstract fun qrR(r: QrRealizer): Realizer
    @Binds @IntoSet abstract fun readQrR(r: ReadQrRealizer): Realizer
    @Binds @IntoSet abstract fun scanR(r: ScanRealizer): Realizer
    @Binds @IntoSet abstract fun cutoutR(r: CutoutRealizer): Realizer
    @Binds @IntoSet abstract fun blurBgR(r: BlurBgRealizer): Realizer

    @Binds @IntoSet abstract fun takeFragmentR(r: TakeFragmentRealizer): Realizer

    @Binds @IntoSet abstract fun hideAreaR(r: HideAreaRealizer): Realizer
    @Binds @IntoSet abstract fun replaceBgR(r: ReplaceBgRealizer): Realizer

    @Binds @IntoSet abstract fun deviceOcrR(r: DeviceOcrRealizer): Realizer
    @Binds @IntoSet abstract fun cloudOcrR(r: CloudOcrRealizer): Realizer

    @Binds @IntoSet abstract fun externalEyeOcrR(r: ExternalEyeOcrRealizer): Realizer
    @Binds @IntoSet abstract fun externalEyeCloudOcrR(r: ExternalEyeCloudOcrRealizer): Realizer
    @Binds @IntoSet abstract fun cloudOcrDirectR(r: CloudOcrDirectRealizer): Realizer
    @Binds @IntoSet abstract fun aiR(r: AiRealizer): Realizer
    @Binds @IntoSet abstract fun shoppingListR(r: ShoppingListRealizer): Realizer
    @Binds @IntoSet abstract fun understandR(r: UnderstandRealizer): Realizer
    @Binds @IntoSet abstract fun phoneAppsR(r: PhoneAppsRealizer): Realizer

    @Binds @IntoSet abstract fun fixErrorsR(r: FixErrorsRealizer): Realizer
    @Binds @IntoSet abstract fun fixErrorsStrongerR(r: FixErrorsStrongerRealizer): Realizer
    @Binds @IntoSet abstract fun wordPlusR(r: WordPlusRealizer): Realizer
    @Binds @IntoSet abstract fun jobReplyR(r: JobReplyRealizer): Realizer
    @Binds @IntoSet abstract fun pcR(r: PcRealizer): Realizer

    companion object {

        @Provides
        fun executionPolicy(
            yolo: com.point.core.flow.YoloMode,
        ): com.point.core.flow.ExecutionPolicy =
            com.point.core.flow.DefaultExecutionPolicy(yolo)

        /**
         * Орган «офис → PDF» живёт на компьютере (#403): телефон его не имеет и пересказом
         * не подменяет. Способность видит орган и сама решает, действие это или причина.
         */
        @Provides
        fun officeOrgan(
            caps: com.point.core.flow.PcCapsStore,
            links: com.point.core.flow.PcLinks,
        ): com.point.core.flow.OfficeOrgan = com.point.core.flow.PcOfficeOrgan(caps, links)

        // Дорогу чтения снимка называет телефон сам (#1021): словарь общий, слово — исполнителя.
        @Provides @ElementsIntoSet @OwnCapabilities
        fun sharedCaps(
            office: com.point.core.flow.OfficeOrgan,

            // Аккаунт спрашивается в момент вопроса (#1022): человек входит и выходит,
            // не перезапуская Point, и «Дать ссылку» обязана видеть нынешнее положение.
            account: com.point.core.flow.AccountStore,
        ): Set<Capability> =
            com.point.core.flow.capabilities.sharedCapabilities(
                office,
                ocrPromise = com.point.executors.OCR_ON_PHONE_PROMISE,
            ) { account.current() != null }.toSet()

        /**
         * «Скан» и «Скан с цветом» выравнивает один исполнитель (#1333): они отличались
         * только именем способности и пометкой `op`, а делали одно и то же.
         */
        @Provides @ElementsIntoSet
        fun pageScanRealizers(store: ObjectStore): Set<Realizer> = setOf(
            PageScanRealizer(ScanCapability.ID, "scan", store),
            PageScanRealizer(ScanPlusCapability.ID, "scan-plus", store),
        )

        /**
         * Кадр для второго захода чтения (#1041): обработка снимков живёт здесь, а понимание
         * кадра — в исследовании, и встречаются они на шве. @Provides, а не @Binds: тот же
         * урок, что с остальным, что трогает OpenCV.
         */
        @Provides
        fun straightFrame(store: ObjectStore): com.point.core.flow.StraightFrame =
            OpenCvStraightFrame(store)

        @Provides
        fun aiChatResponder(llm: com.point.core.flow.LlmClient): AiChatResponder = AiChatResponderImpl(llm)

        @Provides @ElementsIntoSet @OwnCapabilities
        fun appCapabilities(chosen: ChosenApps): Set<Capability> =
            chosen.all().map { AppCapability(it) }.toSet()

        // @Provides, а не @Binds: у реализатора, который трогает графику Android, @Binds
        // роняет разрешение типов во всём модуле KSP — тот же урок, что с OpenCV.
        @Provides @IntoSet
        fun cleanMetadataR(store: ObjectStore): Realizer = com.point.executors.CleanMetadataRealizer(store)

        @Provides @ElementsIntoSet
        fun appRealizers(chosen: ChosenApps, launcher: AppLauncher): Set<Realizer> =
            chosen.all().map { AppOpenRealizer(it, launcher) }.toSet()

        /** Свои способности — часть общего набора- отдельный набор нужен только для сверки с чужими. */
        @Provides @ElementsIntoSet
        fun ownCaps(@OwnCapabilities own: Set<@JvmSuppressWildcards Capability>): Set<Capability> = own

        @Provides @ElementsIntoSet
        fun pcRemoteCapabilities(
            @OwnCapabilities own: Set<@JvmSuppressWildcards Capability>,
            caps: com.point.core.flow.PcCapsStore,
            links: com.point.core.flow.PcLinks,
        ): Set<Capability> = remotePcCapabilities(own, caps.all(), links) {
            com.point.core.flow.capsFresh(caps.savedAt(), System.currentTimeMillis())
        }

        @Provides @ElementsIntoSet
        fun pcRemoteRealizers(
            @OwnCapabilities own: Set<@JvmSuppressWildcards Capability>,
            caps: com.point.core.flow.PcCapsStore,
            links: com.point.core.flow.PcLinks,
            transport: com.point.core.flow.PcTransport,
            store: ObjectStore,
            classifier: ObjectClassifier,
        ): Set<Realizer> = remotePcRealizers(own, caps.all(), links, transport, store, classifier)
    }
}
