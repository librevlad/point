package com.point.desktop

import com.point.core.flow.DeviceKeyPair
import com.point.core.flow.DeviceKeyStore
import com.point.core.flow.DeviceKeys
import com.point.core.flow.decodePcMeta
import com.point.core.flow.encodePcMeta
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

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
