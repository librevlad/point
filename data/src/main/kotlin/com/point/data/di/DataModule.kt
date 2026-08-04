package com.point.data.di

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.point.core.flow.AppLauncher
import com.point.core.flow.BackgroundRemover
import com.point.core.flow.CalendarInserter
import com.point.core.flow.CapabilityUsage
import com.point.core.flow.Clipboard
import com.point.core.flow.Enricher
import com.point.core.flow.Enrichment
import com.point.core.flow.EntityExtractor
import com.point.core.flow.DocxWriter
import com.point.core.flow.Entitlements
import com.point.core.flow.EvidenceCropper
import com.point.core.flow.Exporter
import com.point.core.flow.FavoritesStore
import com.point.core.flow.HistoryStore
import com.point.core.flow.ImageCompositor
import com.point.core.flow.LlmClient
import com.point.core.flow.ArchiveExtractor
import com.point.core.flow.ObjectClassifier
import com.point.core.flow.ObjectStore
import com.point.core.flow.OfficeTextExtractor
import com.point.core.flow.PdfRasterizer
import com.point.core.flow.PdfTextExtractor
import com.point.core.flow.PrivacyConsent
import com.point.core.flow.SensoryFeedback
import com.point.core.flow.SensorySettings
import com.point.core.flow.FlowSnapshotStore
import com.point.core.flow.CrashLog
import com.point.core.flow.PinnedActions
import com.point.core.flow.QrEncoder
import com.point.core.flow.QrReader
import com.point.core.flow.Sharer
import com.point.core.flow.SpreadsheetReader
import com.point.core.flow.SpreadsheetWriter
import com.point.core.flow.AtomRecognizer
import com.point.core.flow.CloudPrivacySettings
import com.point.core.flow.ExternalEye
import com.point.core.flow.SpeechToText
import com.point.core.flow.TextRecognizer
import com.point.core.flow.UrlOpener
import com.point.core.flow.ChosenApps
import com.point.core.flow.PcDiscovery
import com.point.core.flow.Basket
import com.point.core.flow.PcCapsStore
import com.point.core.flow.PcPairings
import com.point.core.flow.PcTransport
import com.point.core.flow.UsageJournal
import com.point.core.flow.UserKeyStore
import com.point.core.flow.Viewer
import com.point.data.AndroidAppLauncher
import com.point.data.AndroidCalendarInserter
import com.point.data.AndroidClipboard
import com.point.data.AndroidImageCompositor
import com.point.data.AndroidSharer
import com.point.data.AndroidUrlOpener
import com.point.data.AndroidViewer
import com.point.data.BitmapEvidenceCropper
import com.point.data.ClaudeLlmClient
import com.point.data.CommonsArchiveExtractor
import com.point.data.DocumentTypeEnricher
import com.point.data.GraphMetadataEnricher
import com.point.data.DefaultEnrichment
import com.point.data.DefaultEntitlements
import com.point.data.EntityEnricher
import com.point.data.IdentifierEnricher
import com.point.data.MlKitEntityExtractor
import com.point.data.FallbackLlmClient
import com.point.data.FileChosenApps
import com.point.data.AndroidPcDiscovery
import com.point.data.FileBasket
import com.point.data.FilePcCaps
import com.point.data.FilePcPairings
import com.point.data.HttpPcClipboardSync
import com.point.data.HttpUrlPcTransport
import com.point.data.LanThenRelayClipboardSync
import com.point.data.LanThenRelayTransport
import com.point.data.RelayPcClipboardSync
import com.point.data.RelayPcTransport
import com.point.data.SelfHealingPcTransport
import com.point.data.FileUsageJournal
import com.point.data.FileCapabilityUsage
import com.point.data.FileFavoritesStore
import com.point.data.FileHistoryStore
import com.point.data.GeminiLlmClient
import com.point.data.HttpJson
import com.point.data.UrlConnectionHttpJson
import com.point.data.HttpFiles
import com.point.data.UrlConnectionHttpFiles
import com.point.data.OutboundFrames
import com.point.data.BitmapOutboundFrames
import com.point.data.CloudAtomRecognizer
import com.point.data.LlamaParseAtomRecognizer
import com.point.data.UnstructuredAtomRecognizer
import com.point.data.CloudTextReader
import com.point.data.DefaultExternalEye
import com.point.data.MistralOcrReader
import com.point.data.OvhVisionReader
import com.point.data.PrefsCloudPrivacySettings
import com.point.data.MediaStoreExporter
import com.point.data.MetadataEntityEnricher
import com.point.data.MlKitBackgroundRemover
import com.point.data.MlKitQrReader
import com.point.data.OcrEnricher
import com.point.data.OoxmlDocxWriter
import com.point.data.OoxmlOfficeTextExtractor
import com.point.data.OoxmlSpreadsheetReader
import com.point.data.OoxmlSpreadsheetWriter
import com.point.data.BuildConfig
import com.point.data.OpenAiCompatibleClient
import com.point.data.OpenAiProvider
import com.point.data.configured
import com.point.data.openAiModels
import com.point.data.PdfBoxTextExtractor
import com.point.data.PdfImageEnricher
import com.point.data.PeriodEnricher
import com.point.data.PrefsPrivacyConsent
import com.point.data.PrefsSensorySettings
import com.point.data.FileFlowSnapshotStore
import com.point.data.FileCrashLog
import com.point.data.PrefsPinnedActions
import com.point.data.VibratorSensoryFeedback
import com.point.data.PrefsUserKeyStore
import com.point.data.QrEnricher
import com.point.data.UserKeyLlmClient
import com.point.data.PdfRendererRasterizer
import com.point.data.ScratchObjectStore
import com.point.data.LlmSpeechToText
import com.point.data.TesseractTextRecognizer
import com.point.data.PcPairingEnricher
import com.point.data.TextUrlEnricher
import com.point.data.VCardEnricher
import com.point.data.ZxingQrEncoder
import com.point.data.ZxingQrReader
import com.point.data.ZipImagesEnricher
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    abstract fun objectStore(impl: ScratchObjectStore): ObjectStore

    @Binds
    abstract fun sharer(impl: AndroidSharer): Sharer

    @Binds
    abstract fun exporter(impl: MediaStoreExporter): Exporter

    /** The public LlmClient is the fallback chain (Gemini -> OpenAI-compatible). */
    @Binds
    abstract fun llmClient(impl: FallbackLlmClient): LlmClient

    @Binds
    abstract fun urlOpener(impl: AndroidUrlOpener): UrlOpener

    @Binds
    abstract fun calendarInserter(impl: AndroidCalendarInserter): CalendarInserter

    @Binds
    abstract fun clipboard(impl: AndroidClipboard): Clipboard

    @Binds
    abstract fun viewer(impl: AndroidViewer): Viewer

    /** Installed apps that can handle an object (#66 device actions). */
    @Binds
    abstract fun appLauncher(impl: AndroidAppLauncher): AppLauncher

    @Binds
    abstract fun enrichment(impl: DefaultEnrichment): Enrichment

    @Binds
    abstract fun entitlements(impl: DefaultEntitlements): Entitlements

    @Binds
    abstract fun pdfTextExtractor(impl: PdfBoxTextExtractor): PdfTextExtractor

    @Binds
    abstract fun pdfRasterizer(impl: PdfRendererRasterizer): PdfRasterizer

    @Binds
    abstract fun spreadsheetWriter(impl: OoxmlSpreadsheetWriter): SpreadsheetWriter

    @Binds
    abstract fun spreadsheetReader(impl: OoxmlSpreadsheetReader): SpreadsheetReader

    @Binds
    abstract fun docxWriter(impl: OoxmlDocxWriter): DocxWriter

    /** Кроп-улика к спорному фрагменту (#267): режет Android-декодер, поэтому реализация в :data. */
    @Binds
    abstract fun evidenceCropper(impl: BitmapEvidenceCropper): EvidenceCropper

    @Binds
    abstract fun qrEncoder(impl: ZxingQrEncoder): QrEncoder

    @Binds
    abstract fun imageCompositor(impl: AndroidImageCompositor): ImageCompositor

    @Binds
    abstract fun officeTextExtractor(impl: OoxmlOfficeTextExtractor): OfficeTextExtractor

    @Binds
    abstract fun archiveExtractor(impl: CommonsArchiveExtractor): ArchiveExtractor

    @Binds
    abstract fun historyStore(impl: FileHistoryStore): HistoryStore

    @Binds
    abstract fun favoritesStore(impl: FileFavoritesStore): FavoritesStore

    @Binds
    abstract fun capabilityUsage(impl: FileCapabilityUsage): CapabilityUsage

    @Binds
    abstract fun textRecognizer(impl: TesseractTextRecognizer): TextRecognizer

    /**
     * Расшифровка голосового (#223). Реализация облачная, и это не временная заглушка, а
     * измеренный факт: системного распознавания **из файла** в Android нет — `SpeechRecognizer`
     * слушает микрофон. Шов настоящий: офлайновый движок встанет сюда одной строкой.
     */
    @Binds
    abstract fun speechToText(impl: LlmSpeechToText): SpeechToText

    /**
     * Геометрия доходит до пайплайна (#257): OcrEnricher читает слой, а не плоскую строку.
     *
     * По умолчанию ридер **офлайновый и остаётся таким**. Облачное второе чтение (#280) живёт
     * своим типом ([com.point.data.FallbackAtomRecognizer]) и сюда не подставляется: фоновое
     * обогащение обязано укладываться в первый экран и не имеет права ходить в сеть.
     */
    @Binds
    abstract fun atomRecognizer(impl: TesseractTextRecognizer): AtomRecognizer

    /** The one real HTTP transport; LLM clients depend on the [HttpJson] interface. */
    @Binds
    abstract fun httpJson(impl: UrlConnectionHttpJson): HttpJson

    /** Загрузка файла формой + опрос задачи (#280) — второй сетевой шов рядом с [HttpJson]. */
    @Binds
    abstract fun httpFiles(impl: UrlConnectionHttpFiles): HttpFiles

    /** Подготовка кадра к отправке наружу: EXIF-выпрямленная копия + её преобразование в сырой кадр. */
    @Binds
    abstract fun outboundFrames(impl: BitmapOutboundFrames): OutboundFrames

    /**
     * Внешний глаз (#280) — цепочка чужих сервисов, читающих страницу целиком.
     *
     * Отдельно от [LlmClient] сознательно: специальная OCR-ручка промпта не принимает, и попади
     * она в общую цепочку моделей, «Понять» на снимке молча вернуло бы расшифровку вместо ответа.
     */
    @Binds
    abstract fun externalEye(impl: DefaultExternalEye): ExternalEye

    /** «Куда можно отправлять» — выбор человека, с умолчанием «максимум бесплатного». */
    @Binds
    abstract fun cloudPrivacy(impl: PrefsCloudPrivacySettings): CloudPrivacySettings

    /** The user's own AI key (BYO), stored on-device. */
    @Binds
    abstract fun userKeyStore(impl: PrefsUserKeyStore): UserKeyStore

    /** Private, consent-gated usage journal (North Star measurement). */
    @Binds
    abstract fun usageJournal(impl: FileUsageJournal): UsageJournal

    /** Remembered app picks — the seed for per-app capabilities (#66 slice 4). */
    @Binds
    abstract fun chosenApps(impl: FileChosenApps): ChosenApps

    /** The paired PC (#147) and the LAN transport to it. */
    @Binds
    abstract fun pcPairings(impl: FilePcPairings): PcPairings

    @Binds
    abstract fun basket(impl: FileBasket): Basket

    @Binds
    abstract fun pcCaps(impl: FilePcCaps): PcCapsStore

    /** LAN autodiscovery of Point-for-PC (#147 slice C) — sugar over manual entry. */
    @Binds
    abstract fun pcDiscovery(impl: AndroidPcDiscovery): PcDiscovery

    /** Consent to send objects to a cloud service (#10). */
    @Binds
    abstract fun privacyConsent(impl: PrefsPrivacyConsent): PrivacyConsent

    /** Hand-feel of the flow (MOTION.md M4) — predefined haptics behind the seam. */
    @Binds
    abstract fun sensoryFeedback(impl: VibratorSensoryFeedback): SensoryFeedback

    @Binds
    abstract fun sensorySettings(impl: PrefsSensorySettings): SensorySettings

    /** Crash-proof journey journal (#7). */
    @Binds
    abstract fun flowSnapshotStore(impl: FileFlowSnapshotStore): FlowSnapshotStore

    /** Local, consent-to-share crash visibility (#11). */
    @Binds
    abstract fun crashLog(impl: FileCrashLog): CrashLog

    /** User rules (#66): one pinned action per object kind. */
    @Binds
    abstract fun pinnedActions(impl: PrefsPinnedActions): PinnedActions

    @Binds @IntoSet
    abstract fun textUrlEnricher(e: TextUrlEnricher): Enricher

    /** `point-pc://` text → «Подключить компьютер» (#147, camera-free pairing). */
    @Binds
    @IntoSet
    abstract fun pcPairingEnricher(e: PcPairingEnricher): Enricher

    @Binds @IntoSet
    abstract fun entityEnricher(e: EntityEnricher): Enricher

    @Binds @IntoSet
    abstract fun vcardEnricher(e: VCardEnricher): Enricher

    @Binds @IntoSet
    abstract fun qrEnricher(e: QrEnricher): Enricher

    @Binds @IntoSet
    abstract fun ocrEnricher(e: OcrEnricher): Enricher

    @Binds @IntoSet
    abstract fun metadataEntityEnricher(e: MetadataEntityEnricher): Enricher

    @Binds @IntoSet
    abstract fun zipImagesEnricher(e: ZipImagesEnricher): Enricher

    @Binds @IntoSet
    abstract fun pdfImageEnricher(e: PdfImageEnricher): Enricher

    /** First extractor of the object pipeline (#222): a waybill number becomes an object,
     *  not a flag. On-device rule, no key and no signal needed. */
    @Binds @IntoSet
    abstract fun identifierEnricher(e: IdentifierEnricher): Enricher

    /** Names the object after what it IS (#222, шаг 5): «Посылка», не «Изображение».
     *  On-device rule — an object's own name must not depend on a quota. */
    @Binds @IntoSet
    abstract fun documentTypeEnricher(e: DocumentTypeEnricher): Enricher

    /** Rebuilds classified roles into graph nodes (#222, шаг 6) — instant, from journaled
     *  metadata, so the paid classification is paid for once. */
    @Binds @IntoSet
    abstract fun graphMetadataEnricher(e: GraphMetadataEnricher): Enricher

    /** Якорь «На новый период» (#224): в таблице прочитан календарь дат. Правило на устройстве,
     *  после первого экрана — заглянуть внутрь файла иначе нечем. */
    @Binds @IntoSet
    abstract fun periodEnricher(e: PeriodEnricher): Enricher

    companion object {
        /** Pure classifier lives in :core:flow (no DI annotations there). */
        @Provides
        fun objectClassifier(): ObjectClassifier = ObjectClassifier()

        /**
         * «Дать ссылку» (#388): файл кладётся на релей под неугадываемым адресом и живёт сутки.
         *
         * Единственное, что релей возит НЕ запечатанным: у чужого человека, который откроет
         * ссылку, ключа нет. Цена названа в самом действии, чтобы человек знал, что отдаёт.
         */
        @Provides
        fun dropLink(): com.point.core.flow.DropLink =
            com.point.data.RelayDropLink(BuildConfig.RELAY_URL, BuildConfig.RELAY_APP_SECRET)

        /**
         * «Принять файл» (#388) — та же ссылка в обратную сторону: чужой человек кладёт файл в
         * ящик на релее, телефон забирает его обычным путём. Плата та же: этот файл релей видит.
         */
        @Provides
        fun dropInbox(): com.point.core.flow.DropInbox =
            com.point.data.RelayDropInbox(BuildConfig.RELAY_URL, BuildConfig.RELAY_APP_SECRET)

        /** #161 v2 «железобетонно»: the LAN transport self-heals a stale PC IP via mDNS (re-resolve +
         *  retry with the token), and when it still can't be reached — different network, LTE — the
         *  object falls back to the always-works relay (outbound-only, E2E-encrypted). */
        @Provides
        fun pcTransport(
            http: HttpUrlPcTransport,
            discovery: PcDiscovery,
            pairings: PcPairings,
            monitor: com.point.core.flow.LinkMonitor,
        ): PcTransport = LanThenRelayTransport(
            lan = SelfHealingPcTransport(http, discovery, pairings),
            relay = RelayPcTransport(BuildConfig.RELAY_APP_SECRET),
            monitor = monitor,
        )

        /** Кто помнит последний контакт с компьютером (#412) — один на приложение: экран и
         *  транспорт обязаны говорить об одном и том же. */
        @Provides
        @Singleton
        fun linkMonitor(): com.point.core.flow.LinkMonitor = com.point.core.flow.InMemoryLinkMonitor()

        /** Shared clipboard (#161 «общий буфер»): LAN hop first, relay fallback when off-network —
         *  same «безотказно» shape as [pcTransport]. */
        @Provides
        fun pcClipboardSync(http: HttpPcClipboardSync): com.point.core.flow.PcClipboardSync =
            LanThenRelayClipboardSync(
                lan = http,
                relay = RelayPcClipboardSync(BuildConfig.RELAY_APP_SECRET),
            )

        /** On-device entity detection (ML Kit) behind the [EntityExtractor] seam. @Provides (not
         *  @Binds) keeps the ML Kit AAR types out of Dagger's KSP aggregation (same fix as OpenCV). */
        @Provides
        @Singleton
        fun entityExtractor(): EntityExtractor = MlKitEntityExtractor()

        /** On-device subject cutout (ML Kit). @Provides (not @Binds) keeps the ML Kit AAR types out
         *  of Dagger's KSP aggregation (same fix as OpenCV / the entity extractor). */
        @Provides
        @Singleton
        fun backgroundRemover(store: ObjectStore): BackgroundRemover = MlKitBackgroundRemover(store)

        /** QR read: ML Kit Barcode (robust on photos) with ZXing as fallback. @Provides keeps the
         *  ML Kit AAR types out of KSP aggregation, same as the others. */
        @Provides
        @Singleton
        fun qrReader(): QrReader = MlKitQrReader(ZxingQrReader())

        /**
         * The AI fallback chain — "all free providers, max": every OpenAI-compatible
         * free provider first (vision-capable ones lead, so "Понять" on a photo works),
         * then the native providers. Each is included only if its key is set, so the
         * chain self-activates as keys land in local.properties. Gemini is intentionally
         * last — it rate-limits hard (HTTP 429), which is the whole reason for #32.
         */
        @Provides
        fun llmProviders(
            http: HttpJson,
            store: ObjectStore,
            userKey: UserKeyLlmClient,
            gemini: GeminiLlmClient,
            claude: ClaudeLlmClient,
        ): List<@JvmSuppressWildcards LlmClient> {
            val free = openAiProviders().configured().map { OpenAiCompatibleClient(http, store, it) }
            val native = buildList {
                if (BuildConfig.GEMINI_API_KEY.isNotBlank()) add(gemini)
                if (BuildConfig.ANTHROPIC_API_KEY.isNotBlank()) add(claude)
            }
            // The user's own key leads the chain; when unset it steps aside to the built-ins.
            return listOf<LlmClient>(userKey) + free + native
        }

        /**
         * Бесплатные читатели страницы (#280), в порядке очереди: Unstructured (15 000
         * страниц/мес) → LlamaParse (10 000 кредитов/мес). Каждый попадает в цепочку, только
         * если его ключ задан, — поэтому в раздаваемой релизной сборке список **пуст**, и
         * облачного чтения там просто нет.
         *
         * Список не биндится на `AtomRecognizer`: подменять им ридер по умолчанию нельзя, иначе
         * фоновое обогащение ушло бы в сеть на первом же экране.
         */
        @Provides
        fun cloudAtomRecognizers(
            http: HttpFiles,
            frames: OutboundFrames,
        ): List<@JvmSuppressWildcards CloudAtomRecognizer> = listOf(
            UnstructuredAtomRecognizer(
                http, frames,
                BuildConfig.UNSTRUCTURED_API_KEY,
                BuildConfig.UNSTRUCTURED_API_URL,
            ),
            LlamaParseAtomRecognizer(
                http, frames,
                BuildConfig.LLAMA_CLOUD_API_KEY,
                BuildConfig.LLAMA_CLOUD_BASE_URL,
                BuildConfig.LLAMA_CLOUD_TIER,
            ),
        ).filter { it.configured }

        /**
         * Внешний глаз (#280) — цепочка в порядке **измеренной эффективности бесплатного**, а не
         * по приватности: 1) Mistral OCR (24/24 дословно на всех порчах кадра, 1,3–5 с),
         * 2) OVH Qwen2.5-VL (15/15 на кириллице, отдаётся без ключа и регистрации).
         *
         * Приватность отсюда никого больше не выбрасывает — она столбец, который человек видит, и
         * настройка, которой он управляет ([CloudPrivacySettings]). Решение владельца 04.08.2026,
         * разбор — в `DECISIONS.md`.
         *
         * Ненастроенные отсеиваются в самой цепочке, а не здесь: список должен уметь ответить
         * «ключей нет вовсе» отдельно от «на этом уровне никого не пускают», а отфильтрованный
         * заранее список эти два случая уже не различает.
         */
        @Provides
        fun cloudTextReaders(
            http: HttpJson,
            frames: OutboundFrames,
        ): List<@JvmSuppressWildcards CloudTextReader> = listOf(
            MistralOcrReader(http, frames, BuildConfig.MISTRAL_API_KEY, BuildConfig.MISTRAL_BASE_URL),
            OvhVisionReader(
                http, frames,
                BuildConfig.OVH_API_KEY,
                BuildConfig.OVH_BASE_URL,
                BuildConfig.OVH_MODEL,
            ),
        )

        /** Gemini is built here (not @Inject) so its key + model list — from BuildConfig —
         *  are constructor-injected, keeping the client itself BuildConfig-free and testable. */
        @Provides
        fun geminiClient(http: HttpJson, store: ObjectStore): GeminiLlmClient =
            GeminiLlmClient(
                http,
                store,
                BuildConfig.GEMINI_API_KEY,
                BuildConfig.GEMINI_MODELS.split(',').map(String::trim).filter(String::isNotBlank),
            )

        /** Known OpenAI-compatible endpoints, each expanded into one entry per model in
         *  its comma-separated *_MODELS list; [configured] drops the ones without a key. */
        private fun openAiProviders(): List<OpenAiProvider> =
            openAiModels("openrouter", BuildConfig.OPENROUTER_BASE_URL, BuildConfig.OPENROUTER_API_KEY, BuildConfig.OPENROUTER_MODELS) +
                openAiModels("github", BuildConfig.GITHUB_BASE_URL, BuildConfig.GITHUB_API_KEY, BuildConfig.GITHUB_MODELS) +
                openAiModels("groq", BuildConfig.GROQ_BASE_URL, BuildConfig.GROQ_API_KEY, BuildConfig.GROQ_MODELS) +
                openAiModels("mistral", BuildConfig.MISTRAL_BASE_URL, BuildConfig.MISTRAL_API_KEY, BuildConfig.MISTRAL_MODELS) +
                openAiModels("cerebras", BuildConfig.CEREBRAS_BASE_URL, BuildConfig.CEREBRAS_API_KEY, BuildConfig.CEREBRAS_MODELS) +
                openAiModels("openai", BuildConfig.OPENAI_BASE_URL, BuildConfig.OPENAI_API_KEY, BuildConfig.OPENAI_MODELS)

        @Provides
        @HistoryDir
        fun historyDir(@ApplicationContext context: Context): java.io.File =
            java.io.File(context.filesDir, "history")

        @Provides
        @FlowSnapshotFile
        fun flowSnapshotFile(@ApplicationContext context: Context): java.io.File =
            java.io.File(context.filesDir, "flow-snapshot.json")

        /** The one injectable IO dispatcher — swapped for the test dispatcher in JVM tests,
         *  so no real thread ever outlives a test (#11 flake root-cause). */
        @Provides
        fun ioDispatcher(): kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO

        @Provides
        @CrashLogFile
        fun crashLogFile(@ApplicationContext context: Context): java.io.File =
            java.io.File(context.filesDir, "last-crash.txt")

        @Provides
        @FavoritesDir
        fun favoritesDir(@ApplicationContext context: Context): java.io.File =
            java.io.File(context.filesDir, "favorites")

        @Provides
        @UsageDir
        fun usageDir(@ApplicationContext context: Context): java.io.File =
            java.io.File(context.filesDir, "usage")
    }
}
