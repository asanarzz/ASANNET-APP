package com.kafinet.asannet

import java.security.MessageDigest
import java.security.SecureRandom

object PasswordHasher {

    /** رمز عبور را با یک salt تصادفی هش می‌کند و به‌صورت "salt:hash" برمی‌گرداند. */
    fun hash(password: String): String {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        val saltHex = toHex(salt)

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        val hashBytes = digest.digest(password.toByteArray(Charsets.UTF_8))
        val hashHex = toHex(hashBytes)

        return "$saltHex:$hashHex"
    }

    /**
     * رمز وارد شده را با هش ذخیره‌شده (به‌فرمت "salt:hash") مقایسه می‌کند.
     * چون هر بار hash() یک salt تصادفی جدید می‌سازد، برای مقایسه باید همان salt
     * ذخیره‌شده را دوباره استفاده کنیم، نه یک salt تازه.
     */
    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split(":")
        if (parts.size != 2) return false
        val saltHex = parts[0]
        val expectedHashHex = parts[1]

        val salt = fromHex(saltHex) ?: return false
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        val hashBytes = digest.digest(password.toByteArray(Charsets.UTF_8))
        val actualHashHex = toHex(hashBytes)

        return actualHashHex == expectedHashHex
    }

    private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun fromHex(hex: String): ByteArray? {
        return try {
            ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (e: Exception) {
            null
        }
    }
}
