package com.networkcontrol.android.kotlin.core.profiles

import com.networkcontrol.android.kotlin.core.model.NetworkContext
import com.networkcontrol.android.kotlin.core.model.NetworkKind
import com.networkcontrol.android.kotlin.core.settings.RuntimeStateService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 按目前網絡自動啟用符合條件的配置文件。
 * 去抖 3 秒；使用者手動切換後，同一網絡不再自動搶回。
 */
class ProfileAutoSwitcher(
    private val profiles: ProfileService,
    private val runtime: RuntimeStateService,
    private val networkState: StateFlow<NetworkContext>,
    private val currentNetwork: () -> NetworkContext,
    private val scope: CoroutineScope,
) {
    private var manualFingerprint: String? = null

    var onAutoSwitched: ((com.networkcontrol.android.kotlin.core.model.Profile, NetworkContext) -> Unit)? = null

    init {
        scope.launch {
            networkState.collect { context ->
                if (context.kind == NetworkKind.None) return@collect
                delay(3_000)
                val latest = currentNetwork()
                if (latest.fingerprint != context.fingerprint || latest.kind == NetworkKind.None) {
                    return@collect
                }
                if (manualFingerprint != null && manualFingerprint != latest.fingerprint) {
                    manualFingerprint = null
                }
                if (manualFingerprint == latest.fingerprint) return@collect

                val match = profiles.all
                    .filter { it.autoActivate?.matches(latest) == true }
                    .minByOrNull { it.sortOrder }
                    ?: return@collect
                if (match.id == runtime.activeProfileId) return@collect
                profiles.activate(match.id)
                onAutoSwitched?.invoke(match, latest)
            }
        }
    }

    fun suppressForCurrentNetwork() {
        manualFingerprint = currentNetwork().fingerprint
    }
}
