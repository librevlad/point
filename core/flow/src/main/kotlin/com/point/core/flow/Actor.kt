package com.point.core.flow

import com.point.core.model.ActionResult
import com.point.core.model.PointObject

/**
 * Кто именно добыл это знание (#1127).
 *
 * `Provenance` называет класс пути — правило, чтение, модель, человек. Этого хватает, чтобы
 * сказать «прочитано», и не хватает, чтобы сравнить двух исполнителей одного вопроса:
 * Тессеракт на устройстве и облачный читатель оба «прочитали», а расходятся по-разному.
 *
 * Имя исполнителя живёт **рядом с путём**, а не становится знанием об объекте (решение
 * владельца 18.08.2026): это ключ-аннотация — строкой на экран он не выходит, узла не
 * рождает и находкой не считается. На экране остаются человеческие слова про класс пути,
 * а имя сервиса — внутренняя опора для сверки, диагноза и повторного выбора исполнителя.
 *
 * Исполнителей у одного значения может быть несколько: когда второй путь принёс то же самое,
 * знание одно, а путей к нему два — оба сохраняются.
 */
const val META_ACTOR_SUFFIX = ".by"

/**
 * Кто ответил на запрос к цепочке сервисов.
 *
 * Живёт в метаданных самого ответа, а не в знании объекта: имя нужно тому, кто разбирает
 * ответ и кладёт из него факты — он и подпишет ими знание.
 */
const val META_ANSWERED_BY = "answered" + META_ACTOR_SUFFIX

private const val ACTOR_SEPARATOR = ","

fun actorsOf(metadata: Map<String, String>, key: String): List<String> = actorList(metadata[key + META_ACTOR_SUFFIX])

fun actorList(value: String?): List<String> =
    value?.split(ACTOR_SEPARATOR)?.map(String::trim)?.filter { it.isNotBlank() }.orEmpty()

fun actorValue(actors: List<String>): String = actors.distinct().joinToString(ACTOR_SEPARATOR)

/**
 * Назвать исполнителя у знания, которое пришло без имени.
 *
 * Уже названное не переписывается: цепочка сервисов знает, кто именно ответил, а исполнитель
 * поверх неё — только себя.
 */
fun withActor(facts: Map<String, String>, actor: String): Map<String, String> =
    withActor(facts, actor, facts.keys)

fun withActor(facts: Map<String, String>, actor: String, keys: Collection<String>): Map<String, String> {
    if (actor.isBlank() || facts.isEmpty()) return facts
    val named = keys.filter { it in facts }
        .filterNot { isAnnotationKey(it) || isStateKey(it) }
        .filter { facts[it + META_ACTOR_SUFFIX].isNullOrBlank() }
    if (named.isEmpty()) return facts
    return facts + named.associate { it + META_ACTOR_SUFFIX to actor }
}

/**
 * Добавить исполнителя к знанию, которое он наблюдал сам.
 *
 * Тот же путь, что и у совпавших прочтений: значение одно, исполнителей у него столько,
 * сколько его увидели.
 */
fun addActor(facts: Map<String, String>, keys: Collection<String>, actor: String): Map<String, String> {
    if (actor.isBlank()) return facts
    val named = keys.filter { it in facts }.filterNot { isAnnotationKey(it) || isStateKey(it) }
    if (named.isEmpty()) return facts
    return facts + named.associate { key ->
        key + META_ACTOR_SUFFIX to actorValue(actorsOf(facts, key) + actor)
    }
}

/**
 * Отметить именем исполнителя знание, которое принёс этот заход.
 *
 * Отмечается только то, что этот заход и правда добыл: новое значение или изменившееся.
 * Исследование вроде «Понять» возвращает вместе со своими находками всё известное знание
 * объекта — и без этого правила именем модели оказалось бы подписано в том числе
 * прочитанное с кадра задолго до неё.
 */
fun findingsBy(known: Map<String, String>, fresh: Map<String, String>, actor: String): Map<String, String> =
    withActor(fresh, actor, fresh.keys.filter { known[it] != fresh[it] })

/**
 * Отметить исполнителем то, что он принёс: и знание об объекте, и найденные им узлы.
 *
 * Зовётся там, где исполнитель уже выбран Resolver-ом, — знание получает имя на общем шве,
 * а не тринадцатью разными способами внутри каждого исследования.
 */
fun ActionResult.knownBy(input: PointObject, actor: String): ActionResult {
    if (actor.isBlank() || this !is ActionResult.Done) return this
    val found = findings ?: return this
    return copy(
        findings = found.copy(
            metadata = findingsBy(input.metadata, found.metadata, actor),
            objects = found.objects.map { it.copy(metadata = withActor(it.metadata, actor)) },
        ),
    )
}

/**
 * Знание и узлы, названные своим исполнителем.
 *
 * Для исполнителя, который выбирает движок сам: имя известно ему точнее, чем шву снаружи.
 */
fun com.point.core.model.Findings.by(actor: String): com.point.core.model.Findings =
    if (actor.isBlank()) {
        this
    } else {
        copy(
            metadata = withActor(metadata, actor),
            objects = objects.map { it.copy(metadata = withActor(it.metadata, actor)) },
        )
    }
