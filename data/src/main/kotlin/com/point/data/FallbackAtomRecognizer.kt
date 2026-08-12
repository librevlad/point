package com.point.data

import com.point.core.flow.AtomLayer
import com.point.core.flow.AtomRecognizer
import com.point.core.model.PointObject
import javax.inject.Inject
import com.point.core.flow.summariseCloudErrors

class FallbackAtomRecognizer @Inject constructor(
    private val readers: List<@JvmSuppressWildcards CloudAtomRecognizer>,
) : AtomRecognizer {

    val available: Boolean get() = readers.any { it.configured }

    override suspend fun read(obj: PointObject): AtomLayer {
        val errors = mutableListOf<String>()
        var configured = 0
        var considered = 0
        for (reader in readers) {
            if (!reader.configured) continue
            configured++
            if (!reader.canRead(obj)) continue
            considered++
            try {
                val layer = reader.read(obj)
                if (layer.atoms.isNotEmpty()) return layer
                errors += "${reader.reader}: страница прочитана пустой"
            } catch (e: Exception) {
                errors += e.message ?: e.javaClass.simpleName
            }
        }

        if (configured == 0) error(NOT_CONFIGURED)
        if (considered == 0) error(NOT_FOR_THIS_OBJECT)
        error(summarise(errors))
    }

    private fun summarise(errors: List<String>): String = summariseCloudErrors(errors)

    private companion object {
        const val NOT_CONFIGURED =
            "Облачное чтение не настроено — задайте бесплатный ключ Unstructured или LlamaParse"

        const val NOT_FOR_THIS_OBJECT =
            "Облачное чтение не берётся за этот объект — бесплатные читатели принимают снимок страницы"
    }
}
