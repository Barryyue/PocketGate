package com.networkcontrol.android.kotlin.data.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * WebDAV 加密備份與跨機還原用戶端（K-164）：
 * - 以 PBKDF2WithHmacSHA256 + AES-256-GCM 端到端加密，伺服器只儲存密文。
 * - 檔頭格式：`PGWB1`（5 bytes）+ salt（16 bytes）+ iv（12 bytes）+ GCM 密文（含 128-bit 認證標籤）。
 */
object WebDavBackupClient {
    private val MAGIC = byteArrayOf('P'.code.toByte(), 'G'.code.toByte(), 'W'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val PBKDF2_ITERATIONS = 65_536
    private const val GCM_TAG_BITS = 128
    private const val DEFAULT_FILENAME = "pocketgate-backup.pgb"

    fun encrypt(plainText: String, passphrase: String): ByteArray {
        require(passphrase.isNotEmpty()) { "Passphrase must not be empty" }
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        val out = ByteArrayOutputStream(MAGIC.size + SALT_BYTES + IV_BYTES + cipherBytes.size)
        out.write(MAGIC)
        out.write(salt)
        out.write(iv)
        out.write(cipherBytes)
        return out.toByteArray()
    }

    fun decrypt(payload: ByteArray, passphrase: String): String {
        require(passphrase.isNotEmpty()) { "Passphrase must not be empty" }
        val minSize = MAGIC.size + SALT_BYTES + IV_BYTES + 16
        require(payload.size >= minSize) { "Invalid backup payload" }
        for (i in MAGIC.indices) {
            require(payload[i] == MAGIC[i]) { "Unsupported backup format" }
        }
        val saltStart = MAGIC.size
        val ivStart = saltStart + SALT_BYTES
        val cipherStart = ivStart + IV_BYTES
        val salt = payload.copyOfRange(saltStart, ivStart)
        val iv = payload.copyOfRange(ivStart, cipherStart)
        val cipherBytes = payload.copyOfRange(cipherStart, payload.size)
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val plainBytes = cipher.doFinal(cipherBytes)
        return String(plainBytes, Charsets.UTF_8)
    }

    internal fun resolveTargetUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim()
        require(trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true)) {
            "Invalid WebDAV URL"
        }
        return if (trimmed.endsWith(".pgb", ignoreCase = true)) {
            trimmed
        } else {
            "${trimmed.trimEnd('/')}/$DEFAULT_FILENAME"
        }
    }

    suspend fun upload(
        baseUrl: String,
        username: String,
        password: String,
        passphrase: String,
        jsonContent: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val targetUrl = resolveTargetUrl(baseUrl)
            val encrypted = encrypt(jsonContent, passphrase)
            val auth = basicAuthHeader(username, password)
            var code = putBytes(targetUrl, auth, encrypted)
            if (code == 404 || code == 409) {
                // 若遠端目錄尚未建立，嘗試 MKCOL 建立父目錄後重試 PUT。
                val parent = targetUrl.substringBeforeLast('/', "")
                if (parent.startsWith("http", ignoreCase = true)) {
                    mkcol(parent, auth)
                    code = putBytes(targetUrl, auth, encrypted)
                }
            }
            if (code !in 200..299) {
                throw IOException("WebDAV PUT failed (HTTP $code)")
            }
            targetUrl
        }
    }

    suspend fun download(
        baseUrl: String,
        username: String,
        password: String,
        passphrase: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val targetUrl = resolveTargetUrl(baseUrl)
            val auth = basicAuthHeader(username, password)
            val connection = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                requestMethod = "GET"
                if (auth != null) setRequestProperty("Authorization", auth)
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    throw IOException("WebDAV GET failed (HTTP $code)")
                }
                val bytes = connection.inputStream.use { it.readBytes() }
                decrypt(bytes, passphrase)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun putBytes(targetUrl: String, auth: String?, bytes: ByteArray): Int {
        val connection = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            requestMethod = "PUT"
            doOutput = true
            setRequestProperty("Content-Type", "application/octet-stream")
            if (auth != null) setRequestProperty("Authorization", auth)
            setFixedLengthStreamingMode(bytes.size)
        }
        return try {
            connection.outputStream.use { it.write(bytes) }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private fun mkcol(dirUrl: String, auth: String?) {
        runCatching {
            val connection = (URL(dirUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                requestMethod = "MKCOL"
                if (auth != null) setRequestProperty("Authorization", auth)
            }
            try {
                connection.responseCode
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun basicAuthHeader(username: String, password: String): String? {
        if (username.isBlank() && password.isBlank()) return null
        val token = Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
        return "Basic $token"
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        val raw = factory.generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }
}
