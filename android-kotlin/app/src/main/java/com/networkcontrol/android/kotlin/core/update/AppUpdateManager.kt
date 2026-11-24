package com.networkcontrol.android.kotlin.core.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * 全域應用更新狀態，由 [AppUpdateManager] 維護，切換底部導覽分頁不中斷檢查與下載。
 */
data class AppUpdateState(
    val checking: Boolean = false,
    val info: UpdateInfo? = null,
    val downloading: Boolean = false,
    val progress: Int = 0,
    val downloadedFile: File? = null,
    val error: String? = null,
    val showAvailablePrompt: Boolean = false,
    val showReadyPrompt: Boolean = false,
)

/**
 * 應用級更新管理器：負責進入應用時自動檢查新版本、背景下載 APK 並跨分頁保留進度、下載完成後彈窗提醒安裝。
 */
class AppUpdateManager(
    private val scope: CoroutineScope,
    private val updatesDir: File,
    private val currentVersion: String,
    private val offlineMode: () -> Boolean,
    private val updateChannel: () -> UpdateChannel = { UpdateChannel.Stable },
    private val checkUpdate: suspend () -> UpdateInfo = { UpdateChecker.checkAuto(currentVersion, channel = updateChannel()) },
    private val downloadApk: (suspend (
        url: String,
        targetFile: File,
        fallbackUrl: String?,
        expectedVersion: String?,
        onProgress: (Int) -> Unit,
    ) -> Result<File>)? = null,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val _state = MutableStateFlow(AppUpdateState())
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    @Volatile
    private var lastAutoCheckMs: Long = 0L

    @Volatile
    private var dismissedVersion: String? = null

    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    /**
     * 進入應用時自動檢查更新（帶冷卻時間，且嚴格遵守純離線模式）；若發現新版本將自動觸發提醒彈窗。
     */
    fun checkOnEnter(cooldownMs: Long = AUTO_CHECK_COOLDOWN_MS) {
        if (offlineMode()) return
        val current = _state.value
        if (current.checking || current.downloading || current.downloadedFile != null) return
        val now = nowMs()
        if (lastAutoCheckMs > 0L && now - lastAutoCheckMs < cooldownMs) return
        lastAutoCheckMs = now
        launchCheck(manual = false)
    }

    /**
     * 設定頁手動觸發檢查更新。
     */
    fun checkManually(onResult: ((UpdateInfo) -> Unit)? = null) {
        if (offlineMode()) return
        if (_state.value.checking) return
        lastAutoCheckMs = nowMs()
        launchCheck(manual = true, onResult = onResult)
    }

    private fun launchCheck(manual: Boolean, onResult: ((UpdateInfo) -> Unit)? = null) {
        checkJob?.cancel()
        checkJob = scope.launch {
            _state.update { it.copy(checking = true, error = null) }
            val info = runCatching { checkUpdate() }.getOrElse { err ->
                UpdateInfo(null, null, err.message ?: "check failed", currentVersion)
            }
            _state.update { prev ->
                val hasNew = info.hasUpdate
                val shouldPrompt = hasNew && (manual || info.latestVersion != dismissedVersion)
                val readyFile = prev.downloadedFile?.takeIf {
                    it.exists() && prev.info?.latestVersion == info.latestVersion
                }
                prev.copy(
                    checking = false,
                    info = info,
                    downloadedFile = readyFile,
                    error = if (manual) info.error else prev.error,
                    showAvailablePrompt = if (readyFile == null) shouldPrompt else false,
                )
            }
            onResult?.invoke(info)
        }
    }

    /**
     * 開始在全域協程作用域背景下載 APK（切換導覽不中斷）。
     */
    fun startDownload(
        info: UpdateInfo? = _state.value.info,
        onError: ((String) -> Unit)? = null,
    ) {
        if (offlineMode()) return
        val target = info?.takeIf { it.hasUpdate } ?: return
        val url = target.downloadUrl ?: return
        val current = _state.value
        if (current.downloading) return

        val versionTag = (target.latestVersion ?: "latest").replace(Regex("[^A-Za-z0-9._-]"), "_")
        val targetFile = File(updatesDir, "PocketGate-$versionTag.apk")
        // 清理舊版殘留 APK 與未完成暫存檔，避免快取持續增長。
        updatesDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name != targetFile.name && (f.name.endsWith(".apk") || f.name.endsWith(".part"))) {
                f.delete()
            }
        }
        // 若同版本 APK 已存在且通過基本結構（及已知 SHA-256）檢查，直接進入待安裝狀態避免重複下載。
        val shaMatches = target.expectedSha256.isNullOrBlank() ||
            runCatching { UpdateChecker.sha256Of(targetFile).equals(target.expectedSha256, ignoreCase = true) }.getOrDefault(false)
        if (targetFile.exists() && targetFile.length() > 1024L && isZipHeader(targetFile) && shaMatches) {
            _state.update {
                it.copy(
                    info = target,
                    downloading = false,
                    progress = 100,
                    downloadedFile = targetFile,
                    error = null,
                    showAvailablePrompt = false,
                    showReadyPrompt = true,
                )
            }
            return
        }

        _state.update {
            it.copy(
                info = target,
                downloading = true,
                progress = 0,
                downloadedFile = null,
                error = null,
                showAvailablePrompt = false,
                showReadyPrompt = false,
            )
        }

        downloadJob?.cancel()
        downloadJob = scope.launch {
            val progressCallback: (Int) -> Unit = { percent ->
                _state.update { state ->
                    if (!state.downloading) state else state.copy(progress = percent.coerceIn(0, 100))
                }
            }
            val result = if (downloadApk != null) {
                downloadApk.invoke(url, targetFile, target.fallbackDownloadUrl, target.latestVersion, progressCallback)
            } else {
                UpdateChecker.downloadApk(
                    url = url,
                    targetFile = targetFile,
                    fallbackUrl = target.fallbackDownloadUrl,
                    currentVersion = currentVersion,
                    expectedVersion = target.latestVersion,
                    channel = updateChannel(),
                    sha256Url = target.sha256Url,
                    expectedSha256 = target.expectedSha256,
                    onProgress = progressCallback,
                )
            }
            result.onSuccess { file ->
                _state.update {
                    it.copy(
                        downloading = false,
                        progress = 100,
                        downloadedFile = file,
                        error = null,
                        showAvailablePrompt = false,
                        showReadyPrompt = true,
                    )
                }
            }.onFailure { err ->
                val message = err.message ?: "download failed"
                _state.update {
                    it.copy(
                        downloading = false,
                        progress = 0,
                        error = message,
                    )
                }
                onError?.invoke(message)
            }
        }
    }

    fun dismissAvailablePrompt() {
        val version = _state.value.info?.latestVersion
        if (!version.isNullOrBlank()) {
            dismissedVersion = version
        }
        _state.update { it.copy(showAvailablePrompt = false) }
    }

    fun dismissReadyPrompt() {
        _state.update { it.copy(showReadyPrompt = false) }
    }

    private fun isZipHeader(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val magic = ByteArray(4)
            input.read(magic) == 4 &&
                magic[0] == 0x50.toByte() &&
                magic[1] == 0x4B.toByte() &&
                magic[2] == 0x03.toByte() &&
                magic[3] == 0x04.toByte()
        }
    }.getOrDefault(false)

    companion object {
        const val AUTO_CHECK_COOLDOWN_MS = 10 * 60 * 1000L
    }
}
