package com.point.core.ui

import com.point.core.model.ActionYield
import com.point.core.model.Bubble
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.ObjectState

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
 * Знание поднимает своё действие (#937, решение владельца 13.08.2026).
 *
 * Человек делится ссылкой — «Открыть» стояло одиннадцатым, под свёрткой, ниже предложения
 * превратить ссылку в таблицу Excel. Единственное действие, ради которого ссылкой и делятся.
 * Порядок не спрашивал, что Point уже знает об объекте.
 *
 * Есть ссылка — выше «Открыть», есть номер — выше «Позвонить», есть адрес — выше карты.
 * Ничего не прячется: меняется только порядок групп, как и требует конституция — Intent
 * влияет на порядок, а не на список.
 */
fun knowsUsableValue(state: ObjectState): Boolean = USABLE_VALUE.any(state::has)

private val USABLE_VALUE = listOf(
    Feature.HAS_URL,
    Feature.HAS_PHONE,
    Feature.HAS_EMAIL,
    Feature.HAS_ADDRESS,
    Feature.HAS_GEO,
    Feature.HAS_QR,
)

/**
 * [useFirst] — объектом можно воспользоваться прямо сейчас: он сам является значением
 * (телефон, адрес, номер) либо такое значение про него известно.
 *
 * Внутри телефона «Исправить ошибки» стояло первым и подсвеченным, а «Позвонить» пряталось
 * ниже сгиба.
 */
fun actionGroupOrder(useFirst: Boolean = false): List<ActionGroup> = if (useFirst) {
    listOf(ActionGroup.USE, ActionGroup.EXTRACT, ActionGroup.TRANSFORM, ActionGroup.SEND)
} else {
    listOf(ActionGroup.EXTRACT, ActionGroup.TRANSFORM, ActionGroup.USE, ActionGroup.SEND)
}

/**
 * «Извлечь» ведёт, только когда есть что извлекать (#1101, #994; решение владельца 21.08.2026).
 *
 * У архива и у неизвестного файла первым и подсвеченным стояло «AI» — единственное, что
 * попало в группу «Извлечь»: оно берёт любой файл и не обещает ничего определённого («вернёт
 * то, что попросите», `ActionYield.Unknown`). Честное местное — «Открыть», «Сохранить»,
 * «Поделиться» — лежало ниже. А негодному объекту чтения не предлагаются вовсе, и вести
 * первым становилось «Дать ссылку».
 *
 * Читать есть что, когда хоть одно действие чтения обещает результат — текст, знание, — и
 * сам объект не сказал о себе, что с ним не вышло. Иначе первым идёт то, что точно
 * сработает. Ничего не прячется — меняется порядок групп, как и требует конституция.
 *
 * Про негодного решение владельца сказано тем же предложением: «У негодного/неизвестного
 * объекта герой — то, что точно сработает». Двери чтения у негодного могут и остаться —
 * когда негодность сказана о сорвавшейся попытке, экран зовёт попробовать ещё раз, и
 * пробовать должно быть чем (`offeredWhenUnfit`). Но вести первым им нечего: попытка только
 * что не удалась. Живой объект #994 — битый PDF: предпросмотр не открыл документ, а героем
 * стояло «Извлечь текст», рядом «Перевести» и «AI».
 *
 * Оговорки, которая есть у дверей — «а вдруг из содержимого уже что-то прочитано», — здесь
 * намеренно нет: там от неё зависело, исчезнет ли дверь совсем, а тут не исчезает ничего.
 * Меняется порядок, и цена ошибки — чтение вторым, а не пропавшее чтение.
 */
fun promisesExtraction(bubbles: List<Bubble>, state: ObjectState): Boolean =
    !state.has(Feature.UNUSABLE) && bubbles.any {
        actionGroupOf(it.intent) == ActionGroup.EXTRACT && promisesResult(it.yields)
    }

/**
 * Действие называет, что принесёт, — и это разбор по каждому исходу, а не «всё, кроме
 * одного» (#1101). «Ничего не вернёт» (`None`) обещанием чтения не является ровно так же,
 * как «вернёт то, что попросите» (`Unknown`): первое обещает пустоту, второе — что угодно.
 * Новый вид исхода обязан ответить здесь на тот же вопрос, а не унаследовать чужой ответ.
 */
private fun promisesResult(yields: ActionYield): Boolean = when (yields) {
    is ActionYield.New, is ActionYield.Same, ActionYield.Copied -> true
    ActionYield.None, ActionYield.Unknown -> false
}

fun actionSections(
    bubbles: List<Bubble>,
    state: ObjectState,
    useFirst: Boolean = false,
): List<ActionSection> {
    return actionGroupOrder(useFirst || !promisesExtraction(bubbles, state)).mapNotNull { group ->
        bubbles.filter { actionGroupOf(it.intent) == group }
            .takeIf { it.isNotEmpty() }
            ?.let { ActionSection(group, it) }
    }
}

