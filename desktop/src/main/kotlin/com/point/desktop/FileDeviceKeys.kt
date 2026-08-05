package com.point.desktop

import com.point.core.flow.DeviceKeyPair
import com.point.core.flow.DeviceKeyStore
import com.point.core.flow.DeviceKeys
import com.point.core.flow.decodePcMeta
import com.point.core.flow.encodePcMeta
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * Ключи этого компьютера (#475) — `~/.point-pc/keys`, правами только владельцу.
 *
 * Тем же `k=v`-кодеком, что пропуск и настройки: своего формата ПК не заводит. Шифровать файл
 * смысла нет, и об этом честно — злоумышленник с правами пользователя на этой машине побеждает в
 * любом случае; шифрование добавило бы обряд, а не защиту. Права ставятся там, где система их
 * понимает (POSIX); на Windows их роль играет профиль пользователя.
 *
 * Пара рождается один раз. Пережить «Выйти» она обязана: сменившийся ключ означал бы, что круг
 * знает про этот компьютер неправду, и всё, что для него уже положили в ящик, стало бы нечитаемым.
 */
class FileDeviceKeys(private val baseDir: File) : DeviceKeyStore {

    private val file: File get() = File(baseDir.apply { mkdirs() }, "keys")

    @Volatile
    private var cache: DeviceKeyPair? = null

    @Synchronized
    override fun keys(): DeviceKeyPair {
        cache?.let { return it }
        val stored = runCatching { decodePcMeta(file.readText()) }.getOrDefault(emptyMap())
        val secret = stored["private"]?.takeIf { it.isNotBlank() }
        val public = stored["public"]?.takeIf { it.isNotBlank() }
        val pair = if (secret != null && public != null) {
            DeviceKeyPair(secret, public)
        } else {
            DeviceKeys.generate().also { fresh ->
                runCatching {
                    file.writeText(encodePcMeta(mapOf("private" to fresh.privateKey, "public" to fresh.publicKey)))
                    ownerOnly(file)
                }
            }
        }
        cache = pair
        return pair
    }

    private fun ownerOnly(target: File) {
        runCatching {
            Files.setPosixFilePermissions(
                target.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }
}
