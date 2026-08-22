package com.point.executors

import com.point.core.flow.AccountStore
import com.point.core.flow.DeviceKind
import com.point.core.flow.PointAccount

/**
 * Аккаунт Point для тестов боевого набора способностей (#1022).
 *
 * `null` — на этом устройстве не вошли. Значение читается на каждый вопрос: человек входит и
 * выходит, не перезапуская Point, и собранный однажды набор обязан видеть нынешнее положение.
 */
internal class AccountForTests(var now: PointAccount? = SOMEBODY) : AccountStore {
    override fun current() = now
    override suspend fun save(account: PointAccount) { now = account }
    override suspend fun clear() { now = null }
}

/** Кто-то вошёл- какой именно человек, тестам набора безразлично. */
internal val SOMEBODY = PointAccount("d-1", "токен", "kto@example.com", "Телефон", DeviceKind.PHONE)
