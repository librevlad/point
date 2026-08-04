package com.point.desktop

import com.point.core.flow.decodePcMeta
import com.point.core.flow.encodePcMeta
import java.io.File
import java.net.InetAddress
import java.security.SecureRandom

/**
 * Что компьютер знает о себе.
 *
 * [token] — пропуск быстрого пути по локальной сети. Он уже не общий и не ездит ни в каком QR
 * — это внутреннее служебное значение одного хопа, и срез 6 (#475) снимает его совсем, заменяя
 * подписанным кадром. Кто владеет этим компьютером, живёт отдельно — в `~/.point-pc/account`
 * ([FileAccountStore]): пропуск аккаунта и адрес машины в сети — разные вещи с разной судьбой.
 *
 * [server] — адрес сервера Point; пусто значит `PointServer.DEFAULT_URL`. Раньше адрес запекался
 * в сборку задачей `generateRelayEnv` вместе с общим паролем приложения; пароля больше нет (#419),
 * а адрес — обычная настройка рядом с именем и портом.
 */
data class PcConfig(val token: String, val name: String, val port: Int, val server: String = "")

/**
 * Stored in `~/.point-pc/config` using the protocol's own k=v codec — dogfooding
 * [encodePcMeta], zero extra dependencies.
 */
class FilePcConfig(private val baseDir: File) {

    private val file: File get() = File(baseDir.apply { mkdirs() }, "config")

    fun load(): PcConfig {
        val stored = runCatching { decodePcMeta(file.readText()) }.getOrDefault(emptyMap())
        val config = PcConfig(
            token = stored["token"] ?: newToken(),
            name = stored["name"] ?: hostName(),
            port = stored["port"]?.toIntOrNull() ?: DEFAULT_PORT,
            server = stored["server"].orEmpty(),
        )
        if (stored.isEmpty()) save(config)
        return config
    }

    fun resetToken(): PcConfig = load().copy(token = newToken()).also(::save)

    private fun save(config: PcConfig) {
        file.writeText(
            encodePcMeta(
                mapOf(
                    "token" to config.token,
                    "name" to config.name,
                    "port" to config.port.toString(),
                    "server" to config.server,
                ),
            ),
        )
    }

    private fun newToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hostName(): String =
        runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("Point PC")

    companion object {
        const val DEFAULT_PORT = 8391
    }
}
