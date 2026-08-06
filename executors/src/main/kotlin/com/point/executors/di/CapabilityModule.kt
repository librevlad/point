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
import com.point.executors.PdfCapability
import com.point.executors.ExtractAllCapability
import com.point.core.model.CapabilityId
import com.point.executors.AppCapability
import com.point.executors.PcCapability
import com.point.executors.PcRealizer
import com.point.executors.AppOpenRealizer
import com.point.executors.AiCapability
import com.point.executors.RemotePcCapability
import com.point.executors.WordPlusCapability
import com.point.executors.WordPlusRealizer
import com.point.executors.RemotePcRealizer
import com.point.executors.JobReplyCapability
import com.point.executors.JobReplyRealizer
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
import com.point.executors.SmsCapability
import com.point.executors.SmsRealizer
import com.point.executors.ArchiveRealizer
import com.point.executors.BlurBgCapability
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
import com.point.executors.PagesRealizer
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
import com.point.executors.ScanPlusRealizer
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
import com.point.core.flow.ObjectStore
import com.point.executors.OpenCvScanRealizer
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
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
    /** #528: реестр спрашивает «есть ли чем выполнить», не получая при этом чем выполнять. */
    @Binds abstract fun actionAvailability(impl: com.point.executors.RealizerAvailability): ActionAvailability
    @Binds abstract fun bubblePolicy(impl: LearningBubblePolicy): BubblePolicy

    // --- Capabilities (declarations) ---
    @Binds @IntoSet abstract fun dropLinkReal(r: com.point.executors.DropLinkRealizer): Realizer

    @Binds @IntoSet abstract fun pcCap(c: PcCapability): Capability
    @Binds @IntoSet abstract fun shareCap(c: ShareCapability): Capability
    @Binds @IntoSet abstract fun saveCap(c: SaveCapability): Capability
    @Binds @IntoSet abstract fun saveAllCap(c: SaveAllCapability): Capability
    @Binds @IntoSet abstract fun shareAllCap(c: ShareAllCapability): Capability
    @Binds @IntoSet abstract fun mergePdfCap(c: MergePdfCapability): Capability
    @Binds @IntoSet abstract fun scanPdfCap(c: ScanPdfCapability): Capability
    @Binds @IntoSet abstract fun openCap(c: OpenCapability): Capability
    @Binds @IntoSet abstract fun pdfCap(c: PdfCapability): Capability
    @Binds @IntoSet abstract fun extractAllCap(c: ExtractAllCapability): Capability
    @Binds @IntoSet abstract fun openInCap(c: OpenInCapability): Capability
    @Binds @IntoSet abstract fun openUrlCap(c: OpenUrlCapability): Capability
    // Entity actions (on-device detection → targeted action) — "right-click" for text.
    @Binds @IntoSet abstract fun callCap(c: CallCapability): Capability
    @Binds @IntoSet abstract fun smsCap(c: SmsCapability): Capability
    @Binds @IntoSet abstract fun emailCap(c: EmailCapability): Capability
    @Binds @IntoSet abstract fun mapCap(c: MapCapability): Capability
    @Binds @IntoSet abstract fun eventCap(c: EventCapability): Capability
    @Binds @IntoSet abstract fun copyCardCap(c: CopyCardCapability): Capability
    @Binds @IntoSet abstract fun vcardCap(c: VCardCapability): Capability
    /** #464: строка карточки готовности «Сохранить контакт» запускает вот эту возможность. */
    @Binds @IntoSet abstract fun saveContactCap(c: SaveContactCapability): Capability
    @Binds @IntoSet abstract fun copyCap(c: CopyCapability): Capability
    /** #279: искать есть где только там, где страница уже разложена по словам. */
    @Binds @IntoSet abstract fun findCap(c: FindCapability): Capability
    @Binds @IntoSet abstract fun pagesCap(c: PagesCapability): Capability
    @Binds @IntoSet abstract fun translateCap(c: TranslateCapability): Capability
    /** #223: голосовое — объект, и «Расшифровать» — его действие. Дальше текст живёт по общим
     *  правилам графа: перевести, в PDF, сохранить — всё это уже есть у TEXT. */
    @Binds @IntoSet abstract fun transcribeCap(c: TranscribeCapability): Capability
    @Binds @IntoSet abstract fun excelCap(c: ExcelCapability): Capability
    /** #224: та же таблица на следующий период. Появляется только на таблице, где прочитан
     *  календарь дат (`Feature.HAS_PERIOD`), — иначе продлевать было бы нечего. */
    @Binds @IntoSet abstract fun renewPeriodCap(c: RenewPeriodCapability): Capability
    @Binds @IntoSet abstract fun wordCap(c: WordCapability): Capability
    @Binds @IntoSet abstract fun readQrCap(c: ReadQrCapability): Capability
    @Binds @IntoSet abstract fun scanCap(c: ScanCapability): Capability
    @Binds @IntoSet abstract fun scanPlusCap(c: ScanPlusCapability): Capability
    @Binds @IntoSet abstract fun cutoutCap(c: CutoutCapability): Capability
    @Binds @IntoSet abstract fun blurBgCap(c: BlurBgCapability): Capability
    @Binds @IntoSet abstract fun replaceBgCap(c: ReplaceBgCapability): Capability
    // «Распознать текст» переехало в общий словарь (`:core:flow`) — см. companion ниже.
    @Binds @IntoSet abstract fun cloudOcrCap(c: CloudOcrCapability): Capability
    @Binds @IntoSet abstract fun aiCap(c: AiCapability): Capability
    @Binds @IntoSet abstract fun shoppingListCap(c: ShoppingListCapability): Capability
    /** #260: «Понять глубже» + «Кто есть кто» + «Собрать данные+» свёрнуты в одно «Понять». */
    @Binds @IntoSet abstract fun understandCap(c: UnderstandCapability): Capability
    @Binds @IntoSet abstract fun wordPlusCap(c: WordPlusCapability): Capability
    @Binds @IntoSet abstract fun jobReplyCap(c: JobReplyCapability): Capability

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
    @Binds @IntoSet abstract fun officeR(r: OfficeRealizer): Realizer
    @Binds @IntoSet abstract fun archiveR(r: ArchiveRealizer): Realizer
    @Binds @IntoSet abstract fun translateR(r: TranslateRealizer): Realizer
    @Binds @IntoSet abstract fun transcribeR(r: TranscribeRealizer): Realizer
    @Binds @IntoSet abstract fun excelR(r: ExcelRealizer): Realizer
    @Binds @IntoSet abstract fun renewPeriodR(r: RenewPeriodRealizer): Realizer
    @Binds @IntoSet abstract fun wordR(r: WordRealizer): Realizer
    @Binds @IntoSet abstract fun qrR(r: QrRealizer): Realizer
    @Binds @IntoSet abstract fun readQrR(r: ReadQrRealizer): Realizer
    @Binds @IntoSet abstract fun scanR(r: ScanRealizer): Realizer
    @Binds @IntoSet abstract fun cutoutR(r: CutoutRealizer): Realizer
    @Binds @IntoSet abstract fun blurBgR(r: BlurBgRealizer): Realizer
    @Binds @IntoSet abstract fun replaceBgR(r: ReplaceBgRealizer): Realizer
    // OCR has two realizers behind one capability — the Resolver ranks device before
    // cloud and chains them (device recognises nothing -> cloud). Roadmap #1 in prod.
    // Собственного чтения табло у Point больше нет (#396, кнопка убрана владельцем), а эта
    // цепочка приборы не читает: внешний глаз на трёх настоящих кадрах даёт ноль из трёх.
    // Путь от фотографии прибора к показанию заведён отдельно — #426 (вырезанный барабан).
    @Binds @IntoSet abstract fun deviceOcrR(r: DeviceOcrRealizer): Realizer
    @Binds @IntoSet abstract fun cloudOcrR(r: CloudOcrRealizer): Realizer

    /** Внешний глаз (#280): в «Распознать текст» — между устройством и общей цепочкой моделей,
     *  в «Распознать в облаке» — первым. Порядок задан `RealizerMeta.priority`, UI не меняется. */
    @Binds @IntoSet abstract fun externalEyeOcrR(r: ExternalEyeOcrRealizer): Realizer
    @Binds @IntoSet abstract fun externalEyeCloudOcrR(r: ExternalEyeCloudOcrRealizer): Realizer
    @Binds @IntoSet abstract fun cloudOcrDirectR(r: CloudOcrDirectRealizer): Realizer
    @Binds @IntoSet abstract fun aiR(r: AiRealizer): Realizer
    @Binds @IntoSet abstract fun shoppingListR(r: ShoppingListRealizer): Realizer
    @Binds @IntoSet abstract fun understandR(r: UnderstandRealizer): Realizer
    @Binds @IntoSet abstract fun wordPlusR(r: WordPlusRealizer): Realizer
    @Binds @IntoSet abstract fun jobReplyR(r: JobReplyRealizer): Realizer
    @Binds @IntoSet abstract fun pcR(r: PcRealizer): Realizer

    companion object {
        /**
         * Общий словарь намерений (`:core:flow.capabilities`) — контракт от 06.08.2026, И1:
         * `Capability` не принадлежит устройству.
         *
         * Одна привязка на весь словарь, а не по одной на способность: следующая переезжающая
         * способность добавляется строкой в `sharedCapabilities()` и появляется здесь сама. Место,
         * которое пришлось бы дописывать на каждую, однажды забыли бы дописать.
         *
         * `@Provides`, а не `@Binds`, по простой причине: у общей декларации нет и не будет
         * `@Inject`. Ядро Point не имеет сторонних зависимостей, и `javax.inject` туда не поедет
         * ради переезда деклараций — связывание остаётся заботой того, у кого DI есть.
         */
        @Provides @ElementsIntoSet
        fun sharedCaps(): Set<Capability> =
            com.point.core.flow.capabilities.sharedCapabilities().toSet()

        // @Provides (not @Binds) keeps the concrete OpenCV realizer out of the binding
        // signature, so Dagger's KSP aggregation never has to resolve the native OpenCV AAR
        // types (which it can't, even though kotlinc can) — the pack still lands @IntoSet (#45).
        @Provides @IntoSet
        fun openCvScanR(store: ObjectStore): Realizer = OpenCvScanRealizer(store)

        // «Скан+» (#200): same @Provides trick — the realizer touches native OpenCV, so keep it out of
        // the @Binds signature KSP aggregates. Was missing → capability appeared but «no realizer».
        @Provides @IntoSet
        fun scanPlusR(store: ObjectStore): Realizer = ScanPlusRealizer(store)

        // #4: same trick for the AI chat — construct the impl manually from its LlmClient dep so
        // the concrete class stays out of the binding graph KSP aggregates.
        @Provides
        fun aiChatResponder(llm: com.point.core.flow.LlmClient): AiChatResponder = AiChatResponderImpl(llm)

        // #66 slice 4: remembered app picks join the SAME graph as built-in actions —
        // a capability+realizer pair per pick, synthesised once at process start
        // (a fresh pick appears on the next launch; ChosenApps.all() is warm and I/O-free).
        @Provides @ElementsIntoSet
        fun appCapabilities(chosen: ChosenApps): Set<Capability> =
            chosen.all().map { AppCapability(it) }.toSet()

        @Provides @ElementsIntoSet
        fun appRealizers(chosen: ChosenApps, launcher: AppLauncher): Set<Realizer> =
            chosen.all().map { AppOpenRealizer(it, launcher) }.toSet()

        // #80: the paired PC's advertised actions join the SAME graph — one pair per
        // cached advertisement (refreshed on pairing; visible from the next launch).
        //
        // Намерение из общего словаря отсюда НЕ синтезируется (контракт 06.08.2026, И1 и И2):
        // декларация у него одна, и вторая — «Распознать текст на ПК» рядом с «Распознать текст» —
        // была бы предложением выбрать устройство. Реализация компьютера при этом никуда не
        // девается: она приходит [pcRemoteRealizers] и встаёт кандидатом к той же способности,
        // между которыми и выбирает `Resolver`.
        //
        // Список сжимается сам по мере переезда способностей в общий словарь и однажды опустеет
        // вместе с `RemotePcCapability`.
        @Provides @ElementsIntoSet
        fun pcRemoteCapabilities(caps: com.point.core.flow.PcCapsStore, links: com.point.core.flow.PcLinks): Set<Capability> =
            caps.all()
                .filterNot { CapabilityId(it.id) in com.point.core.flow.capabilities.sharedCapabilityIds }
                .map { RemotePcCapability(it, links) }
                .toSet()

        @Provides @ElementsIntoSet
        fun pcRemoteRealizers(
            caps: com.point.core.flow.PcCapsStore,
            links: com.point.core.flow.PcLinks,
            transport: com.point.core.flow.PcTransport,
        ): Set<Realizer> = caps.all().map { RemotePcRealizer(it, links, transport) }.toSet()
    }
}
