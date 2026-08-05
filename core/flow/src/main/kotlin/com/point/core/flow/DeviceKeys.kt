package com.point.core.flow

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement

/**
 * Ключ рождается у устройства, а не выдаётся сервером (#475).
 *
 * Раньше ключ, которым запечатан кадр между телефоном и компьютером, выводился из токена пары, а
 * токен приезжал в QR. QR убран, пары нет — и общего секрета у двух устройств не осталось бы вовсе.
 * Без него сервер возил бы открытый текст, а обещание «что вы пересылаете между своими
 * устройствами, сервер прочитать не может» стало бы неправдой. Поэтому: каждое устройство при
 * первом запуске делает себе пару ключей, закрытая половина не уходит с него никогда, открытая
 * едет в круг, и общий секрет **вычисляется** обеими сторонами (ECDH), а не пересылается.
 *
 * **P-256, а не X25519** — не по вкусу: `XDH` появился в Android только с API 33, а `ECDH` есть на
 * всех поддерживаемых версиях и в обычной JVM. Новых зависимостей ноль, как и у прежнего кода.
 *
 * **Названная цена.** Открытые ключи раздаёт сервер — значит злонамеренный сервер теоретически
 * может подсунуть свой. Прежде этого сделать было нельзя: токен рождался в QR, которого сервер не
 * видел. Это единственная настоящая потеря от перехода на один путь, и прятать её нельзя. Что
 * поверх этого делают запоминание ключа при первой встрече и код устройства для сверки глазами —
 * отдельная работа (#474), сюда она не входит.
 */
object DeviceKeys {

    /** Кривая, которую понимают и Android, и обычная JVM. */
    private const val CURVE = "secp256r1"

    /** Новая пара ключей устройства. Зовётся один раз в жизни устройства. */
    fun generate(): DeviceKeyPair {
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(CURVE)) }
        val pair = generator.generateKeyPair()
        return DeviceKeyPair(
            privateKey = base64(pair.private.encoded),
            publicKey = base64(pair.public.encoded),
        )
    }

    /**
     * Общий секрет с соседом по кругу — из своей закрытой половины и его открытой.
     *
     * `null` значит «ключа соседа нет или он непонятен»: сосед вошёл сборкой без ключей, круг ещё
     * не приехал, строка испорчена. Молчание здесь честнее выдуманного ключа — на выдуманном кадр
     * уехал бы, а распечатать его не смог бы никто.
     */
    fun sharedSecret(privateKey: String, peerPublicKey: String): ByteArray? = runCatching {
        if (privateKey.isBlank() || peerPublicKey.isBlank()) return null
        val factory = KeyFactory.getInstance("EC")
        val mine = factory.generatePrivate(PKCS8EncodedKeySpec(unBase64(privateKey)))
        val theirs = factory.generatePublic(X509EncodedKeySpec(unBase64(peerPublicKey)))
        val agreed = KeyAgreement.getInstance("ECDH").apply {
            init(mine)
            doPhase(theirs, true)
        }.generateSecret()
        // Согласованная точка кривой — не ключ шифра: у неё неравномерные биты. Хеш выравнивает её
        // до 256 бит, а метка не даёт одному и тому же секрету случайно послужить где-то ещё.
        MessageDigest.getInstance("SHA-256").digest(agreed + "point-pc".toByteArray(Charsets.UTF_8))
    }.getOrNull()

    private fun base64(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun unBase64(text: String): ByteArray = Base64.getUrlDecoder().decode(text)
}

/** Пара ключей устройства: закрытая половина остаётся здесь, открытая едет в круг. */
data class DeviceKeyPair(val privateKey: String, val publicKey: String)

/**
 * Где живут ключи этого устройства.
 *
 * Шов, а не хранилище: `:core:flow` обязан оставаться Android-free, и «куда записать закрытую
 * половину» решает платформа — телефон кладёт её туда же, где пропуск (шифрованно), компьютер —
 * файлом только для владельца.
 *
 * Чтение тёплое и синхронное, как у остальных крошечных хранилищ: пара нужна ровно тогда, когда
 * кадр уже собран, и уходить за ней в корутину незачем.
 */
interface DeviceKeyStore {
    /** Пара этого устройства; при первом обращении рождается и сохраняется. */
    fun keys(): DeviceKeyPair
}

/**
 * Общий ключ с устройством круга — то, чем запечатывается кадр.
 *
 * Отдельный шов, потому что вычисление у него одно, а вот откуда брать свою закрытую половину —
 * платформенное дело. Транспорту знать про ключи не нужно: он спрашивает «чем шифровать этому» и
 * получает либо ключ, либо `null`.
 */
fun interface PcSecrets {
    fun sharedWith(peer: LinkedPc): ByteArray?
}

/** Общий ключ из своей пары и открытого ключа соседа — единственная реализация [PcSecrets]. */
class KeyStoreSecrets(private val keys: DeviceKeyStore) : PcSecrets {
    override fun sharedWith(peer: LinkedPc): ByteArray? =
        DeviceKeys.sharedSecret(keys.keys().privateKey, peer.key)
}
