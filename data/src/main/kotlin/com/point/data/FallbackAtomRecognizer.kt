package com.point.data

import com.point.core.flow.AtomLayer
import com.point.core.flow.AtomRecognizer
import com.point.core.model.PointObject
import javax.inject.Inject

/**
 * Цепочка облачных читателей страницы — зеркало [FallbackLlmClient] для геометрии (#280).
 *
 * Правила ровно три:
 * - **первый непустой слой выигрывает.** Пустой слой — не победа: он означает «прочитал и ничего
 *   не нашёл», а на ведомости это заведомо неправда, поэтому цепочка идёт дальше;
 * - **402/429 — следующий, а не касса.** Тезис проекта: жить на бесплатном. Ответ «нужна карта»
 *   или «кончился лимит» переводит очередь к следующему слою и никогда не покупает;
 * - **все отказали — честный отказ.** Здесь [read] бросает, а не отдаёт пустой слой. Пустой слой
 *   неотличим от «страница пустая», и молчаливая подмена одного другим — ровно та тихая ложь,
 *   от которой лечит весь слой улик (#257).
 *
 * Ненастроенный слой (нет ключа — например, в раздаваемой релизной сборке, где `BuildConfig`
 * пустой) выпадает **молча**: это не отказ чтения, это отсутствие провайдера.
 */
class FallbackAtomRecognizer @Inject constructor(
    private val readers: List<@JvmSuppressWildcards CloudAtomRecognizer>,
) : AtomRecognizer {

    /** Есть ли вообще кому читать: ни одного ключа — и облачного чтения в этой сборке нет. */
    val available: Boolean get() = readers.any { it.configured }

    override suspend fun read(obj: PointObject): AtomLayer {
        val errors = mutableListOf<String>()
        var considered = 0
        for (reader in readers) {
            if (!reader.configured) continue // нет ключа — слоя просто нет, это не сбой
            if (!reader.canRead(obj)) continue // например, PDF там, где ридер умеет только кадр
            considered++
            try {
                val layer = reader.read(obj)
                if (layer.atoms.isNotEmpty()) return layer
                errors += "${reader.reader}: страница прочитана пустой"
            } catch (e: Exception) {
                errors += e.message ?: e.javaClass.simpleName
            }
        }
        if (considered == 0) error(NOT_CONFIGURED)
        error(summarise(errors))
    }

    /**
     * Одна человеческая строка вместо стены ошибок от каждого слоя — та же дисциплина, что в
     * [FallbackLlmClient]: общая причина (нет сети, кончилось бесплатное) схлопывается в причину,
     * а не в перечисление.
     */
    private fun summarise(errors: List<String>): String = when {
        errors.isNotEmpty() && errors.all { it.isNetworkError() } ->
            "Облачное чтение недоступно — нет подключения к интернету"
        errors.isNotEmpty() && errors.all { it.isQuotaError() } ->
            "Бесплатные лимиты чтения исчерпаны — вернитесь позже, платить не идём"
        else -> "Облачное чтение не удалось — " +
            errors.map { it.substringBefore('\n').take(120) }.distinct().take(2).joinToString("; ")
    }

    private fun String.isNetworkError(): Boolean = NETWORK_HINTS.any { contains(it, ignoreCase = true) }

    private fun String.isQuotaError(): Boolean = QUOTA_HINTS.any { contains(it, ignoreCase = true) }

    private companion object {
        const val NOT_CONFIGURED =
            "Облачное чтение не настроено — задайте бесплатный ключ Unstructured или LlamaParse"

        val NETWORK_HINTS = listOf(
            "resolve host", "No address associated", "Unable to resolve",
            "connection abort", "Network is unreachable", "Failed to connect",
            "timed out", "timeout",
        )

        val QUOTA_HINTS = listOf("(402)", "(429)", "HTTP 402", "HTTP 429")
    }
}
