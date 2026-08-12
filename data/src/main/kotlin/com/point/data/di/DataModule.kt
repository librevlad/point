package com.point.data.di

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.point.core.flow.AiFacts
import com.point.core.flow.AiKeyCheck
import com.point.core.flow.AiReadiness
import com.point.core.flow.BuiltInAiKeys
import com.point.core.flow.AppLauncher
import com.point.core.flow.BackgroundRemover
import com.point.core.flow.CalendarInserter
import com.point.core.flow.CapabilityUsage
import com.point.core.flow.Clipboard
import com.point.core.flow.ContactInserter
import com.point.core.flow.Capability
import com.point.core.flow.Realizer
import com.point.core.flow.Enrichment
import com.point.core.flow.EntityExtractor
import com.point.core.flow.DocxWriter
import com.point.core.flow.Entitlements
import com.point.core.flow.EvidenceCropper
import com.point.core.flow.Exporter
import com.point.core.flow.FirstHeardSpeechToText
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
import com.point.core.flow.NetworkAvailability
import com.point.core.flow.GROQ_PROVIDER_ID
import com.point.core.flow.MISTRAL_PROVIDER_ID
import com.point.core.flow.SpeechReadiness
import com.point.core.flow.SpeechToText
import com.point.core.flow.TextRecognizer
import com.point.core.flow.speechKeyNeeds
import com.point.core.flow.UrlOpener
import com.point.core.flow.ChosenApps
import com.point.core.flow.PcCapsStore
import com.point.core.flow.PcLinks
import com.point.core.flow.PcTransport
import com.point.core.flow.UserKeyStore
import com.point.core.flow.Viewer
import com.point.data.AndroidAppLauncher
import com.point.data.AndroidCalendarInserter
import com.point.data.AndroidClipboard
import com.point.data.AndroidContactInserter
import com.point.data.AndroidImageCompositor
import com.point.data.AndroidImageRedactor
import com.point.data.AndroidNetworkAvailability
import com.point.data.AndroidSharer
import com.point.data.AndroidUrlOpener
import com.point.data.AndroidViewer
import com.point.data.BitmapEvidenceCropper
import com.point.core.flow.ClaudeLlmClient
import com.point.data.CommonsArchiveExtractor
import com.point.data.DocumentTypeInvestigation
import com.point.data.DocumentTypeInvestigationRealizer
import com.point.data.GraphRolesInvestigation
import com.point.data.GraphRolesInvestigationRealizer
import com.point.data.DefaultEnrichment
import com.point.data.DefaultEntitlements
import com.point.data.EntityInvestigation
import com.point.data.EntityInvestigationRealizer
import com.point.data.IdentifierInvestigation
import com.point.data.IdentifierInvestigationRealizer
import com.point.data.MlKitEntityExtractor
import com.point.core.flow.FallbackLlmClient
import com.point.data.FileChosenApps
import com.point.data.FilePcCaps
import com.point.data.FilePcLinks
import com.point.core.flow.HttpAiKeyCheck
import com.point.data.FileCapabilityUsage
import com.point.data.FileHistoryStore
import com.point.core.flow.GeminiLlmClient
import com.point.data.GroqWhisperSpeechToText
import com.point.data.SummarizingSpeechToText
import com.point.core.flow.HttpJson
import com.point.core.flow.UrlConnectionHttpJson
import com.point.core.flow.HttpFiles
import com.point.core.flow.UrlConnectionHttpFiles
import com.point.data.OutboundFrames
import com.point.data.BitmapOutboundFrames
import com.point.data.CloudAtomRecognizer
import com.point.data.LlamaParseAtomRecognizer
import com.point.data.UnstructuredAtomRecognizer
import com.point.data.CloudTextReader
import com.point.data.DefaultExternalEye
import com.point.data.MistralOcrReader
import com.point.data.OcrSpaceReader
import com.point.data.OvhVisionReader
import com.point.data.PrefsCloudPrivacySettings
import com.point.data.MediaStoreExporter
import com.point.data.MetadataEntityInvestigation
import com.point.data.MetadataEntityInvestigationRealizer
import com.point.data.MlKitBackgroundRemover
import com.point.data.MlKitQrReader
import com.point.data.OcrInvestigation
import com.point.data.OcrInvestigationRealizer
import com.point.data.OoxmlDocxWriter
import com.point.data.OoxmlOfficeTextExtractor
import com.point.data.OoxmlSpreadsheetReader
import com.point.data.OoxmlSpreadsheetWriter
import com.point.data.BuildConfig
import com.point.core.flow.OpenAiCompatibleClient
import com.point.core.flow.OpenAiProvider
import com.point.core.flow.configured
import com.point.core.flow.openAiModels
import com.point.data.PdfBoxTextExtractor
import com.point.data.PdfImageInvestigation
import com.point.data.PdfImageInvestigationRealizer
import com.point.data.PeriodInvestigation
import com.point.data.PeriodInvestigationRealizer
import com.point.data.PrefsPrivacyConsent
import com.point.data.PrefsSensorySettings
import com.point.data.FileFlowSnapshotStore
import com.point.data.FileCrashLog
import com.point.data.PrefsPinnedActions
import com.point.data.VibratorSensoryFeedback
import com.point.data.PrefsAiFacts
import com.point.data.PrefsUserKeyStore
import com.point.data.BuildConfigAiKeys
import com.point.data.ExifInvestigation
import com.point.data.ExifInvestigationRealizer
import com.point.data.QrInvestigation
import com.point.data.QrInvestigationRealizer
import com.point.core.flow.UserKeyLlmClient
import com.point.data.PdfRendererRasterizer
import com.point.data.ScratchObjectStore
import com.point.data.LlmSpeechToText
import com.point.data.TesseractTextRecognizer
import com.point.data.TextUrlInvestigation
import com.point.data.TextUrlInvestigationRealizer
import com.point.data.VCardInvestigation
import com.point.data.VCardInvestigationRealizer
import com.point.data.ZxingQrEncoder
import com.point.data.ZxingQrReader
import com.point.data.ZipImagesInvestigation
import com.point.data.ZipImagesInvestigationRealizer
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

    @Binds
    abstract fun urlOpener(impl: AndroidUrlOpener): UrlOpener

    @Binds
    abstract fun calendarInserter(impl: AndroidCalendarInserter): CalendarInserter

    @Binds
    abstract fun contactInserter(impl: AndroidContactInserter): ContactInserter

    @Binds
    abstract fun clipboard(impl: AndroidClipboard): Clipboard

    @Binds
    abstract fun viewer(impl: AndroidViewer): Viewer

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

    @Binds
    abstract fun evidenceCropper(impl: BitmapEvidenceCropper): EvidenceCropper

    @Binds
    abstract fun qrEncoder(impl: ZxingQrEncoder): QrEncoder

    @Binds
    abstract fun imageCompositor(impl: AndroidImageCompositor): ImageCompositor

    @Binds
    abstract fun imageRedactor(impl: AndroidImageRedactor): com.point.core.flow.ImageRedactor

    @Binds
    abstract fun officeTextExtractor(impl: OoxmlOfficeTextExtractor): OfficeTextExtractor

    @Binds
    abstract fun archiveExtractor(impl: CommonsArchiveExtractor): ArchiveExtractor

    @Binds
    abstract fun historyStore(impl: FileHistoryStore): HistoryStore

    @Binds
    abstract fun capabilityUsage(impl: FileCapabilityUsage): CapabilityUsage

    @Binds
    abstract fun textRecognizer(impl: TesseractTextRecognizer): TextRecognizer

    @Binds
    abstract fun httpJsonImpl(impl: UrlConnectionHttpJson): HttpJson

    @Binds
    abstract fun httpFilesImpl(impl: UrlConnectionHttpFiles): HttpFiles

    @Binds
    abstract fun outboundFrames(impl: BitmapOutboundFrames): OutboundFrames

    @Binds
    abstract fun externalEye(impl: DefaultExternalEye): ExternalEye

    @Binds
    abstract fun cloudPrivacy(impl: PrefsCloudPrivacySettings): CloudPrivacySettings

    @Binds
    @Singleton
    abstract fun networkAvailability(impl: AndroidNetworkAvailability): NetworkAvailability

    @Binds
    abstract fun userKeyStore(impl: PrefsUserKeyStore): UserKeyStore

    @Binds
    @Singleton
    abstract fun aiFacts(impl: PrefsAiFacts): AiFacts

    @Binds
    abstract fun builtInAiKeys(impl: BuildConfigAiKeys): BuiltInAiKeys

    @Binds
    abstract fun aiKeyCheckImpl(impl: HttpAiKeyCheck): AiKeyCheck

    @Binds
    abstract fun chosenApps(impl: FileChosenApps): ChosenApps

    @Binds
    abstract fun pcLinks(impl: FilePcLinks): PcLinks

    @Binds
    abstract fun pcCaps(impl: FilePcCaps): PcCapsStore

    @Binds
    abstract fun deviceKeys(impl: com.point.data.EncryptedDeviceKeys): com.point.core.flow.DeviceKeyStore

    @Binds
    abstract fun linkLog(impl: com.point.data.PrefsLinkLog): com.point.core.flow.LinkLog

    @Binds
    abstract fun privacyConsent(impl: PrefsPrivacyConsent): PrivacyConsent

    @Binds
    abstract fun yoloMode(impl: com.point.data.PrefsYoloMode): com.point.core.flow.YoloMode

    @Binds
    abstract fun sensoryFeedback(impl: VibratorSensoryFeedback): SensoryFeedback

    @Binds
    abstract fun sensorySettings(impl: PrefsSensorySettings): SensorySettings

    @Binds
    abstract fun flowSnapshotStore(impl: FileFlowSnapshotStore): FlowSnapshotStore

    @Binds
    abstract fun crashLog(impl: FileCrashLog): CrashLog

    @Binds
    abstract fun pinnedActions(impl: PrefsPinnedActions): PinnedActions

    @Binds
    @Singleton
    abstract fun accountStore(impl: com.point.data.EncryptedAccountStore): com.point.core.flow.AccountStore

    @Binds
    @Singleton
    abstract fun pendingLogins(impl: com.point.data.EncryptedPendingLogins): com.point.core.flow.PendingLoginStore

    @Binds
    abstract fun browserOpener(impl: com.point.data.AndroidBrowserOpener): com.point.core.flow.BrowserOpener

    @Binds @IntoSet
    abstract fun ocrInvestigation(c: OcrInvestigation): Capability

    @Binds @IntoSet
    abstract fun ocrInvestigationRealizer(r: OcrInvestigationRealizer): Realizer

    @Binds @IntoSet
    abstract fun exifInvestigation(c: ExifInvestigation): Capability

    @Binds @IntoSet
    abstract fun exifInvestigationRealizer(r: ExifInvestigationRealizer): Realizer

    @Binds @IntoSet
    abstract fun qrInvestigation(c: QrInvestigation): Capability

    @Binds @IntoSet
    abstract fun qrInvestigationRealizer(r: QrInvestigationRealizer): Realizer

    @Binds @IntoSet
    abstract fun entityInvestigation(c: EntityInvestigation): Capability

    @Binds @IntoSet
    abstract fun entityInvestigationRealizer(r: EntityInvestigationRealizer): Realizer

    @Binds @IntoSet
    abstract fun metadataEntityInvestigation(c: MetadataEntityInvestigation): Capability

    @Binds @IntoSet
    abstract fun metadataEntityInvestigationRealizer(r: MetadataEntityInvestigationRealizer): Realizer

    @Binds @IntoSet
    abstract fun identifierInvestigation(c: IdentifierInvestigation): Capability

    @Binds @IntoSet
    abstract fun identifierInvestigationRealizer(r: IdentifierInvestigationRealizer): Realizer

    @Binds @IntoSet
    abstract fun textUrlInvestigation(c: TextUrlInvestigation): Capability

    @Binds @IntoSet
    abstract fun textUrlInvestigationRealizer(r: TextUrlInvestigationRealizer): Realizer

    @Binds @IntoSet
    abstract fun vCardInvestigation(c: VCardInvestigation): Capability

    @Binds @IntoSet
    abstract fun vCardInvestigationRealizer(r: VCardInvestigationRealizer): Realizer

    @Binds @IntoSet
    abstract fun zipImagesInvestigation(c: ZipImagesInvestigation): Capability

    @Binds @IntoSet
    abstract fun zipImagesInvestigationRealizer(r: ZipImagesInvestigationRealizer): Realizer

    @Binds @IntoSet
    abstract fun pdfImageInvestigation(c: PdfImageInvestigation): Capability

    @Binds @IntoSet
    abstract fun pdfImageInvestigationRealizer(r: PdfImageInvestigationRealizer): Realizer

    @Binds @IntoSet
    abstract fun periodInvestigation(c: PeriodInvestigation): Capability

    @Binds @IntoSet
    abstract fun periodInvestigationRealizer(r: PeriodInvestigationRealizer): Realizer

    @Binds @IntoSet
    abstract fun documentTypeInvestigation(c: DocumentTypeInvestigation): Capability

    @Binds @IntoSet
    abstract fun documentTypeInvestigationRealizer(r: DocumentTypeInvestigationRealizer): Realizer

    @Binds @IntoSet
    abstract fun graphRolesInvestigation(c: GraphRolesInvestigation): Capability

    @Binds @IntoSet
    abstract fun graphRolesInvestigationRealizer(r: GraphRolesInvestigationRealizer): Realizer

    companion object {

        /**
         * Читатель на устройстве — цепочка (#747): сначала PP-OCRv5 с кириллическим словарём,
         * запасным остаётся прежний движок. Он берёт то, на чём новый молчит, и выбрасывать
         * его незачем: у них разные слабости.
         *
         * @Provides, а не @Binds: класс тянет нативную библиотеку вывода, и @Binds роняет
         * разрешение типов во всём модуле KSP — тот же урок, что с OpenCV.
         */
        @Provides
        @Singleton
        fun atomRecognizer(
            @ApplicationContext context: Context,
            tesseract: TesseractTextRecognizer,
        ): AtomRecognizer = com.point.data.LocalOcr.reader(context)
            ?.let { com.point.data.ChainedAtomRecognizer(it, tesseract) }
            ?: tesseract


        @Provides
        fun objectClassifier(): ObjectClassifier = ObjectClassifier()

        @Provides
        fun dropLink(
            account: com.point.core.flow.AccountStore,
            network: NetworkAvailability,
        ): com.point.core.flow.DropLink =
            com.point.data.RelayDropLink(serverUrl(), devicePass(account), network)

        @Provides
        fun dropInbox(
            account: com.point.core.flow.AccountStore,
            network: NetworkAvailability,
        ): com.point.core.flow.DropInbox =
            // Приём — один код на телефон и компьютер (#727): своей копии у Android больше нет.
            com.point.core.flow.HttpDropInbox({ serverUrl() }, devicePass(account), network)

        @Provides
        @Singleton
        fun pcSecrets(keys: com.point.core.flow.DeviceKeyStore): com.point.core.flow.PcSecrets =
            com.point.core.flow.KeyStoreSecrets(keys)

        @Provides
        @Singleton
        fun pcRpc(
            account: com.point.core.flow.AccountStore,
            secrets: com.point.core.flow.PcSecrets,
            monitor: com.point.core.flow.LinkMonitor,
            network: NetworkAvailability,
        ): com.point.core.flow.RelayRpcClient =
            com.point.core.flow.RelayRpcClient(serverUrl(), { account.current() }, secrets, monitor, network)

        @Provides
        fun pcTransport(rpc: com.point.core.flow.RelayRpcClient): PcTransport = com.point.core.flow.RelayPcTransport(rpc)

        @Provides
        @Singleton
        fun linkMonitor(log: com.point.core.flow.LinkLog): com.point.core.flow.LinkMonitor =
            com.point.core.flow.RememberingLinkMonitor(log)

        @Provides
        fun pcClipboardSync(rpc: com.point.core.flow.RelayRpcClient): com.point.core.flow.PcClipboardSync =
            com.point.core.flow.RelayPcClipboardSync(rpc)

        @Provides
        fun circleClipboard(
            links: com.point.core.flow.PcLinks,
            sync: com.point.core.flow.PcClipboardSync,
        ): com.point.core.flow.CircleClipboard = com.point.core.flow.RelayCircleClipboard(links, sync)

        @Provides
        @Singleton
        fun accountClient(keys: com.point.core.flow.DeviceKeyStore): com.point.core.flow.AccountClient =
            com.point.core.flow.HttpAccountClient(serverUrl(), keys.keys().publicKey, handoff = true)

        private fun serverUrl(): String = com.point.core.flow.PointServer.base(BuildConfig.RELAY_URL)

        private fun devicePass(account: com.point.core.flow.AccountStore): () -> String? =
            { account.current()?.deviceToken }

        @Provides
        @Singleton
        fun entityExtractor(): EntityExtractor = MlKitEntityExtractor()

        @Provides
        @Singleton
        fun backgroundRemover(store: ObjectStore): BackgroundRemover = MlKitBackgroundRemover(store)

        @Provides
        @Singleton
        fun qrReader(): QrReader = MlKitQrReader(ZxingQrReader())

        @Provides
        fun llmProviders(
            http: HttpJson,
            store: ObjectStore,
            userKey: UserKeyLlmClient,
            gemini: GeminiLlmClient,
            claude: ClaudeLlmClient,
            frames: com.point.core.flow.FrameForModel,
        ): List<@JvmSuppressWildcards LlmClient> {
            val free = openAiProviders().configured().map { OpenAiCompatibleClient(http, store, it, frames) }
            val native = buildList {
                if (BuildConfig.GEMINI_API_KEY.isNotBlank()) add(gemini)
                if (BuildConfig.ANTHROPIC_API_KEY.isNotBlank()) add(claude)
            }

            return listOf<LlmClient>(userKey) + free + native
        }

        // Журнал обменов с моделью — только на отладочном стенде: содержимое —
        // личные данные человека (просьба владельца 2026-08-09).
        @Provides
        @Singleton
        fun llmClient(
            impl: FallbackLlmClient,
            privacy: com.point.core.flow.CloudPrivacySettings,
            @ApplicationContext context: Context,
        ): LlmClient = com.point.core.flow.PrivacyGuardedLlmClient(
            inner = com.point.core.flow.LoggingLlmClient(
                inner = impl,
                dir = java.io.File(context.filesDir, "llm-log"),
                enabled = BuildConfig.DEBUG,
            ),
            privacy = privacy,
        )

        @Provides
        fun speechEngines(
            http: HttpFiles,
            byModel: LlmSpeechToText,
            userKeys: UserKeyStore,
        ): List<@JvmSuppressWildcards SpeechToText> = listOf(
            GroqWhisperSpeechToText(
                http,
                { userKeys.keys().keyFor(GROQ_PROVIDER_ID).ifBlank { BuildConfig.GROQ_API_KEY } },
                BuildConfig.GROQ_BASE_URL,
                BuildConfig.GROQ_WHISPER_MODEL,
            ),
            byModel,
        )

        @Provides
        fun speechToText(
            engines: List<@JvmSuppressWildcards SpeechToText>,
            llm: LlmClient,
        ): SpeechToText = SummarizingSpeechToText(FirstHeardSpeechToText(engines), llm)

        @Provides
        fun speechReadiness(
            engines: List<@JvmSuppressWildcards SpeechToText>,
        ): SpeechReadiness = SpeechReadiness { speechKeyNeeds(engines) }

        @Provides
        fun aiReadiness(llm: LlmClient): AiReadiness = AiReadiness { llm.configured }

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

        @Provides
        fun cloudTextReaders(
            http: HttpJson,
            frames: OutboundFrames,
            userKeys: UserKeyStore,
            facts: AiFacts,
        ): List<@JvmSuppressWildcards CloudTextReader> = listOf(
            MistralOcrReader(
                http, frames,

                { userKeys.keys().keyFor(MISTRAL_PROVIDER_ID).ifBlank { BuildConfig.MISTRAL_API_KEY } },
                BuildConfig.MISTRAL_BASE_URL,
                facts,
            ),
            OcrSpaceReader(
                http, frames,
                { BuildConfig.OCRSPACE_API_KEY },
                BuildConfig.OCRSPACE_URL,
            ),
            OvhVisionReader(
                http, frames,
                BuildConfig.OVH_API_KEY,
                BuildConfig.OVH_BASE_URL,
                BuildConfig.OVH_MODEL,
            ),
        )

        @Provides
        fun geminiClient(
            http: HttpJson,
            store: ObjectStore,
            frames: com.point.core.flow.FrameForModel,
        ): GeminiLlmClient =
            GeminiLlmClient(
                http,
                store,
                BuildConfig.GEMINI_API_KEY,
                BuildConfig.GEMINI_MODELS.split(',').map(String::trim).filter(String::isNotBlank),
                frames,
            )

        /**
         * Ключ, адрес и модель Claude знает сборка, а не ядро (#828): сама цепочка живёт в
         * `:core:flow` и о `BuildConfig` не знает.
         */
        @Provides
        fun claudeClient(
            http: HttpJson,
            store: ObjectStore,
            frames: com.point.core.flow.FrameForModel,
        ): ClaudeLlmClient = ClaudeLlmClient(
            http,
            store,
            apiKey = BuildConfig.ANTHROPIC_API_KEY,
            baseUrl = BuildConfig.ANTHROPIC_BASE_URL,
            model = BuildConfig.CLAUDE_MODEL,
            frames = frames,
        )

        /**
         * Перенесённые в `:core:flow` классы собираются здесь (#828): в чистом ядре нет
         * аннотаций Hilt — ровно как у связки устройств после #819.
         */
        @Provides
        fun urlConnectionHttpJson(): UrlConnectionHttpJson = UrlConnectionHttpJson()

        @Provides
        fun urlConnectionHttpFiles(): UrlConnectionHttpFiles = UrlConnectionHttpFiles()

        @Provides
        fun httpAiKeyCheck(http: HttpJson): HttpAiKeyCheck = HttpAiKeyCheck(http)

        @Provides
        fun userKeyLlmClient(
            userKeys: UserKeyStore,
            http: HttpJson,
            store: ObjectStore,
            facts: AiFacts,
        ): UserKeyLlmClient = UserKeyLlmClient(userKeys, http, store, facts)

        @Provides
        fun fallbackLlmClient(
            providers: List<@JvmSuppressWildcards LlmClient>,
            facts: AiFacts,
            network: NetworkAvailability,
            yolo: com.point.core.flow.YoloMode,
        ): FallbackLlmClient = FallbackLlmClient(providers, facts, network, yolo)

        /** Ужать снимок умеет телефон — цепочка провайдеров об этом не знает (#828). */
        @Provides
        fun frameForModel(): com.point.core.flow.FrameForModel =
            com.point.core.flow.FrameForModel { path, mime ->
                com.point.data.inlineFrame(path, mime)
            }

        /**
         * Workers AI отвечает по OpenAI-совместимому адресу, но живёт под номером аккаунта:
         * без номера адрес не собрать, поэтому провайдера нет, даже если ключ задан.
         */
        private fun cloudflareModels(): List<OpenAiProvider> =
            if (BuildConfig.CLOUDFLARE_ACCOUNT_ID.isBlank()) {
                emptyList()
            } else {
                openAiModels(
                    "cloudflare",
                    "${BuildConfig.CLOUDFLARE_BASE_URL}/${BuildConfig.CLOUDFLARE_ACCOUNT_ID}/ai/v1",
                    BuildConfig.CLOUDFLARE_API_KEY,
                    BuildConfig.CLOUDFLARE_MODELS,
                )
            }

        private fun openAiProviders(): List<OpenAiProvider> =
            openAiModels("openrouter", BuildConfig.OPENROUTER_BASE_URL, BuildConfig.OPENROUTER_API_KEY, BuildConfig.OPENROUTER_MODELS) +
                openAiModels("sambanova", BuildConfig.SAMBANOVA_BASE_URL, BuildConfig.SAMBANOVA_API_KEY, BuildConfig.SAMBANOVA_MODELS) +
                openAiModels("mistral", BuildConfig.MISTRAL_BASE_URL, BuildConfig.MISTRAL_API_KEY, BuildConfig.MISTRAL_MODELS) +
                openAiModels("cerebras", BuildConfig.CEREBRAS_BASE_URL, BuildConfig.CEREBRAS_API_KEY, BuildConfig.CEREBRAS_MODELS) +
                openAiModels("groq", BuildConfig.GROQ_BASE_URL, BuildConfig.GROQ_API_KEY, BuildConfig.GROQ_MODELS) +
                openAiModels("zhipu", BuildConfig.ZHIPU_BASE_URL, BuildConfig.ZHIPU_API_KEY, BuildConfig.ZHIPU_MODELS) +
                openAiModels("openai", BuildConfig.OPENAI_BASE_URL, BuildConfig.OPENAI_API_KEY, BuildConfig.OPENAI_MODELS) +

                // Место в очереди — за замером, а не за новизной: пока Workers AI не
                // померян на корпусе, он стоит после тех, кого уже знаем.
                openAiModels("modelscope", BuildConfig.MODELSCOPE_BASE_URL, BuildConfig.MODELSCOPE_API_KEY, BuildConfig.MODELSCOPE_MODELS) +
                cloudflareModels()

        @Provides
        @HistoryDir
        fun historyDir(@ApplicationContext context: Context): java.io.File =
            java.io.File(context.filesDir, "history")

        @Provides
        @FlowSnapshotFile
        fun flowSnapshotFile(@ApplicationContext context: Context): java.io.File =
            java.io.File(context.filesDir, "flow-snapshot.json")

        @Provides
        fun ioDispatcher(): kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO

        @Provides
        @CrashLogFile
        fun crashLogFile(@ApplicationContext context: Context): java.io.File =
            java.io.File(context.filesDir, "last-crash.txt")

        @Provides
        @UsageDir
        fun usageDir(@ApplicationContext context: Context): java.io.File =
            java.io.File(context.filesDir, "usage")
    }
}
