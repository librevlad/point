package com.point.core.flow

import kotlinx.coroutines.flow.Flow

/** A Point-for-PC instance seen in the local network (#147 slice C). */
data class DiscoveredPc(val name: String, val host: String, val port: Int)

/**
 * LAN discovery of `_point-pc._tcp` services. Emits the current list as it changes;
 * collection stops the underlying scan. Discovery is SUGAR over manual host:port —
 * flaky networks (AP isolation, emulator NAT) must never block pairing.
 */
fun interface PcDiscovery {
    fun discover(): Flow<List<DiscoveredPc>>
}

/**
 * Идёт ли поиск компьютеров в сети (#458).
 *
 * Экран рисовал «Найдено в сети», только когда что-то уже нашлось, — и первые секунды выглядели
 * как «ничего нет, вводите руками». Состояния три, а не флаг, по той же причине, по какой их три
 * у ожидания файла (#114): «ещё ищу» и «искал, не нашёл» — разные ответы, и молчание вместо
 * второго снова оставляет человека гадать.
 */
enum class PcSearch {
    /** Ещё не искали — экран только открылся. */
    IDLE,

    /** Ищем прямо сейчас: у ожидания виден пульс. */
    RUNNING,

    /** Искали и закончили — «в этой сети никого» это ответ, а не тишина. */
    DONE,
}

/**
 * Сколько ждём ответа сети, прежде чем сказать «никого не видно».
 *
 * mDNS отвечает за секунду-две — либо не отвечает НИКОГДА, если роутер разводит клиентов
 * изоляцией. Пульс без конца — та же ловушка, что «Ждём файл…» навсегда: ожидание обязано
 * кончаться словом.
 */
const val PC_SEARCH_WINDOW_MS = 5_000L

/**
 * Что сказать про поиск. `null` — говорить нечего: список нашедшихся говорит сам за себя, а до
 * начала поиска не о чем.
 */
fun pcSearchLine(search: PcSearch, found: Int): String? = when {
    found > 0 -> null
    search == PcSearch.RUNNING -> "Ищу компьютеры в этой сети…"
    search == PcSearch.DONE -> "В этой сети компьютеров не видно"
    else -> null
}
