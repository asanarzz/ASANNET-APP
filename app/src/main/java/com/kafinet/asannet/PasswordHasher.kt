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

    private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
