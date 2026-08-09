package com.point.desktop

import com.point.core.flow.CloudScope
import com.point.core.flow.PrivacyConsent
import com.point.core.flow.remembersConsent
import java.io.File

/**
 * Согласие на выход объекта за границу устройств (Конституция §11, инвариант 9).
 * Тот же контракт, что на телефоне: MODELS запоминается, PUBLIC_LINK спрашивается
 * каждый раз — ссылка на сутки каждый раз новая ставка.
 */
class FileConsent(private val file: File) : PrivacyConsent {

    private val lock = Any()

    override suspend fun allowed(scope: CloudScope): Boolean = synchronized(lock) {
        remembersConsent(scope) && scope.name in lines()
    }

    override suspend fun allow(scope: CloudScope) {
        if (!remembersConsent(scope)) return
        synchronized(lock) {
            val kept = (lines() + scope.name).distinct()
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(kept.joinToString("\n"))
            }
        }
    }

    override suspend fun revoke(scope: CloudScope) {
        synchronized(lock) {
            runCatching { file.writeText(lines().filterNot { it == scope.name }.joinToString("\n")) }
        }
    }

    private fun lines(): List<String> =
        runCatching { file.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
}
