package com.secrux.security

import com.secrux.config.SecruxCryptoProperties
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Component
class SecretCrypto(
    properties: SecruxCryptoProperties
) {
    private val secret = properties.secret.trim()
    private val secretKeySpec =
        SecretKeySpec(
            MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(StandardCharsets.UTF_8)),
            "AES"
        )
    private val secureRandom = SecureRandom()

    init {
        check(secret.isNotBlank()) { "secrux.crypto.secret must be configured" }
    }

    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12)
        secureRandom.nextBytes(iv)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, spec)
        val cipherText = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        val payload = iv + cipherText
        return Base64.getEncoder().encodeToString(payload)
    }

    fun decrypt(encoded: String): String {
        val payload = Base64.getDecoder().decode(encoded)
        require(payload.size > 12) { "cipher payload too short" }
        val iv = payload.copyOfRange(0, 12)
        val cipherText = payload.copyOfRange(12, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(cipherText)
        return String(plain, StandardCharsets.UTF_8)
    }
}
