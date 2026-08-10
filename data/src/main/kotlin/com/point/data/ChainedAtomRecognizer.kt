package com.point.data

import com.point.core.flow.AtomLayer
import com.point.core.flow.AtomRecognizer
import com.point.core.model.PointObject

/**
 * Читатели на устройстве идут цепочкой: сначала тот, кто читает кириллицу лучше (#747).
 *
 * Прежний движок не различал украинские буквы на наклейке и отдавал «ЫТЛГОРОД-ДНТСТРОВСЬКИЙ».
 * Но выбрасывать его нельзя: он берёт то, на чём новый молчит, — и остаётся запасным.
 *
 * Запасной зовётся только когда первый не прочитал ничего: частичное чтение — уже знание,
 * и портить его вторым мнением незачем.
 */
class ChainedAtomRecognizer(
    private val first: AtomRecognizer,
    private val fallback: AtomRecognizer,
) : AtomRecognizer {

    override suspend fun read(obj: PointObject): AtomLayer {
        val primary = runCatching { first.read(obj) }.getOrNull()
        if (primary != null && (primary.atoms.isNotEmpty() || primary.text.isNotBlank())) return primary

        val second = fallback.read(obj)

        // Первый сорвался, второй тоже пуст — причина берётся у того, кто её назвал.
        if (second.atoms.isEmpty() && second.text.isBlank() && second.incomplete == null) {
            primary?.incomplete?.let { return AtomLayer(emptyList(), incomplete = it) }
        }
        return second
    }
}
