package com.point.desktop

import com.point.core.flow.extensionForMime
import com.point.core.flow.mimeForName

/**
 * Компьютер спрашивает про типы файлов там же, где телефон (#840): своя таблица уехала в
 * `:core:flow`, здесь остались имена, которыми её зовёт десктопный код.
 */
fun mimeFor(fileName: String): String = mimeForName(fileName)

fun extFor(mime: String): String = extensionForMime(mime).ifBlank { "bin" }
