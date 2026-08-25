package com.point.core.flow

data class RealizerMeta(

    val priority: Int = 50,
    val kind: RealizerKind = RealizerKind.LOCAL,

    /**
     * Имя исполнителя в evidence знания (#1127).
     *
     * Не человеческая подпись и не показывается на экране: по нему сверяются два прочтения
     * одного вопроса и объясняется расхождение. Пусто — исполнитель называет себя сам
     * (цепочка сервисов знает, кто именно ответил) или знания не приносит вовсе.
     */
    val actor: String = "",
)

enum class RealizerKind { LOCAL, CLOUD, REMOTE }
