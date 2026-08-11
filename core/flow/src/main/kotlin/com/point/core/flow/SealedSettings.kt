package com.point.core.flow

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Настройки, запечатанные на устройстве (#610, решение владельца 10.08.2026: «всё, включая
 * ключи, но ключи в закрытом виде»).
 *
 * Настройки едут за человеком через сервер — то есть через машину, которой человек своих
 * ключей от чужих сервисов не отдавал. Поэтому сервер получает нечитаемое: содержимое
 * зашифровано случайным ключом, а сам этот ключ вложен в конверт для каждого устройства
 * круга по его публичной части. Расшифровать может только устройство, и никогда — сервер.
 *
 * Отдельного механизма для этого не заводится: конверт стоит на той же паре ключей
 * устройства, которую оно отдаёт серверу при входе в круг (`key_agree` в `/enroll`).
 * Назначение разведено контекстом — секрет связки для настроек не годится по построению.
 */
data class SealedSettings(

    /** Содержимое: `nonce.шифртекст`, оба в base64. Сервер видит только это. */
    val body: String,

    /** Конверт с ключом содержимого на каждое устройство круга: id устройства → конверт. */
    val wraps: Map<String, String>,

    /** Когда запечатано: по этому же числу решается, чьи настройки новее. */
    val at: Long,
) {
    fun encode(): String = encodePcMeta(
        buildMap {
            put(AT, at.toString())
            put(BODY, body)
            wraps.forEach { (device, wrap) -> put(WRAP + device, wrap) }
        },
    )

    companion object {

        private const val AT = "at"
        private const val BODY = "body"
        private const val WRAP = "w."

        fun decode(encoded: String): SealedSettings? {
            val fields = decodePcMeta(encoded)
            val body = fields[BODY]?.takeIf { it.isNotBlank() } ?: return null
            return SealedSettings(
                body = body,
                wraps = fields.filterKeys { it.startsWith(WRAP) }.mapKeys { it.key.removePrefix(WRAP) },
                at = fields[AT]?.toLongOrNull() ?: 0L,
            )
        }
    }
}

/**
 * Запечатывание и вскрытие. Случайность отдана наружу, чтобы её можно было проверить тестом:
 * ни один тест не должен доказывать шифрование, полагаясь на удачу.
 */
class SettingsSeal(private val random: () -> ByteArray = { randomBytes(KEY_BYTES + NONCE_BYTES) }) {

    /**
     * Запечатать [plain] для устройств круга. Устройство без публичной части в конверт не
     * попадает: выдумывать ему ключ нельзя, а молча оставить настройки открытыми — тем более.
     */
    fun seal(plain: String, circle: List<CircleDevice>, at: Long): SealedSettings? {
        val material = random()
        if (material.size < KEY_BYTES + NONCE_BYTES) return null
        val contentKey = material.copyOfRange(0, KEY_BYTES)
        val nonce = material.copyOfRange(KEY_BYTES, KEY_BYTES + NONCE_BYTES)

        val body = encrypt(contentKey, nonce, plain.toByteArray(Charsets.UTF_8)) ?: return null
        val wraps = circle
            .filter { it.key.isNotBlank() }
            .mapNotNull { device -> wrapFor(device.key, contentKey)?.let { device.id to it } }
            .toMap()
        if (wraps.isEmpty()) return null

        return SealedSettings(
            body = DeviceKeys.base64Of(nonce) + SPLIT + DeviceKeys.base64Of(body),
            wraps = wraps,
            at = at,
        )
    }

    /** Вскрыть своим ключом. `null` — конверта для этого устройства нет или он не открывается. */
    fun open(sealed: SealedSettings, deviceId: String, privateKey: String): String? {
        val contentKey = unwrap(sealed.wraps[deviceId] ?: return null, privateKey) ?: return null
        val parts = sealed.body.split(SPLIT)
        if (parts.size != 2) return null
        val nonce = runCatching { DeviceKeys.bytesOf(parts[0]) }.getOrNull() ?: return null
        val cipher = runCatching { DeviceKeys.bytesOf(parts[1]) }.getOrNull() ?: return null
        return decrypt(contentKey, nonce, cipher)?.toString(Charsets.UTF_8)
    }

    /**
     * Конверт: одноразовая пара ключей на каждое устройство. Так вскрывающему нужен только
     * свой ключ — идти за публичной частью запечатавшего не приходится.
     */
    private fun wrapFor(publicKey: String, contentKey: ByteArray): String? {
        val once = DeviceKeys.generate()
        val shared = DeviceKeys.sharedSecret(once.privateKey, publicKey, DeviceKeys.SETTINGS_CONTEXT)
            ?: return null
        val nonce = randomBytes(NONCE_BYTES)
        val sealedKey = encrypt(shared, nonce, contentKey) ?: return null
        return once.publicKey + SPLIT + DeviceKeys.base64Of(nonce) + SPLIT + DeviceKeys.base64Of(sealedKey)
    }

    private fun unwrap(wrap: String, privateKey: String): ByteArray? {
        val parts = wrap.split(SPLIT)
        if (parts.size != 3) return null
        val shared = DeviceKeys.sharedSecret(privateKey, parts[0], DeviceKeys.SETTINGS_CONTEXT) ?: return null
        val nonce = runCatching { DeviceKeys.bytesOf(parts[1]) }.getOrNull() ?: return null
        val sealedKey = runCatching { DeviceKeys.bytesOf(parts[2]) }.getOrNull() ?: return null
        return decrypt(shared, nonce, sealedKey)
    }

    private fun encrypt(key: ByteArray, nonce: ByteArray, plain: ByteArray): ByteArray? = runCatching {
        cipher(Cipher.ENCRYPT_MODE, key, nonce).doFinal(plain)
    }.getOrNull()

    private fun decrypt(key: ByteArray, nonce: ByteArray, sealed: ByteArray): ByteArray? = runCatching {
        cipher(Cipher.DECRYPT_MODE, key, nonce).doFinal(sealed)
    }.getOrNull()

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        }

    companion object {

        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val SPLIT = "."

        private val SOURCE = SecureRandom()

        fun randomBytes(size: Int): ByteArray = ByteArray(size).also(SOURCE::nextBytes)
    }
}
