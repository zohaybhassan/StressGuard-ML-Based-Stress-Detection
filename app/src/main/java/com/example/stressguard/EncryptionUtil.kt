package com.example.stressguard // MAKE SURE THIS MATCHES YOUR PACKAGE NAME

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object EncryptionUtil {
    // In a production app, this key is generated securely and hidden.
    // For your FYP prototype, a static 16-byte (128-bit) key is perfectly acceptable to demonstrate the architecture.
    private const val SECRET_KEY = "StressGuardKey12"
    private const val ALGORITHM = "AES/ECB/PKCS5Padding"

    fun encrypt(plainText: String): ByteArray {
        val cipher = Cipher.getInstance(ALGORITHM)
        val secretKeySpec = SecretKeySpec(SECRET_KEY.toByteArray(), "AES")
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec)
        return cipher.doFinal(plainText.toByteArray())
    }

    fun decrypt(encryptedData: ByteArray): String {
        val cipher = Cipher.getInstance(ALGORITHM)
        val secretKeySpec = SecretKeySpec(SECRET_KEY.toByteArray(), "AES")
        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec)
        val decryptedBytes = cipher.doFinal(encryptedData)
        return String(decryptedBytes)
    }
}