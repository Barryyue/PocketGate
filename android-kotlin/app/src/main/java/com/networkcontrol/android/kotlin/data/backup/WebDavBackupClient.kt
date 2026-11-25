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
 * - 檔頭格式：`PGWB2`（5 bytes，210,000 次迭代，向後相容舊版 `PGWB1` 65,536 次）+ salt（16 bytes）+ iv（12 bytes）+ GCM 密文。
 */
object WebDavBackupClient {
    private val MAGIC_V1 = byteArrayOf('P'.code.toByte(), 'G'.code.toByte(), 'W'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())
    private val MAGIC_V2 = byteArrayOf('P'.code.toByte(), 'G'.code.toByte(), 'W'.code.toByte(), 'B'.code.toByte(), '2'.code.toByte())
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val PBKDF2_ITERATIONS_V1 = 65_536
    private const val PBKDF2_ITERATIONS_V2 = 210_000
    private const val GCM_TAG_BITS = 128
    private const val DEFAULT_FILENAME = "pocketgate-backup.pgb"

    data class SyncResult(
        val targetUrl: String,
        val etag: String?,
        val content: String = "",
    )

    class ConflictException(message: String) : IOException(message)

    fun encrypt(plainText: String, passphrase: String): ByteArray {
        require(passphrase.isNotEmpty()) { "Passphrase must not be empty" }
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val key = deriveKey(passphrase, salt, PBKDF2_ITERATIONS_V2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        val out = ByteArrayOutputStream(MAGIC_V2.size + SALT_BYTES + IV_BYTES + cipherBytes.size)
        out.write(MAGIC_V2)
        out.write(salt)
        out.write(iv)
        out.write(cipherBytes)
        return out.toByteArray()
    }

    fun decrypt(payload: ByteArray, passphrase: String): String {
        require(passphrase.isNotEmpty()) { "Passphrase must not be empty" }
        val minSize = MAGIC_V2.size + SALT_BYTES + IV_BYTES + 16
        require(payload.size >= minSize) { "Invalid backup payload" }
        val iterations = when {
            MAGIC_V2.indices.all { payload[it] == MAGIC_V2[it] } -> PBKDF2_ITERATIONS_V2
            MAGIC_V1.indices.all { payload[it] == MAGIC_V1[it] } -> PBKDF2_ITERATIONS_V1
            else -> throw IllegalArgumentException("Unsupported backup format")
        }
        val saltStart = MAGIC_V2.size
        val ivStart = saltStart + SALT_BYTES
        val cipherStart = ivStart + IV_BYTES
        val salt = payload.copyOfRange(saltStart, ivStart)
        val iv = payload.copyOfRange(ivStart, cipherStart)
        val cipherBytes = payload.copyOfRange(cipherStart, payload.size)
        val key = deriveKey(passphrase, salt, iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val plainBytes = cipher.doFinal(cipherBytes)
        return String(plainBytes, Charsets.UTF_8)
    }

    internal fun resolveTargetUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim()
        val lower = trimmed.lowercase()
        val isLocalHttp = lower.startsWith("http://127.0.0.1") || lower.startsWith("http://localhost")
        require(lower.startsWith("https://") || isLocalHttp) {
            "WebDAV requires HTTPS URL"
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
        expectedEtag: String? = null,
    ): Result<String> = uploadWithMeta(baseUrl, username, password, passphrase, jsonContent, expectedEtag)
        .map { it.targetUrl }

    suspend fun uploadWithMeta(
        baseUrl: String,
        username: String,
        password: String,
        passphrase: String,
        jsonContent: String,
        expectedEtag: String? = null,
    ): Result<SyncResult> = withContext(Dispatchers.IO) {
        runCatching {
            val targetUrl = resolveTargetUrl(baseUrl)
            val encrypted = encrypt(jsonContent, passphrase)
            val auth = basicAuthHeader(username, password)
            // 若已知上次同步的 ETag，先透過 HEAD 確認遠端是否已被其他裝置修改，避免靜默覆蓋。
            if (!expectedEtag.isNullOrBlank()) {
                val remoteEtag = headEtag(targetUrl, auth)
                if (!remoteEtag.isNullOrBlank() && remoteEtag != expectedEtag) {
                    throw ConflictException("Remote backup changed by another device (ETag mismatch)")
                }
            }
            var putResult = putBytes(targetUrl, auth, encrypted, expectedEtag)
            if (putResult.first == 412) {
                throw ConflictException("Remote backup changed by another device (HTTP 412)")
            }
            if (putResult.first == 404 || putResult.first == 409) {
                val parent = targetUrl.substringBeforeLast('/', "")
                if (parent.startsWith("http", ignoreCase = true)) {
                    mkcol(parent, auth)
                    putResult = putBytes(targetUrl, auth, encrypted, expectedEtag = null)
                }
            }
            if (putResult.first !in 200..299) {
                throw IOException("WebDAV PUT failed (HTTP ${putResult.first})")
            }
            val latestEtag = putResult.second ?: headEtag(targetUrl, auth)
            SyncResult(targetUrl = targetUrl, etag = latestEtag)
        }
    }

    suspend fun download(
        baseUrl: String,
        username: String,
        password: String,
        passphrase: String,
    ): Result<String> = downloadWithMeta(baseUrl, username, password, passphrase).map { it.content }

    suspend fun downloadWithMeta(
        baseUrl: String,
        username: String,
        password: String,
        passphrase: String,
    ): Result<SyncResult> = withContext(Dispatchers.IO) {
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
                val etag = connection.getHeaderField("ETag")?.trim()?.takeIf { it.isNotEmpty() }
                val bytes = connection.inputStream.use { it.readBytes() }
                SyncResult(targetUrl = targetUrl, etag = etag, content = decrypt(bytes, passphrase))
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun headEtag(targetUrl: String, auth: String?): String? = runCatching {
        val connection = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            requestMethod = "HEAD"
            if (auth != null) setRequestProperty("Authorization", auth)
        }
        try {
            if (connection.responseCode in 200..299) {
                connection.getHeaderField("ETag")?.trim()?.takeIf { it.isNotEmpty() }
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun putBytes(targetUrl: String, auth: String?, bytes: ByteArray, expectedEtag: String?): Pair<Int, String?> {
        val connection = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            requestMethod = "PUT"
            doOutput = true
            setRequestProperty("Content-Type", "application/octet-stream")
            if (auth != null) setRequestProperty("Authorization", auth)
            if (!expectedEtag.isNullOrBlank()) setRequestProperty("If-Match", expectedEtag)
            setFixedLengthStreamingMode(bytes.size)
        }
        return try {
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            val etag = connection.getHeaderField("ETag")?.trim()?.takeIf { it.isNotEmpty() }
            code to etag
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

    private fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_BITS)
        val raw = factory.generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }
}
