package com.point.desktop

import com.point.core.flow.decodePcMeta
import com.point.core.flow.encodePcMeta
import java.io.File
import java.net.InetAddress

/**
 * Что компьютер знает о себе.
 *
 * От прежней записи остались имя и адрес сервера. Токен быстрого пути по локальной сети и порт
 * своего HTTP-сервера ушли вместе с самой локальной сетью (#475): слушать компьютеру больше
 * нечего, а «своё имя в сети» перестало быть его делом.
 *
 * Кто владеет этим компьютером, живёт отдельно — в `~/.point-pc/account` ([FileAccountStore]);
 * ключи — в `~/.point-pc/keys` ([FileDeviceKeys]). Пропуск, ключ и имя машины — разные вещи с
 * разной судьбой.
 *
 * [server] — адрес сервера Point; пусто значит `PointServer.DEFAULT_URL`.
 */
data class PcConfig(val name: String, val server: String = "")

/**
 * Stored in `~/.point-pc/config` using the protocol's own k=v codec — dogfooding
 * [encodePcMeta], zero extra dependencies.
 */
class FilePcConfig(private val baseDir: File) {

    private val file: File get() = File(baseDir.apply { mkdirs() }, "config")

    fun load(): PcConfig {
        val stored = runCatching { decodePcMeta(file.readText()) }.getOrDefault(emptyMap())
        val config = PcConfig(
            name = stored["name"] ?: hostName(),
            server = stored["server"].orEmpty(),
        )
        if (stored["name"].isNullOrBlank()) save(config)
        return config
    }

    private fun save(config: PcConfig) {
        file.writeText(encodePcMeta(mapOf("name" to config.name, "server" to config.server)))
    }

    private fun hostName(): String =
        runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("Point PC")
}
