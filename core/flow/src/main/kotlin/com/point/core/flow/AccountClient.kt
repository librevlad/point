package com.point.core.flow

interface AccountClient {

    suspend fun start(deviceName: String, kind: DeviceKind): LoginStart?

    suspend fun poll(loginId: String, claimToken: String): LoginPoll

    suspend fun enroll(account: PointAccount, publicKey: String): Boolean

    suspend fun circle(account: PointAccount): CircleAnswer

    suspend fun revoke(account: PointAccount, deviceId: String): Boolean

    suspend fun signOut(account: PointAccount): Boolean = revoke(account, account.deviceId)

    suspend fun deleteAccount(account: PointAccount): Boolean

    /**
     * Настройки аккаунта в закрытом виде (#610). Сервер хранит нечитаемое и расшифровать
     * не может: он для этих байтов — почтовый ящик, а не читатель.
     */
    suspend fun settings(account: PointAccount): SealedSettings? = null

    /** `false` — не сохранилось: сеть, отзыв пропуска или чужие настройки оказались новее. */
    suspend fun saveSettings(account: PointAccount, sealed: SealedSettings): Boolean = false

    /**
     * Сказать серверу, куда стучать в это устройство (#817).
     *
     * Адрес — про себя и только про себя. Пустая строка стирает его: человек выключил
     * уведомления, и стучать больше некуда.
     */
    suspend fun tellPushAddress(account: PointAccount, address: String): Boolean = false

    /**
     * Попросить сервер постучать в другое своё устройство: «зайди, для тебя что-то есть».
     *
     * `false` — не постучали. Это не отказ в работе: просьба всё равно ждёт, и человек
     * разберёт её, когда откроет Point сам.
     */
    suspend fun knock(account: PointAccount, deviceId: String): Boolean = false
}

interface AccountStore {

    fun current(): PointAccount?
    suspend fun save(account: PointAccount)
    suspend fun clear()
}

/**
 * Последний успешно полученный круг устройств (#1076).
 *
 * Круг живёт на сервере, но без сети список пуст — и экран выдавал незнание за одиночество:
 * «пока вы один» при живом компьютере в круге. Телефон помнит последний ответ сервера рядом
 * с пропуском; офлайн показывает его, а не пустоту. `null` — круга не было никогда:
 * это не то же самое, что «в круге никого нет».
 */
interface CircleStore {

    fun current(): List<CircleDevice>?
    suspend fun save(devices: List<CircleDevice>)
    suspend fun clear()
}

interface PendingLoginStore {
    fun current(): PendingLogin?
    suspend fun save(login: PendingLogin)
    suspend fun clear()
}

class InMemoryCircleStore : CircleStore {
    private var devices: List<CircleDevice>? = null
    override fun current(): List<CircleDevice>? = devices
    override suspend fun save(devices: List<CircleDevice>) { this.devices = devices }
    override suspend fun clear() { devices = null }
}

class InMemoryPendingLogins : PendingLoginStore {
    private var login: PendingLogin? = null
    override fun current(): PendingLogin? = login
    override suspend fun save(login: PendingLogin) { this.login = login }
    override suspend fun clear() { login = null }
}

object PointServer {
    const val DEFAULT_URL = "https://point.leerio.app"

    fun base(url: String?): String = (url?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_URL).trimEnd('/')
}
