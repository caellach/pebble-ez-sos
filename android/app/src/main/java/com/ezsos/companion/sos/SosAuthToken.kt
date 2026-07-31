package com.ezsos.companion.sos

import com.ezsos.companion.BuildConfig
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Shared-secret AES-256-GCM timestamp token appended to outbound SOS SMS.
 * Stops casual spoofing; key is extractable from the APK.
 */
object SosAuthToken {
    const val LINE_PREFIX = "ez."
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun buildLine(nowEpochSeconds: Long = System.currentTimeMillis() / 1000L): String {
        val key = keyBytes()
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        val plaintext = nowEpochSeconds.toString().toByteArray(Charsets.UTF_8)
        val cipherAndTag = cipher.doFinal(plaintext)
        val packed = ByteBuffer.allocate(iv.size + cipherAndTag.size)
            .put(iv)
            .put(cipherAndTag)
            .array()
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(packed)
        return LINE_PREFIX + b64
    }

    fun extractTokenFromBody(body: String): String? {
        val last = body.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() }
            ?: return null
        if (!last.startsWith(LINE_PREFIX)) return null
        val token = last.removePrefix(LINE_PREFIX)
        return token.takeIf { it.isNotEmpty() }
    }

    fun decryptEpochSeconds(tokenBase64Url: String): Long? {
        return try {
            val packed = Base64.getUrlDecoder().decode(tokenBase64Url)
            if (packed.size <= IV_LEN) return null
            val iv = packed.copyOfRange(0, IV_LEN)
            val cipherAndTag = packed.copyOfRange(IV_LEN, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes(), "AES"),
                GCMParameterSpec(TAG_BITS, iv)
            )
            val plain = cipher.doFinal(cipherAndTag).toString(Charsets.UTF_8)
            plain.toLongOrNull()
        } catch (_: Exception) {
            null
        }
    }

    fun isFresh(
        epochSeconds: Long,
        windowMinutes: Int,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L
    ): Boolean {
        val windowSec = windowMinutes.toLong().coerceAtLeast(0) * 60L
        return abs(nowEpochSeconds - epochSeconds) <= windowSec
    }

    fun validateBody(
        body: String,
        windowMinutes: Int,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L
    ): Boolean {
        val token = extractTokenFromBody(body) ?: return false
        val ts = decryptEpochSeconds(token) ?: return false
        return isFresh(ts, windowMinutes, nowEpochSeconds)
    }

    private fun keyBytes(): ByteArray {
        val hex = BuildConfig.EZ_SOS_AUTH_KEY_HEX
        require(hex.length == 64)
        return ByteArray(32) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
