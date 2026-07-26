package com.point.desktop

import com.point.core.flow.decodePcMeta
import com.point.core.flow.encodePcMeta
import java.io.File
import java.net.InetAddress
import java.security.SecureRandom

/** The PC's identity: one long-lived token (reset revokes every phone at once). */
data class PcConfig(val token: String, val name: String, val port: Int)

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
        )
        if (stored.isEmpty()) save(config)
        return config
    }

    fun resetToken(): PcConfig = load().copy(token = newToken()).also(::save)

    private fun save(config: PcConfig) {
        file.writeText(
            encodePcMeta(
                mapOf("token" to config.token, "name" to config.name, "port" to config.port.toString()),
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
