package com.point

import com.point.core.model.ObjectKind

/** Что делает тап по объекту на первом экране (#290, #259). */
enum class HeroTap { SELECT, OPEN }

/**
 * Обводить можно то, у чего есть пиксели, — картинку.
 *
 * Слой слов больше не условие, а лишь разница в поведении рамки: есть слой — рамка липнет к
 * словам и выделение даёт текст; нет слоя — рамка свободная и выделение даёт кадр
 * (`fragmentCapture`). Требовать чтение до обводки значило заставлять человека распознавать то,
 * что он всего лишь хочет обвести.
 *
 * [hasWordLayer] остаётся в договоре намеренно: тест закрепляет, что наличие слоя ничего здесь не
 * меняет, — иначе прежнее правило вернулось бы незамеченным.
 */
@Suppress("UNUSED_PARAMETER")
fun heroTapOf(kind: ObjectKind, hasWordLayer: Boolean): HeroTap =
    if (kind == ObjectKind.IMAGE) HeroTap.SELECT else HeroTap.OPEN
