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
}

interface AccountStore {

    fun current(): PointAccount?
    suspend fun save(account: PointAccount)
    suspend fun clear()
}

interface PendingLoginStore {
    fun current(): PendingLogin?
    suspend fun save(login: PendingLogin)
    suspend fun clear()
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
