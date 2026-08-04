package com.point.desktop

import com.point.core.flow.AccountStore
import com.point.core.flow.DeviceKind
import com.point.core.flow.PointAccount
import com.point.core.flow.decodePcMeta
import com.point.core.flow.encodePcMeta
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * Пропуск аккаунта на компьютере (#473) — `~/.point-pc/account`, правами только владельцу.
 *
 * Хранение тем же `k=v`-кодеком, которым живут `config`, `phone-caps` и журнал: своего формата ПК
 * не заводит. Шифровать файл смысла нет, и об этом честно: злоумышленник с правами пользователя на
 * этом компьютере побеждает в любом случае — шифрование добавило бы обряд, а не защиту. Права
 * ставятся там, где система их понимает (POSIX); на Windows их роль играет профиль пользователя.
 */
class FileAccountStore(private val baseDir: File) : AccountStore {

    private val file: File get() = File(baseDir.apply { mkdirs() }, "account")

    override fun current(): PointAccount? {
        val stored = runCatching { decodePcMeta(file.readText()) }.getOrNull() ?: return null
        val id = stored["device_id"]?.takeIf { it.isNotBlank() } ?: return null
        val token = stored["device_token"]?.takeIf { it.isNotBlank() } ?: return null
        return PointAccount(
            deviceId = id,
            deviceToken = token,
            email = stored["email"].orEmpty(),
            deviceName = stored["name"].orEmpty(),
            kind = DeviceKind.PC,
        )
    }

    override suspend fun save(account: PointAccount) {
        val target = file
        target.writeText(
            encodePcMeta(
                mapOf(
                    "device_id" to account.deviceId,
                    "device_token" to account.deviceToken,
                    "email" to account.email,
                    "name" to account.deviceName,
                ),
            ),
        )
        ownerOnly(target)
    }

    override suspend fun clear() {
        runCatching { file.delete() }
    }

    /** Только владельцу — там, где файловая система это понимает. */
    private fun ownerOnly(target: File) {
        runCatching {
            Files.setPosixFilePermissions(
                target.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }
}
