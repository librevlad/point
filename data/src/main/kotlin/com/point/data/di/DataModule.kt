package com.point.data.di

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.point.core.flow.CapabilityUsage
import com.point.core.flow.Enricher
import com.point.core.flow.Enrichment
import com.point.core.flow.Entitlements
import com.point.core.flow.Exporter
import com.point.core.flow.FavoritesStore
import com.point.core.flow.HistoryStore
import com.point.core.flow.LlmClient
import com.point.core.flow.ArchiveExtractor
import com.point.core.flow.ObjectClassifier
import com.point.core.flow.ObjectStore
import com.point.core.flow.OfficeTextExtractor
import com.point.core.flow.PdfRasterizer
import com.point.core.flow.PdfTextExtractor
import com.point.core.flow.Sharer
import com.point.core.flow.SpreadsheetWriter
import com.point.core.flow.TextRecognizer
import com.point.core.flow.UrlOpener
import com.point.core.flow.Viewer
import com.point.data.AndroidSharer
import com.point.data.AndroidUrlOpener
import com.point.data.AndroidViewer
import com.point.data.ClaudeLlmClient
import com.point.data.CommonsArchiveExtractor
import com.point.data.DefaultEnrichment
import com.point.data.DefaultEntitlements
import com.point.data.FallbackLlmClient
import com.point.data.FileCapabilityUsage
import com.point.data.FileFavoritesStore
import com.point.data.FileHistoryStore
import com.point.data.GeminiLlmClient
import com.point.data.MediaStoreExporter
import com.point.data.OoxmlOfficeTextExtractor
import com.point.data.OoxmlSpreadsheetWriter
import com.point.data.BuildConfig
import com.point.data.OpenAiCompatibleClient
import com.point.data.OpenAiProvider
import com.point.data.configured
import com.point.data.openAiModels
import com.point.data.PdfBoxTextExtractor
import com.point.data.PdfImageEnricher
import com.point.data.PdfRendererRasterizer
import com.point.data.ScratchObjectStore
import com.point.data.TesseractTextRecognizer
import com.point.data.TextUrlEnricher
import com.point.data.ZipImagesEnricher
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

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
    abstract fun viewer(impl: AndroidViewer): Viewer

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

    @Binds @IntoSet
    abstract fun textUrlEnricher(e: TextUrlEnricher): Enricher

    @Binds @IntoSet
    abstract fun zipImagesEnricher(e: ZipImagesEnricher): Enricher

    @Binds @IntoSet
    abstract fun pdfImageEnricher(e: PdfImageEnricher): Enricher

    companion object {
        /** Pure classifier lives in :core:flow (no DI annotations there). */
        @Provides
        fun objectClassifier(): ObjectClassifier = ObjectClassifier()

        /**
         * The AI fallback chain — "all free providers, max": every OpenAI-compatible
         * free provider first (vision-capable ones lead, so "Понять" on a photo works),
         * then the native providers. Each is included only if its key is set, so the
         * chain self-activates as keys land in local.properties. Gemini is intentionally
         * last — it rate-limits hard (HTTP 429), which is the whole reason for #32.
         */
        @Provides
        fun llmProviders(
            store: ObjectStore,
            gemini: GeminiLlmClient,
            claude: ClaudeLlmClient,
        ): List<@JvmSuppressWildcards LlmClient> {
            val free = openAiProviders().configured().map { OpenAiCompatibleClient(store, it) }
            val native = buildList {
                if (BuildConfig.GEMINI_API_KEY.isNotBlank()) add(gemini)
                if (BuildConfig.ANTHROPIC_API_KEY.isNotBlank()) add(claude)
            }
            return free + native
        }

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
        @FavoritesDir
        fun favoritesDir(@ApplicationContext context: Context): java.io.File =
            java.io.File(context.filesDir, "favorites")
    }
}
