package com.point.data.di

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.point.core.flow.Enricher
import com.point.core.flow.Enrichment
import com.point.core.flow.Exporter
import com.point.core.flow.FavoritesStore
import com.point.core.flow.HistoryStore
import com.point.core.flow.LlmClient
import com.point.core.flow.ArchiveExtractor
import com.point.core.flow.ObjectClassifier
import com.point.core.flow.ObjectStore
import com.point.core.flow.OfficeTextExtractor
import com.point.core.flow.PdfTextExtractor
import com.point.core.flow.Sharer
import com.point.core.flow.TextRecognizer
import com.point.core.flow.UrlOpener
import com.point.data.AndroidSharer
import com.point.data.AndroidUrlOpener
import com.point.data.ClaudeLlmClient
import com.point.data.CommonsArchiveExtractor
import com.point.data.DefaultEnrichment
import com.point.data.FallbackLlmClient
import com.point.data.FileFavoritesStore
import com.point.data.FileHistoryStore
import com.point.data.GeminiLlmClient
import com.point.data.MediaStoreExporter
import com.point.data.OoxmlOfficeTextExtractor
import com.point.data.OpenAiLlmClient
import com.point.data.PdfBoxTextExtractor
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
    abstract fun enrichment(impl: DefaultEnrichment): Enrichment

    @Binds
    abstract fun pdfTextExtractor(impl: PdfBoxTextExtractor): PdfTextExtractor

    @Binds
    abstract fun officeTextExtractor(impl: OoxmlOfficeTextExtractor): OfficeTextExtractor

    @Binds
    abstract fun archiveExtractor(impl: CommonsArchiveExtractor): ArchiveExtractor

    @Binds
    abstract fun historyStore(impl: FileHistoryStore): HistoryStore

    @Binds
    abstract fun favoritesStore(impl: FileFavoritesStore): FavoritesStore

    @Binds
    abstract fun textRecognizer(impl: TesseractTextRecognizer): TextRecognizer

    @Binds @IntoSet
    abstract fun textUrlEnricher(e: TextUrlEnricher): Enricher

    @Binds @IntoSet
    abstract fun zipImagesEnricher(e: ZipImagesEnricher): Enricher

    companion object {
        /** Pure classifier lives in :core:flow (no DI annotations there). */
        @Provides
        fun objectClassifier(): ObjectClassifier = ObjectClassifier()

        /** Ordered AI providers the fallback tries: Gemini -> Claude -> OpenAI. */
        @Provides
        fun llmProviders(
            gemini: GeminiLlmClient,
            claude: ClaudeLlmClient,
            openAi: OpenAiLlmClient,
        ): List<@JvmSuppressWildcards LlmClient> = listOf(gemini, claude, openAi)

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
