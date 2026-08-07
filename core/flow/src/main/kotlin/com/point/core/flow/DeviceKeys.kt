package com.point.core.flow

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement

object DeviceKeys {

    private const val CURVE = "secp256r1"

    fun generate(): DeviceKeyPair {
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(CURVE)) }
        val pair = generator.generateKeyPair()
        return DeviceKeyPair(
            privateKey = base64(pair.private.encoded),
            publicKey = base64(pair.public.encoded),
        )
    }

    fun sharedSecret(privateKey: String, peerPublicKey: String): ByteArray? = runCatching {
        if (privateKey.isBlank() || peerPublicKey.isBlank()) return null
        val factory = KeyFactory.getInstance("EC")
        val mine = factory.generatePrivate(PKCS8EncodedKeySpec(unBase64(privateKey)))
        val theirs = factory.generatePublic(X509EncodedKeySpec(unBase64(peerPublicKey)))
        val agreed = KeyAgreement.getInstance("ECDH").apply {
            init(mine)
            doPhase(theirs, true)
        }.generateSecret()

        MessageDigest.getInstance("SHA-256").digest(agreed + "point-pc".toByteArray(Charsets.UTF_8))
    }.getOrNull()

    private fun base64(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun unBase64(text: String): ByteArray = Base64.getUrlDecoder().decode(text)
}

data class DeviceKeyPair(val privateKey: String, val publicKey: String)

interface DeviceKeyStore {

    fun keys(): DeviceKeyPair
}

fun interface PcSecrets {
    fun sharedWith(peer: LinkedPc): ByteArray?
}

class KeyStoreSecrets(private val keys: DeviceKeyStore) : PcSecrets {
    override fun sharedWith(peer: LinkedPc): ByteArray? =
        DeviceKeys.sharedSecret(keys.keys().privateKey, peer.key)
}
