package com.point.core.flow

/**
 * Роль без правдоподобного имени — не человек (#654): модель, отвечая ролью на целый
 * текст или номер, не рождает «человека» из документа. Общее правило обеих сторон
 * протокола понимания (#653: пары «имя+номер» фильтруются им же).
 */
fun plausiblePersonName(text: String): Boolean {
    val t = text.trim()
    if (t.isEmpty() || t.length > 60) return false
    if (!t.any(Char::isLetter)) return false

    // Группа цифр — номер или сумма, не имя; одиночная цифра в слове — искажение
    // распознавания («1ваненко ван»), это ещё имя.
    if (Regex("""\d{2,}""").containsMatchIn(t)) return false
    if (t.count(Char::isDigit) > t.length / 5) return false
    return t.split(Regex("""\s+""")).size <= 5
}

/** Сторона «чей это контакт» — ею подписан и узел человека, и объект, где он найден. */
const val CONTACT_ROLE = "contact"

/**
 * Человек, названный при номере, — знание самого объекта (#993).
 *
 * Пара «номер | имя» рождала только узел человека, и на визитке, где стоит «Сохранить
 * контакт», имени не оставалось: системная карточка открывалась с пустыми полями, и
 * человек вписывал руками то, что Point уже прочитал и показывает рядом. Владелец номера —
 * такая же сторона объекта, как отправитель накладной, и живёт тем же ключом роли: второго
 * хранилища не заводится, узел человека по-прежнему строится из той же пары.
 *
 * Названный один — знание объекта; названных несколько — «того самого» человека у объекта
 * нет, и каждый остаётся при своём узле: выбрать одного из троих значило бы выдумать.
 *
 * [parties] — стороны, названные УЖЕ: и этим витком, и прежним знанием объекта. Сверяться
 * только с ответом текущего витка было мало: второй «Понять», не повторивший строку роли,
 * заводил тому же человеку вторую сторону — один человек оказывался и отправителем, и
 * контактом одного объекта.
 *
 * Слово модели становится знанием самого объекта, поэтому спрашивается с него так же, как с
 * прочтения поля (#809, «нет в тексте — нет знания»): Point прочитал страницу — имя обязано в
 * ней стоять, иначе ошибка модели уехала бы в системную карточку контакта как факт. Читать
 * нечем ([readText] пуст, зрячее чтение снимка) — сверять не с чем, и слово модели остаётся.
 */
fun contactParty(
    contacts: List<PersonContact>,
    parties: Map<String, String>,
    readText: String,
): Map<String, String> {
    val name = contacts.map { it.name }.distinctBy(::normalizedParty).singleOrNull() ?: return emptyMap()
    if (readText.isNotBlank() && !standsInReadText(name, readText)) return emptyMap()
    if (partyValues(parties).any { normalizedParty(it) == normalizedParty(name) }) return emptyMap()
    return mapOf(META_GRAPH_ROLE_PREFIX + CONTACT_ROLE to name)
}

/**
 * Имя человека, о котором говорит знание объекта (#993, решение владельца «имя из графа»).
 *
 * Действию нужен человек, а не похожая на имя строка текста: именем становится только то,
 * что уже стало знанием, — сторона объекта. Чей это факт, говорит принадлежность (#1176):
 * у номера с наклейки хозяин назван, и в карточку контакта едет имя владельца знания [about],
 * а не первая попавшаяся сторона документа.
 *
 * **Хозяин назван — вопрос закрыт им одним.** Он человек — это и есть имя; он организация —
 * имени нет. Проваливаться после названного хозяина к запасному шагу нельзя: у наклейки, где
 * номер принадлежит «ТОВ «Агротрейд»», а вторая сторона — Петренко Петро, карточка с номером
 * организации подписалась бы Петренко — ровно та подмена хозяина, ради которой правило и
 * писано. Хозяева знания карточки разошлись — «того самого» человека тоже нет.
 *
 * Хозяин не назван вовсе — годится единственное человеческое имя объекта; имён несколько —
 * Point не выбирает за человека, и карточка честно открывается без имени.
 */
fun personNameOf(facts: Map<String, String>, about: List<String>): String? {
    val owners = about.mapNotNull { ownerKeyOf(facts, it) }
        .distinctBy { normalizedParty(facts.getValue(it)) }
    if (owners.isNotEmpty()) {
        return owners.singleOrNull()
            ?.let { owner -> facts.getValue(owner).takeIf { isPersonParty(owner, it) } }
    }
    return partyValues(facts, persons = true)
        .distinctBy(::normalizedParty)
        .singleOrNull()
}

/** Стороны объекта: знание витка живёт теми же ключами роли, что и уже накопленное. */
private fun partyValues(facts: Map<String, String>, persons: Boolean = false): List<String> =
    facts.entries
        .filter { (key, value) -> isPartyKey(key) && value.isNotBlank() }
        .filter { (key, value) -> !persons || isPersonParty(key, value) }
        .map { it.value }

private fun isPartyKey(key: String): Boolean =
    key.startsWith(META_GRAPH_ROLE_PREFIX) && !isAnnotationKey(key) && !isStateKey(key)

private fun isPersonParty(key: String, value: String): Boolean =
    plausiblePersonName(value) && (roleOfKey(key)?.kindFor(value) ?: KIND_PERSON) == KIND_PERSON
