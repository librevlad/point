package com.point.desktop

import java.util.Base64

/**
 * Секрет на диске защищён ключом пользователя системы (#1095).
 *
 * Ключи AI, речи и OCR, токен устройства и приватный ключ связки лежали в `~/.point-pc`
 * открытым текстом — прочитать их мог любой процесс и любой человек с доступом к диску.
 * Телефон свои шифрует (EncryptedAccountStore); компьютер теперь тоже: Windows DPAPI,
 * ключом текущего пользователя. Пароль не спрашивается и не хранится — его держит система.
 *
 * Там, где DPAPI нет (не-Windows, урезанная среда), значение честно остаётся как есть:
 * прикидываться защитой, которой нет, хуже, чем не обещать её. Прочитанное различается
 * меткой: защищённое несёт префикс, голое — нет, поэтому старые файлы читаются как раньше
 * и защищаются при первой же записи.
 */
object SecretVault {

    private const val MARK = "dpapi:"

    val active: Boolean by lazy {
        runCatching {
            com.sun.jna.platform.win32.Crypt32Util.cryptProtectData("point".toByteArray())
            true
        }.getOrDefault(false)
    }

    fun protect(value: String): String {
        if (value.isBlank() || value.startsWith(MARK) || !active) return value
        return runCatching {
            MARK + Base64.getEncoder().encodeToString(
                com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(value.toByteArray(Charsets.UTF_8)),
            )
        }.getOrDefault(value)
    }

    fun reveal(value: String): String {
        if (!value.startsWith(MARK)) return value
        return runCatching {
            String(
                com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(
                    Base64.getDecoder().decode(value.removePrefix(MARK)),
                ),
                Charsets.UTF_8,
            )
        }.getOrDefault("")
    }

    /** Ключи файла конфигурации, значения которых — секреты. */
    fun secretConfigKey(key: String): Boolean =
        key == "speech.key" || key == "ocr.key" ||
            (
                key.startsWith(com.point.core.flow.AiKeyFields.PREFIX) &&
                    !key.endsWith(com.point.core.flow.AiKeyFields.MODEL) &&
                    !key.endsWith(com.point.core.flow.AiKeyFields.URL) &&
                    !key.endsWith(com.point.core.flow.AiKeyFields.SAVED)
                )

    fun protectConfig(stored: Map<String, String>): Map<String, String> =
        stored.mapValues { (key, value) -> if (secretConfigKey(key)) protect(value) else value }

    fun revealConfig(stored: Map<String, String>): Map<String, String> =
        stored.mapValues { (key, value) -> if (secretConfigKey(key)) reveal(value) else value }
}
