package com.point.data.di

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.point.core.flow.AiKeyCheck
import com.point.core.flow.AiReadiness
import com.point.core.flow.AppLauncher
import com.point.core.flow.BackgroundRemover
import com.point.core.flow.CalendarInserter
import com.point.core.flow.CapabilityUsage
import com.point.core.flow.Clipboard
import com.point.core.flow.ContactInserter
import com.point.core.flow.Enricher
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
import com.point.core.flow.GROQ_PROVIDER_ID
import com.point.core.flow.MISTRAL_PROVIDER_ID
import com.point.core.flow.SpeechReadiness
import com.point.core.flow.SpeechToText
import com.point.core.flow.TextRecognizer
import com.point.core.flow.keyFor
import com.point.core.flow.speechKeyNeeds
import com.point.core.flow.UrlOpener
import com.point.core.flow.ChosenApps
import com.point.core.flow.PcDiscovery
import com.point.core.flow.PcCapsStore
import com.point.core.flow.PcPairings
import com.point.core.flow.PcTransport
import com.point.core.flow.UsageJournal
import com.point.core.flow.UserKeyStore
import com.point.core.flow.Viewer
import com.point.data.AndroidAppLauncher
import com.point.data.AndroidCalendarInserter
import com.point.data.AndroidClipboard
import com.point.data.AndroidContactInserter
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
import com.point.data.FilePcCaps
import com.point.data.FilePcPairings
import com.point.data.HttpAiKeyCheck
import com.point.data.HttpPcClipboardSync
import com.point.data.HttpUrlPcTransport
import com.point.data.LanThenRelayClipboardSync
import com.point.data.LanThenRelayTransport
import com.point.data.RelayPcClipboardSync
import com.point.data.RelayPcTransport
import com.point.data.SelfHealingPcTransport
import com.point.data.FileUsageJournal
import com.point.data.FileCapabilityUsage
import com.point.data.FileHistoryStore
import com.point.data.GeminiLlmClient
import com.point.data.GroqWhisperSpeechToText
import com.point.data.SummarizingSpeechToText
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
import com.point.data.OcrSpaceReader
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

    /** #464: «Сохранить контакт» открывает системный экран нового контакта. */
    @Binds
    abstract fun contactInserter(impl: AndroidContactInserter): ContactInserter

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
    abstract fun capabilityUsage(impl: FileCapabilityUsage): CapabilityUsage

    @Binds
    abstract fun textRecognizer(impl: TesseractTextRecognizer): TextRecognizer

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

    /** Живая проверка этого ключа — тем же путём, каким пойдут действия, и только по тапу (#465). */
    @Binds
    abstract fun aiKeyCheck(impl: HttpAiKeyCheck): AiKeyCheck

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
    abstract fun pcCaps(impl: FilePcCaps): PcCapsStore

    /** LAN autodiscovery of Point-for-PC (#147 slice C) — sugar over manual entry. */
    @Binds
    abstract fun pcDiscovery(impl: AndroidPcDiscovery): PcDiscovery

    /** Где последний контакт с компьютером переживает перезапуск (#451). */
    @Binds
    abstract fun linkLog(impl: com.point.data.PrefsLinkLog): com.point.core.flow.LinkLog

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

    /** Пропуск аккаунта (#472) — шифрованный, один на приложение. */
    @Binds
    @Singleton
    abstract fun accountStore(impl: com.point.data.EncryptedAccountStore): com.point.core.flow.AccountStore

    /** Страница входа открывается системным браузером (#472). */
    @Binds
    abstract fun browserOpener(impl: com.point.data.AndroidBrowserOpener): com.point.core.flow.BrowserOpener

    @Binds @IntoSet
    abstract fun textUrlEnricher(e: TextUrlEnricher): Enricher

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
        fun dropLink(account: com.point.core.flow.AccountStore): com.point.core.flow.DropLink =
            com.point.data.RelayDropLink(serverUrl(), devicePass(account))

        /**
         * «Принять файл» (#388) — та же ссылка в обратную сторону: чужой человек кладёт файл в
         * ящик на релее, телефон забирает его обычным путём. Плата та же: этот файл релей видит.
         */
        @Provides
        fun dropInbox(account: com.point.core.flow.AccountStore): com.point.core.flow.DropInbox =
            com.point.data.RelayDropInbox(serverUrl(), devicePass(account))

        /** #161 v2 «железобетонно»: the LAN transport self-heals a stale PC IP via mDNS (re-resolve +
         *  retry with the token), and when it still can't be reached — different network, LTE — the
         *  object falls back to the always-works relay (outbound-only, E2E-encrypted). */
        @Provides
        fun pcTransport(
            http: HttpUrlPcTransport,
            discovery: PcDiscovery,
            pairings: PcPairings,
            monitor: com.point.core.flow.LinkMonitor,
            account: com.point.core.flow.AccountStore,
        ): PcTransport = LanThenRelayTransport(
            lan = SelfHealingPcTransport(http, discovery, pairings),
            relay = RelayPcTransport(devicePass(account)),
            monitor = monitor,
        )

        /** Кто помнит последний контакт с компьютером (#412) — один на приложение: экран и
         *  транспорт обязаны говорить об одном и том же. Помнит и после перезапуска (#451):
         *  забытый вчерашний контакт превращался на экране в «ещё не связывались». */
        @Provides
        @Singleton
        fun linkMonitor(log: com.point.core.flow.LinkLog): com.point.core.flow.LinkMonitor =
            com.point.core.flow.RememberingLinkMonitor(log)

        /** Shared clipboard (#161 «общий буфер»): LAN hop first, relay fallback when off-network —
         *  same «безотказно» shape as [pcTransport]. */
        @Provides
        fun pcClipboardSync(
            http: HttpPcClipboardSync,
            account: com.point.core.flow.AccountStore,
        ): com.point.core.flow.PcClipboardSync =
            LanThenRelayClipboardSync(
                lan = http,
                relay = RelayPcClipboardSync(devicePass(account)),
            )

        /**
         * Разговор с сервером Point (#472): вход, круг устройств, отзыв.
         *
         * Реализация одна на телефон и на ПК и живёт в `:core:flow` — вход одинаков там и там.
         */
        @Provides
        @Singleton
        fun accountClient(): com.point.core.flow.AccountClient =
            com.point.core.flow.HttpAccountClient(serverUrl())

        /**
         * Адрес сервера — сборка без секрета (#419).
         *
         * `RELAY_URL` остался переопределением для своих опытов и секретом никогда не был; пусто — берётся
         * адрес по умолчанию. Общего пароля приложения рядом больше нет ни в одной сборке.
         */
        private fun serverUrl(): String = com.point.core.flow.PointServer.base(BuildConfig.RELAY_URL)

        /**
         * Чем это устройство предъявляется серверу — своим пропуском, а не общим паролем (#473).
         *
         * Функция, а не значение: пропуск появляется после входа и исчезает после «Выйти», а клиенты релея живут дольше и того и другого.
         */
        private fun devicePass(account: com.point.core.flow.AccountStore): () -> String? =
            { account.current()?.deviceToken }

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
         * Расшифровка голосового (#223) — очередь движков, и порядок в ней измерен, а не выбран.
         *
         * **Whisper первый.** Замер 04.08.2026: бесплатная квота модели общего назначения — 20
         * запросов в СУТКИ (`HTTP 429, generate_content_free_tier_requests, limit: 20`), то есть
         * расшифровка на ней кончается за вечер. Whisper на тех же трёх записях владельца прочитал
         * украинскую речь дословно и даром (ошибка слов 9,5 %, и вся она косметическая — «той
         * ходім» / «то й ходім», место апострофа).
         *
         * **Модель общего назначения — вторая, а не выброшена.** Она читает форматы, которых
         * Whisper не обещает, и приносит суть одним ответом; на её квоту приходят, только когда
         * первый не дошёл.
         *
         * **Ключ Whisper — от человека, а не от сборки (#467).** Раньше движок заводился от
         * `BuildConfig.GROQ_API_KEY`, и очередь собиралась один раз при старте. В раздаваемой
         * сборке этого ключа нет вовсе — то есть Whisper там не включался НИКОГДА, а тот, кто читал
         * «нет ключа Groq», шёл на экран ключей, вводил ключ Groq — и не менялось ничего. Теперь
         * ключ спрашивается на каждый вызов: сначала ключ человека (если на экране выбран именно
         * Groq), и только потом ключ сборки — он живой лишь в отладочной. Ключ сборки остаётся
         * вторым, а не первым, потому что квота человека — его собственная, и тратить чужую вместо
         * неё было бы решением за него.
         *
         * Оба движка стоят в очереди всегда: ненастроенный из неё выпадает сам, спросив себя о
         * ключе прямо в момент работы. Собирать список по ключам ЗДЕСЬ значило бы снова запомнить
         * ответ на старте — ровно та ошибка, которую чиним.
         *
         * Сверху — добор сути ([SummarizingSpeechToText]): Whisper отдаёт только дословный текст, а
         * человеку обещана суть. Одно действие, один дополнительный ТЕКСТОВЫЙ запрос, и его провал
         * ничего не отменяет.
         */
        @Provides
        fun speechEngines(
            http: HttpFiles,
            byModel: LlmSpeechToText,
            userKeys: UserKeyStore,
        ): List<@JvmSuppressWildcards SpeechToText> = listOf(
            GroqWhisperSpeechToText(
                http,
                { userKeys.read().keyFor(GROQ_PROVIDER_ID).ifBlank { BuildConfig.GROQ_API_KEY } },
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

        /**
         * Тот же вопрос, что и у очереди, но заданный ДО тапа: «есть ли кому слушать». Отдельный
         * контракт, потому что спрашивает его [com.point.core.flow.Capability] — то, что видит UI, —
         * а движок остаётся за реализатором. Список движков общий, поэтому подсказка на экране и
         * отказ после тапа не могут разойтись.
         */
        @Provides
        fun speechReadiness(
            engines: List<@JvmSuppressWildcards SpeechToText>,
        ): SpeechReadiness = SpeechReadiness { speechKeyNeeds(engines) }

        /**
         * Тот же вопрос про модель, и по той же причине отдельным контрактом (#529): его задаёт
         * [com.point.core.flow.Capability], а способности нельзя давать клиента, которым можно
         * сходить в сеть, — иначе «что можно» и «как» перестают быть разными вещами.
         *
         * Клиент один и тот же, поэтому имя действия («AI · нужен ключ») и отказ после тапа не
         * могут разойтись: оба судят по `configured` той же цепочки провайдеров.
         */
        @Provides
        fun aiReadiness(llm: LlmClient): AiReadiness = AiReadiness { llm.configured }

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
         * Внешний глаз (#280/#490/#493) — цепочка в порядке **измеренной эффективности
         * бесплатного**, а не по приватности и не по алфавиту. Числа — перемер 04.08.2026
         * (`docs/VISION-MODELS.md`, три повтора на каждую пару «кандидат + картинка»):
         *
         * 1. **Mistral OCR** — 15/15 и на чистом скане, и на мятом фото под углом, шесть ответов из
         *    шести, медиана 0,3–0,4 с. Специальная ручка разбора страницы, а не чат: у того же
         *    поставщика чат берёт 13/15. Отсюда правило очереди — специальные ручки раньше общих
         *    чатов, а не «кто первый подключён».
         * 2. **OCR.space** — 15/15 шесть из шести, 2 с, 25 000 страниц в месяц. Работает
         *    **демо-ключом из их же примеров**, то есть живой у человека, который ничего не
         *    настраивал.
         * 3. **OVH Qwen2.5-VL** — 15/15 шесть из шести, 6–8 с, без ключа и регистрации вовсе; два
         *    запроса в минуту. Медленнее первых двух, зато единственный, кто остаётся на строгом
         *    уровне приватности («Не учатся на моём»).
         *
         * Приватность отсюда никого не выбрасывает — она столбец, который человек видит, и
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
            userKeys: UserKeyStore,
        ): List<@JvmSuppressWildcards CloudTextReader> = listOf(
            MistralOcrReader(
                http, frames,
                // Ключ человека первым, ключ сборки вторым, и спрашивается он на каждом чтении
                // (#467). Прежде сюда приходил только ключ сборки — а его нет ни в одной
                // раздаваемой сборке: сильнейший читатель был мёртв у всех, кроме нас самих.
                { userKeys.read().keyFor(MISTRAL_PROVIDER_ID).ifBlank { BuildConfig.MISTRAL_API_KEY } },
                BuildConfig.MISTRAL_BASE_URL,
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

        /**
         * Known OpenAI-compatible endpoints, each expanded into one entry per model in its
         * comma-separated `*_MODELS` list; [configured] drops the ones without a key.
         *
         * **Порядок — по замеру, и правило одно: сколько раз ответил → насколько дословно → за
         * сколько** (перемер 04.08.2026, `docs/VISION-MODELS.md`, шесть попыток на кандидата).
         * Надёжность стоит первой не из любви к порядку: одиночный удачный прогон прячет лимит
         * провайдера, и ровно на этом Groq однажды выглядел вторым номером, а на повторах дал два
         * ответа из шести. Прежняя очередь была наследством («кого подключили раньше»), а не
         * ранжированием.
         *
         * - **openrouter** — 15/15, шесть ответов из шести;
         * - **sambanova** — 14–15/15, шесть из шести (ключ лежал в `local.properties` мёртвым: поля
         *   сборки под него не было вовсе);
         * - **mistral** — чат 12–13/15, шесть из шести; его же OCR-ручка стоит отдельно и выше,
         *   в цепочке читателей страницы;
         * - **cerebras** — 14/15, пять из шести, но **0,7 с**: самый быстрый в таблице;
         * - **groq** — 15/15 и два ответа из шести (8000 токенов в минуту, картинка ≈4400, то есть
         *   вторая в ту же минуту не проходит). По качеству хорош, рабочей лошадью быть не может;
         * - **zhipu** — 12–13/15 и два из шести («перегружено»); последний из живых;
         * - **github** — закрыт (410), список моделей пуст; **openai** — платный, потому в хвосте.
         *
         * Замер делался на снимках. Для текста отдельного замера нет, и выдумывать ему свой порядок
         * не из чего: 429 переводит очередь дальше сам, а лишний порядок был бы догадкой с видом
         * решения.
         */
        private fun openAiProviders(): List<OpenAiProvider> =
            openAiModels("openrouter", BuildConfig.OPENROUTER_BASE_URL, BuildConfig.OPENROUTER_API_KEY, BuildConfig.OPENROUTER_MODELS) +
                openAiModels("sambanova", BuildConfig.SAMBANOVA_BASE_URL, BuildConfig.SAMBANOVA_API_KEY, BuildConfig.SAMBANOVA_MODELS) +
                openAiModels("mistral", BuildConfig.MISTRAL_BASE_URL, BuildConfig.MISTRAL_API_KEY, BuildConfig.MISTRAL_MODELS) +
                openAiModels("cerebras", BuildConfig.CEREBRAS_BASE_URL, BuildConfig.CEREBRAS_API_KEY, BuildConfig.CEREBRAS_MODELS) +
                openAiModels("groq", BuildConfig.GROQ_BASE_URL, BuildConfig.GROQ_API_KEY, BuildConfig.GROQ_MODELS) +
                openAiModels("zhipu", BuildConfig.ZHIPU_BASE_URL, BuildConfig.ZHIPU_API_KEY, BuildConfig.ZHIPU_MODELS) +
                openAiModels("github", BuildConfig.GITHUB_BASE_URL, BuildConfig.GITHUB_API_KEY, BuildConfig.GITHUB_MODELS) +
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
        @UsageDir
        fun usageDir(@ApplicationContext context: Context): java.io.File =
            java.io.File(context.filesDir, "usage")
    }
}
