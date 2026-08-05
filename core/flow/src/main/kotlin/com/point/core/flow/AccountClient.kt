package com.point.core.flow

/**
 * Шов входа (#472, #473). За ним — разговор с сервером Point; перед ним — экран, который ничего
 * про HTTP не знает.
 *
 * Одна реализация на две платформы ([HttpAccountClient]) — ровно то, ради чего был выбран поток с
 * сервером-посредником: Compose Desktop и Android открывают браузер одинаково, а всё остальное
 * делает сервер. Ни `App Links`, ни своей схемы возврата, ни слушателя на `127.0.0.1` (последний на
 * Windows вызывал бы запрос брандмауэра при первом же входе).
 */
interface AccountClient {

    /**
     * «Я хочу войти»: сервер заводит вход и возвращает ссылку для браузера и код для сверки.
     * `null` — до сервера не дозвониться (отличается от отказа сервера, см. [accountRefusal]).
     */
    suspend fun start(deviceName: String, kind: DeviceKind): LoginStart?

    /** Один опрос: подтвердил ли человек вход в браузере. [claimToken] — из [LoginStart]. */
    suspend fun poll(loginId: String, claimToken: String): LoginPoll

    /**
     * Объявить кругу открытый ключ этого устройства (#475).
     *
     * Не «зарегистрироваться»: в круг устройство попало входом. Здесь оно лишь рассказывает
     * остальным, **чем с ним говорить**, — закрытая половина ключа не покидает устройство никогда.
     * Молчаливая работа: не вышло — попробуем в следующий раз, человеку об этом сказать нечего.
     */
    suspend fun enroll(account: PointAccount, publicKey: String): Boolean

    /** Круг устройств аккаунта. Пустой список — законный ответ; см. [CircleAnswer]. */
    suspend fun circle(account: PointAccount): CircleAnswer

    /**
     * Отключить устройство круга — своё или чужое. Любое устройство круга может отключить любое:
     * телефон потерян, а отключать его надо с того, что осталось в руках.
     */
    suspend fun revoke(account: PointAccount, deviceId: String): Boolean

    /**
     * «Выйти» — это отзыв ЭТОГО устройства, а не отдельная дверь.
     *
     * Своей ручки у выхода нет намеренно (решение сервера, #471): «выйти» и «отключить это
     * устройство» — одно и то же событие, и две ручки об одном однажды разошлись бы.
     */
    suspend fun signOut(account: PointAccount): Boolean = revoke(account, account.deviceId)
}

/** Где хранится пропуск. Телефон шифрует, компьютер кладёт файлом только для владельца. */
interface AccountStore {
    /** Тёплое синхронное чтение — как у остальных крошечных хранилищ проекта. */
    fun current(): PointAccount?
    suspend fun save(account: PointAccount)
    suspend fun clear()
}

/**
 * Где лежит начатый, но не законченный вход (#561).
 *
 * Отдельный шов, а не поле в [AccountStore]: у них разные сроки жизни и разный смысл. Пропуск —
 * годы, начатый вход — пять минут; пропуск говорит «мы вошли», начатый вход — «мы ещё спрашиваем».
 * Хранится он там же, где пропуск (шифрованно), потому что [PendingLogin.claimToken] — секрет
 * устройства: по нему забирается пропуск ко всем объектам аккаунта.
 */
interface PendingLoginStore {
    fun current(): PendingLogin?
    suspend fun save(login: PendingLogin)
    suspend fun clear()
}

/**
 * Начатый вход в памяти — умолчание для тех, кто живёт дольше браузера.
 *
 * Окно на компьютере не уничтожают, пока человек ходит в браузер: там опрос переживает поход сам.
 * Телефону этого мало, и он подставляет сюда шифрованное хранилище.
 */
class InMemoryPendingLogins : PendingLoginStore {
    private var login: PendingLogin? = null
    override fun current(): PendingLogin? = login
    override suspend fun save(login: PendingLogin) { this.login = login }
    override suspend fun clear() { login = null }
}

/**
 * Адрес сервера Point.
 *
 * Константа сборки **без секрета** — переезд на другой домен правится одной строкой, как владелец и
 * просил. Раньше адрес приезжал из `local.properties` вместе с общим паролем приложения; пароля
 * больше нет (#419), а адрес секретом никогда и не был.
 */
object PointServer {
    const val DEFAULT_URL = "https://point.leerio.app"

    /** Нормализованная база: без хвостовой косой, пустое — значит «нечем говорить». */
    fun base(url: String?): String = (url?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_URL).trimEnd('/')
}
