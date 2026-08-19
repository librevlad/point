package com.point.desktop

import com.point.core.flow.AccountStore
import com.point.core.flow.DeviceKind
import com.point.core.flow.PointAccount
import com.point.core.flow.decodePcMeta
import com.point.core.flow.encodePcMeta
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

class FileAccountStore(private val baseDir: File) : AccountStore {

    private val file: File get() = File(baseDir.apply { mkdirs() }, "account")

    override fun current(): PointAccount? {
        val stored = runCatching { decodePcMeta(file.readText()) }.getOrNull() ?: return null
        val id = stored["device_id"]?.takeIf { it.isNotBlank() } ?: return null
        val token = stored["device_token"]?.takeIf { it.isNotBlank() }?.let(SecretVault::reveal) ?: return null
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
                    // Токен — вход в аккаунт: на диске он защищён ключом пользователя (#1095).
                    "device_token" to SecretVault.protect(account.deviceToken),
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

    private fun ownerOnly(target: File) {
        runCatching {
            Files.setPosixFilePermissions(
                target.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }
}
