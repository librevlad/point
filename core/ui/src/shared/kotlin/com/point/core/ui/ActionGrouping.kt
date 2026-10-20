package com.point.core.ui

import com.point.core.flow.KIND_ADDRESS
import com.point.core.flow.KIND_AMOUNT
import com.point.core.flow.KIND_DATE
import com.point.core.flow.KIND_EMAIL
import com.point.core.flow.KIND_IDENTIFIER
import com.point.core.flow.KIND_ORGANIZATION
import com.point.core.flow.KIND_PERSON
import com.point.core.flow.KIND_PHONE
import com.point.core.flow.KIND_PLACE
import com.point.core.flow.KIND_URL
import com.point.core.model.ActionYield
import com.point.core.model.Bubble
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject

/*
 * Действия раскладываются по тому, о чём они, — одно правило на телефон и компьютер (#879).
 *
 * Прежде разделы назывались видом функции: «Сделать», «Извлечь», «Превратить»,
 * «Отправить». Это язык каталога, а человек думает от вещи: вот визитка — на ней телефон,
 * позвонить; адрес — посмотреть, где это; а саму визитку — сохранить. Вещь → её смыслы →
 * естественные следующие шаги.
 *
 * Поэтому разделы — по тому, о чём действие и чем оно кончается:
 *  - найденное значение и его продолжения — «Телефон · 067 123 4567»: позвонить, написать,
 *    сохранить контакт (что действие продолжает, решает `becauseOf` в `:core:flow`);
 *  - понимание вещи — «Разобраться»: прочитать, перевести, спросить; знание ложится НА вещь;
 *  - новое из вещи — «Сделать из документа»: PDF, Word, скан; результат — другая вещь ИЗ неё;
 *  - сама вещь дальше — «С документом»: открыть, сохранить, поделиться; вещь уходит ЧЕРЕЗ
 *    приложение, устройство или человека.
 *
 * Логика чистая: она смотрит на действия и состояние объекта и ни на что больше. Каталог
 * `src/shared/kotlin` компилируют обе стороны, каждая своим Compose.
 */

const val LIKELY_COUNT = 3

fun likelyCount(total: Int): Int = if (total <= LIKELY_COUNT + 2) total else LIKELY_COUNT

/**
 * Сколько строк раздела видно сразу.
 *
 * Свёртка (#879) заведена против стены превращений и чтений: у снимка их два десятка, и
 * «помочь выбрать» превращалось в «вывалить всё». Сама вещь дальше — открыть, сохранить,
 * поделиться, отправить на компьютер — не сворачивается: это выходы, ради которых вещью и
 * делятся, и их всегда немного. Свёрнутое «Поделиться» у текста было бы Point, который
 * прячет дверь наружу.
 */
fun shownAtOnce(section: ActionSection<*>): Int =
    if (section.topic == SectionTopic.THING) section.items.size else likelyCount(section.items.size)

/** О чём раздел действий. */
enum class SectionTopic { VALUE, UNDERSTAND, MAKE, THING }

const val UNDERSTAND_TITLE = "Разобраться"

/**
 * Раздел действий.
 *
 * [value] — само найденное значение у раздела значения («067 123 4567»), чтобы человек видел,
 * *какой* номер наберётся. [leads] — первая строка раздела и есть самое обоснованное
 * продолжение: её подсвечивают.
 */
data class ActionSection<T>(
    val topic: SectionTopic,
    val key: String,
    val title: String,
    val items: List<T>,
    val value: String? = null,
    val leads: Boolean = false,
)

/**
 * Разделы действий объекта.
 *
 * Найденные значения идут первыми и в том порядке, в каком ранжирование ставит их лучшее
 * действие (#937, решение владельца 13.08.2026: знание поднимает своё действие — есть
 * ссылка, выше «Открыть»; есть номер, выше «Позвонить»). Ничего не прячется: меняется
 * только порядок, как и требует конституция — Intent влияет на порядок, а не на список.
 *
 * Подсвечивается только продолжение найденного — то, для чего у Point есть основание.
 * У вещи без находок главного действия нет: подсветить «Извлечь текст» или «Открыть»
 * значит угадать за человека, а не понять. Исключение — вещь, которая сама значение
 * ([thingIsValue]: номер, адрес, человек): её продолжения и есть продолжения значения.
 *
 * «Сделать из …» стоит отдельно от «С …» затем, чтобы то, ради чего вещью делятся, не
 * утонуло под свёрткой: в одном разделе с десятком превращений «Поделиться» уходило за
 * «Показать ещё» у каждого текста (см. [shownAtOnce]).
 *
 * Действие, у которого нет пузыря ([bubbleOf] вернул `null`, — просьба к телефону на
 * компьютере), принадлежит самой вещи.
 */
fun <T> actionSections(
    items: List<T>,
    bubbleOf: (T) -> Bubble?,
    state: ObjectState,
    thing: String,
    thingIsValue: Boolean = false,
    make: String = MAKE_TITLE,
): List<ActionSection<T>> {
    val values = items
        .filter { bubbleOf(it)?.because != null }
        .groupBy { bubbleOf(it)!!.because!!.key }
        .map { (key, rows) ->
            val because = bubbleOf(rows.first())!!.because!!
            ActionSection(SectionTopic.VALUE, key, because.name, rows, value = because.value)
        }

    val aboutThing = items.filter { bubbleOf(it)?.because == null }
    fun about(intent: Intent) = aboutThing.filter { bubbleOf(it)?.intent == intent }
    val understand = about(Intent.UNDERSTAND)
    val made = about(Intent.PREPARE)
    val own = aboutThing.filterNot { bubbleOf(it)?.intent == Intent.UNDERSTAND || bubbleOf(it)?.intent == Intent.PREPARE }

    val understandSection = ActionSection(SectionTopic.UNDERSTAND, "understand", UNDERSTAND_TITLE, understand)
    val makeSection = ActionSection(SectionTopic.MAKE, "make", make, made)
    val thingSection = ActionSection(SectionTopic.THING, "thing", thing, own)
    val understandFirst = !thingIsValue && promisesExtraction(understand.mapNotNull(bubbleOf), state)
    val rest = if (understandFirst) {
        listOf(understandSection, makeSection, thingSection)
    } else {
        listOf(thingSection, makeSection, understandSection)
    }

    val sections = (values + rest).filter { it.items.isNotEmpty() }
    val first = sections.firstOrNull() ?: return sections
    val leads = first.topic == SectionTopic.VALUE || (thingIsValue && first.topic == SectionTopic.THING)
    return if (leads) listOf(first.copy(leads = true)) + sections.drop(1) else sections
}

fun actionSections(
    bubbles: List<Bubble>,
    state: ObjectState,
    thing: String,
    thingIsValue: Boolean = false,
    make: String = MAKE_TITLE,
): List<ActionSection<Bubble>> = actionSections(bubbles, { it }, state, thing, thingIsValue, make)

/** Раздел нового из вещи, когда вид вещи не назван. */
const val MAKE_TITLE = "Сделать из этого"

/**
 * «Разобраться» идёт раньше самой вещи, только когда есть что понимать (#1101, #994;
 * решение владельца 21.08.2026).
 *
 * У архива и у неизвестного файла первым стояло «AI» — единственное чтение, оно берёт любой
 * файл и не обещает ничего определённого («вернёт то, что попросите», `ActionYield.Unknown`).
 * Честное местное — «Открыть», «Сохранить», «Поделиться» — лежало ниже. А негодному объекту
 * чтения не предлагаются вовсе.
 *
 * Понимать есть что, когда хоть одно чтение самой вещи обещает результат — текст, знание, — и
 * сам объект не сказал о себе, что с ним не вышло. Иначе первой идёт вещь: то, что точно
 * сработает. Ничего не прячется — меняется порядок разделов.
 *
 * Про негодного решение владельца сказано тем же предложением: «У негодного/неизвестного
 * объекта герой — то, что точно сработает». Двери чтения у негодного могут и остаться —
 * когда негодность сказана о сорвавшейся попытке, экран зовёт попробовать ещё раз, и
 * пробовать должно быть чем (`offeredWhenUnfit`). Но вести первыми им нечего: попытка только
 * что не удалась (живой объект #994 — битый PDF, «Извлечь текст» стояло первым).
 */
fun promisesExtraction(bubbles: List<Bubble>, state: ObjectState): Boolean =
    !state.has(Feature.UNUSABLE) && bubbles.any {
        it.because == null && it.intent == Intent.UNDERSTAND && promisesResult(it.yields)
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

/**
 * Как зовётся раздел самой вещи — «С документом», «Со ссылкой».
 *
 * Слово то же, каким вещь названа на экране (`kindMarkOf`, `kindLabel`), только в том
 * падеже, в каком о ней говорят «что сделать с …».
 */
fun thingTitle(obj: PointObject): String {
    if (obj.state.has(Feature.IS_PRESENTATION)) return "С презентацией"
    return when (kindMarkOf(obj)) {
        KindMark.SPREADSHEET -> "С таблицей"
        KindMark.TEXT -> "С текстом"
        KindMark.IMAGE -> "С изображением"
        KindMark.PDF -> "С PDF"
        KindMark.ARCHIVE -> "С архивом"
        KindMark.AUDIO -> "С записью"
        KindMark.LINK -> "Со ссылкой"
        KindMark.COLLECTION -> "С коллекцией"
        KindMark.DOCUMENT -> "С документом"
        KindMark.UNKNOWN -> valueTitle(obj.state.kind)
    }
}

/**
 * Как зовётся раздел нового из вещи — «Сделать из документа», «Сделать из ссылки».
 * Тот же вид, что в [thingTitle], в том падеже, в каком говорят «сделать из …».
 */
fun makeTitle(obj: PointObject): String {
    if (obj.state.has(Feature.IS_PRESENTATION)) return "Сделать из презентации"
    return "Сделать из " + when (kindMarkOf(obj)) {
        KindMark.SPREADSHEET -> "таблицы"
        KindMark.TEXT -> "текста"
        KindMark.IMAGE -> "изображения"
        KindMark.PDF -> "PDF"
        KindMark.ARCHIVE -> "архива"
        KindMark.AUDIO -> "записи"
        KindMark.LINK -> "ссылки"
        KindMark.COLLECTION -> "коллекции"
        KindMark.DOCUMENT -> "документа"
        KindMark.UNKNOWN -> valueFrom(obj.state.kind)
    }
}

private fun valueFrom(kind: ObjectKind): String = when (kind) {
    KIND_PHONE -> "номера"
    KIND_EMAIL -> "почты"
    KIND_ADDRESS -> "адреса"
    KIND_DATE -> "даты"
    KIND_URL -> "ссылки"
    KIND_IDENTIFIER -> "номера"
    KIND_ORGANIZATION -> "организации"
    KIND_PERSON -> "человека"
    KIND_AMOUNT -> "суммы"
    KIND_PLACE -> "места"
    else -> "файла"
}

private fun valueTitle(kind: ObjectKind): String = when (kind) {
    KIND_PHONE -> "С номером"
    KIND_EMAIL -> "С почтой"
    KIND_ADDRESS -> "С адресом"
    KIND_DATE -> "С датой"
    KIND_URL -> "Со ссылкой"
    KIND_IDENTIFIER -> "С номером"
    KIND_ORGANIZATION -> "С организацией"
    KIND_PERSON -> "С человеком"
    KIND_AMOUNT -> "С суммой"
    KIND_PLACE -> "С местом"
    else -> "С файлом"
}

/** Вещь сама значение — найденный номер, адрес, человек: её продолжения обоснованы ею же. */
fun thingIsValue(obj: PointObject): Boolean =
    obj.uri is com.point.core.model.ValueRef || obj.state.kind in com.point.core.flow.EXTRACTED_KINDS
