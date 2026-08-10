package com.point.data

import android.content.Context
import com.point.core.flow.AtomRecognizer

/**
 * Чтение на устройстве своими сетями — только там, где его можно померить (#747).
 *
 * Решение владельца 11.08.2026: «только в dogfood, пока не померяем». Движок читает
 * кириллицу заметно лучше прежнего, но стоит сборке 40 МБ — из них 29 МБ сама библиотека
 * вывода, — и платить их всем до замеров на живом корпусе рано.
 *
 * Поэтому и модели, и распознаватель, и библиотека живут в отдельном наборе исходников,
 * который подключён к debug и dogfood. В релизе на этом месте стоит заглушка, и Point
 * читает как раньше.
 */
internal object LocalOcr {

    fun reader(context: Context): AtomRecognizer? = PaddleOcrRecognizer(context)
}
