package com.point.core.ui

import com.point.core.model.Bubble
import com.point.core.model.Intent

/*
 * Группировка действий по смыслу — одна на телефон и компьютер (#879).
 *
 * Правило жило в телефонном `FirstScreen.kt`, и компьютер его не видел: там все действия
 * лежали одним списком «Что можно сделать». Порядок при этом был тот же — правило
 * ранжирования общее (#840), — но человеку он был не виден.
 *
 * Логика чистая: она смотрит на намерение действия и ни на что больше. Каталог
 * `src/shared/kotlin` компилируют обе стороны, каждая своим Compose.
 */

const val LIKELY_COUNT = 3

fun likelyCount(total: Int): Int = if (total <= LIKELY_COUNT + 2) total else LIKELY_COUNT

enum class ActionGroup(val label: String) {
    USE("Сделать"),
    EXTRACT("Извлечь"),
    TRANSFORM("Превратить"),
    SEND("Отправить"),
}

/**
 * «Позвонить», «Сохранить контакт», «Построить маршрут» — это не «Отправить» (охота
 * 11.08.2026). Они стояли в чужой группе и последними, а на объекте-значении именно они и
 * нужны: владелец про верхний блок сказал «одно действие, и не то, которое мне надо».
 */
fun actionGroupOf(intent: Intent): ActionGroup = when (intent) {
    Intent.UNDERSTAND -> ActionGroup.EXTRACT
    Intent.PREPARE -> ActionGroup.TRANSFORM
    Intent.OPEN -> ActionGroup.USE
    Intent.SEND -> ActionGroup.SEND
}

data class ActionSection(val group: ActionGroup, val bubbles: List<Bubble>)

/**
 * [useFirst] — объект сам является значением (телефон, адрес, номер). Там главное им
 * воспользоваться, а не понимать его заново: «Исправить ошибки» стояло первым и
 * подсвеченным внутри телефона, а «Позвонить» пряталось ниже сгиба.
 */
fun actionGroupOrder(useFirst: Boolean = false): List<ActionGroup> = if (useFirst) {
    listOf(ActionGroup.USE, ActionGroup.EXTRACT, ActionGroup.TRANSFORM, ActionGroup.SEND)
} else {
    listOf(ActionGroup.EXTRACT, ActionGroup.TRANSFORM, ActionGroup.USE, ActionGroup.SEND)
}

fun actionSections(bubbles: List<Bubble>, useFirst: Boolean = false): List<ActionSection> {
    return actionGroupOrder(useFirst).mapNotNull { group ->
        bubbles.filter { actionGroupOf(it.intent) == group }
            .takeIf { it.isNotEmpty() }
            ?.let { ActionSection(group, it) }
    }
}

