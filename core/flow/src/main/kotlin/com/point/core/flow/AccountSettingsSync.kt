package com.point.core.flow

/**
 * Настройки едут за человеком через сервер (#610, решение владельца 10.08.2026).
 *
 * Один свод на обе стороны: телефон и компьютер сводят своё с тем, что лежит на сервере,
 * одним и тем же кодом. Порознь у них разъехались бы правила слияния — молча и незаметно,
 * потому что каждая сторона считала бы себя правой.
 *
 * Сервер в этом обмене — почтовый ящик: он получает запечатанное и отдаёт запечатанное.
 */
class AccountSettingsSync(
    private val client: AccountClient,
    private val seal: SettingsSeal = SettingsSeal(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Свести своё с общим и вернуть итог.
     *
     * `null` — свести не вышло (сеть, отзыв пропуска, некому запечатать): своё при этом
     * остаётся как было. Молчаливая потеря настроек хуже несостоявшегося обмена.
     */
    suspend fun sync(account: PointAccount, keys: DeviceKeyPair, mine: AccountSettings): AccountSettings? {
        val circle = (client.circle(account) as? CircleAnswer.Circle)?.devices ?: return null

        val theirs = client.settings(account)
            ?.let { seal.open(it, account.deviceId, keys.privateKey) }
            ?.let(AccountSettings::decode)
            ?: AccountSettings()

        val merged = theirs.mergedWith(mine)
        if (merged == theirs) return merged

        // Своё новее общего — значит, у общего появилась новая отметка времени: иначе
        // сервер отказал бы, а правка человека потерялась бы молча.
        val stamped = merged.copy(at = maxOf(merged.at, clock()))
        val sealed = seal.seal(stamped.encode(), circle, stamped.at) ?: return null
        return if (client.saveSettings(account, sealed)) stamped else null
    }
}
