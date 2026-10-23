package com.point.data

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.point.core.flow.OnDeviceTranslator
import com.point.core.model.Lang
import com.point.core.model.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Перевод на самом телефоне — ML Kit (#433, решение владельца 23.10.2026).
 *
 * Язык текста узнаёт встроенная модель, сам перевод — пакет языка, который скачивается один
 * раз (~30 МБ) и дальше работает без сети. Текст с телефона не уходит: наружу идёт только
 * просьба о пакете языка.
 */
class MlKitTranslator @Inject constructor() : OnDeviceTranslator {

    private val identifier by lazy { LanguageIdentification.getClient() }

    override suspend fun translate(text: String, target: Lang): String? = withContext(Dispatchers.IO) {
        val tag = identifier.identifyLanguage(text.take(SAMPLE)).await()
        val source = TranslateLanguage.fromLanguageTag(tag) ?: return@withContext null
        val into = TranslateLanguage.fromLanguageTag(target.tag) ?: return@withContext null
        if (source == into) return@withContext null

        val options = TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(into).build()
        val client = Translation.getClient(options)
        try {
            runCatching { client.downloadModelIfNeeded(DownloadConditions.Builder().build()).await() }
                .getOrElse { error(NEEDS_DOWNLOAD) }

            // По строкам: переводчик держит короткие куски ровнее, а разбивка текста на
            // абзацы остаётся той же, что у исходника.
            val lines = text.lines()
            buildString {
                lines.forEachIndexed { i, line ->
                    if (i > 0) append('\n')
                    append(if (line.isBlank()) line else client.translate(line).await())
                }
            }
        } finally {
            client.close()
        }
    }

    private companion object {

        /** Языку текста хватает начала: читать весь документ ради этого незачем. */
        const val SAMPLE = 2_000

        val NEEDS_DOWNLOAD: String get() =
            tr("Чтобы переводить без ключа, телефон один раз скачивает язык — нужен интернет")
    }
}
