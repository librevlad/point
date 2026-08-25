package com.point.core.flow

import com.point.core.model.ObjectKind
import com.point.core.model.PointObject
import java.io.File

/**
 * Текущее знание объекта — то, что Point уже понял, а не то, с чего он начал (#1138).
 *
 * Общего входа «дай текст этого объекта» не было, и каждый исполнитель решал сам: почти все
 * шли к `input.uri`, то есть к исходному файлу. Поэтому «Перевести» отвечало «текста нет»
 * над прочитанным кадром, а «В Word» читало кадр заново и строило документ из худшего
 * прочтения, чем то, что уже лежало в графе.
 *
 * Здесь один вопрос и один ответ: что известно сейчас. Исследованием это не является и
 * чтение не запускает — если знания нет, ответ пустой, и решение о том, добывать ли его,
 * остаётся за Discovery и за самим действием (ADR-0001 §11).
 */
interface CurrentKnowledge {

    /**
     * Текст объекта, каким Point знает его сейчас, или `null` — текста ещё нет.
     *
     * Сначала прочитанное с кадра, потом собственное содержимое объекта там, где оно и есть
     * текст: у текстового объекта — его файл, у PDF — его текстовый слой. Это не толкование
     * заново, а то же самое знание, взятое из первых рук.
     */
    suspend fun textOf(obj: PointObject, limit: Int = KNOWN_TEXT_LIMIT): String?

    /** Слой слов с их местом на странице, если он уже снят; иначе `null`. */
    suspend fun layerOf(obj: PointObject): AtomLayer?
}

/** Сколько текста отдаётся за один вопрос: столько же, сколько берёт разведка. */
const val KNOWN_TEXT_LIMIT: Int = INVESTIGATION_TEXT_CHARS

/**
 * Знание берётся из Graph, а payload — только там, где он и есть знание.
 *
 * Ни одного чтения кадра здесь не происходит: снимок, который ещё не читали, честно
 * отвечает «неизвестно».
 */
class GraphKnowledge(
    private val store: ObjectStore,
    private val pdfText: PdfTextExtractor,
) : CurrentKnowledge {

    override suspend fun textOf(obj: PointObject, limit: Int): String? {
        reading(obj, limit)?.let { return it }
        return when (obj.state.kind) {
            ObjectKind.TEXT -> runCatching { store.readText(obj, limit) }.getOrNull()

            // Про документ, у которого текст файлом не достаётся, это уже известно (#1241):
            // `IS_IMAGE_PDF` — найденный ответ, а не «ещё не смотрели». Прежде каждый вопрос
            // («Перевести», «В Word», реплика разговора) гонял все страницы ради заведомой
            // пустоты — секунды за уже отвеченное.
            ObjectKind.PDF -> if (obj.state.has(com.point.core.model.Feature.IS_IMAGE_PDF)) {
                null
            } else {
                runCatching {

                    // Единственное место здесь, где работа заметна человеку: текстовый слой PDF
                    // достаётся не мгновенно, и молчать об этом нельзя (#555).
                    reportStage("Читаю текст PDF")
                    pdfText.extractText(obj).take(limit)
                }.getOrNull()
            }
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    override suspend fun layerOf(obj: PointObject): AtomLayer? =
        obj.metadata[META_OCR_ATOMS_REF]?.takeIf { it.isNotBlank() }?.let { ref ->
            runCatching { AtomCodec.decode(File(ref).readText()) }.getOrNull()
        }?.takeIf { it.atoms.isNotEmpty() }

    /**
     * Прочитанное с кадра — знание объекта, а не чужой файл рядом.
     *
     * Читается ровно столько, сколько спросили (#1241): прежде прочтение поднималось в
     * память целиком и обрезалось уже после — на каждый вопрос и на каждую реплику
     * разговора о большом объекте.
     */
    private fun reading(obj: PointObject, limit: Int): String? =
        obj.metadata[META_OCR_TEXT_REF]?.takeIf { it.isNotBlank() }
            ?.let { ref -> fileHead(ref, limit) }
            ?.takeIf { it.isNotBlank() }
}
