package com.sikorokoro44.messengerpro.engine

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Password hashing and integrity helpers used by the engines. */
object Hashing {
    private val random = SecureRandom()

    fun randomHex(bytes: Int = 16): String {
        val buffer = ByteArray(bytes)
        random.nextBytes(buffer)
        return buffer.toHex()
    }

    fun hashPassword(password: String, saltHex: String, iterations: Int = 10_000): String {
        val spec = PBEKeySpec(password.toCharArray(), saltHex.toByteArray(), iterations, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        return factory.generateSecret(spec).encoded.toHex()
    }

    fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).toHex()

    fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (b in this) out.append("%02x".format(b.toInt() and 0xff))
        return out.toString()
    }
}
