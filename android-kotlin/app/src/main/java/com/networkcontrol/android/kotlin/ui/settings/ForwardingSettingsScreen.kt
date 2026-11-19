package com.networkcontrol.android.kotlin.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.networkcontrol.android.kotlin.AppBackend
import com.networkcontrol.android.kotlin.ForwardingUpstream
import com.networkcontrol.android.kotlin.core.model.InstalledApp
import com.networkcontrol.android.kotlin.engine.dns.DohProviders
import com.networkcontrol.android.kotlin.engine.vpn.VpnEngine
import com.networkcontrol.android.kotlin.platform.Socks5Probe
import com.networkcontrol.android.kotlin.ui.S
import com.networkcontrol.android.kotlin.ui.common.SectionCard
import com.networkcontrol.android.kotlin.ui.rememberUiStrings
import com.networkcontrol.android.kotlin.ui.security.LocalSensitiveAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 逐連線（轉發）模式的獨立設定頁：整合上游出口（內建直連 / SOCKS5）、DoH DNS、防 DNS 繞過與直連/代理應用選擇。
 */
@Composable
fun ForwardingSettingsScreen(backend: AppBackend, onBack: () -> Unit) {
    val guard = LocalSensitiveAction.current
    val s = rememberUiStrings()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val ruleSet by backend.container.rules.ruleSet.collectAsStateWithLifecycle()
    val network by backend.network.collectAsStateWithLifecycle()
    val forwardingActive by backend.forwardingActive.collectAsStateWithLifecycle()
    val forwardingAvailable = remember { backend.forwardingAvailable() }

    var forwardingEnabled by remember { mutableStateOf(backend.forwardingEnabled()) }
    var forwardingUpstream by remember { mutableStateOf(backend.forwardingUpstream()) }
    val viaProxy = forwardingUpstream == ForwardingUpstream.SOCKS5
    var dnsBypass by remember { mutableStateOf(backend.dnsBypassProtection()) }
    var exitDns by remember { mutableStateOf(backend.exitDnsProvider()) }
    var exitDnsUrl by remember { mutableStateOf(backend.exitDnsCustomUrl()) }
    var exitDnsFallback by remember { mutableStateOf(backend.exitDnsFallback()) }
    var socks5Host by remember { mutableStateOf(backend.socks5Host()) }
    var socks5Port by remember { mutableStateOf(backend.socks5Port().toString()) }
    var socks5Udp by remember { mutableStateOf(backend.socks5Udp()) }
    var socks5User by remember { mutableStateOf(backend.socks5Username()) }
    var socks5Pass by remember { mutableStateOf(backend.socks5Password()) }
    var proxyTesting by remember { mutableStateOf(false) }
    var proxyPackage by remember { mutableStateOf(backend.socks5BypassPackage()) }
    var showProxyAppDialog by remember { mutableStateOf(false) }
    var directPackages by remember { mutableStateOf(backend.forwardingDirectPackages()) }
    var showDirectAppsDialog by remember { mutableStateOf(false) }

    val pickerApps by produceState<List<InstalledApp>?>(
        initialValue = null,
        showProxyAppDialog,
        showDirectAppsDialog,
    ) {
        if (showProxyAppDialog || showDirectAppsDialog) {
            value = withContext(Dispatchers.IO) { backend.installedApps() }
        }
    }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    BackHandler { onBack() }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ ${s[S.Back]}") }
            Text(
                s[S.ForwardingMode],
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard {
                    Text(
                        s[S.ForwardingHint],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (forwardingAvailable) s[S.ForwardingAvailable] else s[S.ForwardingUnavailable],
                        style = MaterialTheme.typography.bodySmall,
                        color = if (forwardingAvailable) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    SwitchRow(
                        title = s[S.ForwardingMode],
                        checked = forwardingEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled && !forwardingAvailable) {
                                toast(s[S.ForwardingUnavailable])
                            } else {
                                guard.run {
                                    forwardingEnabled = enabled
                                    backend.setForwardingEnabled(enabled)
                                }
                            }
                        },
                    )
                    if (forwardingEnabled) {
                        Text(
                            if (forwardingActive) s[S.ForwardingStatusActive] else s[S.ForwardingStatusInactive],
                            style = MaterialTheme.typography.bodySmall,
                            color = if (forwardingActive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                    Text(
                        s[S.ForwardingAbiHint],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                SectionCard(s[S.ForwardingUpstream]) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !viaProxy,
                            onClick = {
                                forwardingUpstream = ForwardingUpstream.DIRECT
                                backend.setForwardingUpstream(forwardingUpstream)
                            },
                            label = { Text(s[S.UpstreamDirect]) },
                        )
                        FilterChip(
                            selected = viaProxy,
                            onClick = {
                                forwardingUpstream = ForwardingUpstream.SOCKS5
                                backend.setForwardingUpstream(forwardingUpstream)
                            },
                            label = { Text(s[S.UpstreamSocks5]) },
                        )
                    }
                    if (!viaProxy) {
                        Text(
                            s[S.UpstreamDirectHint],
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(s[S.ExitDns], style = MaterialTheme.typography.bodyMedium)
                        Text(
                            s[S.ExitDnsHint],
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val dnsOptions = listOf("system" to s[S.ExitDnsSystem]) +
                            DohProviders.all.map { it.id to it.name } +
                            listOf("custom" to s[S.ExitDnsCustom])
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            dnsOptions.forEach { (id, label) ->
                                FilterChip(
                                    selected = exitDns == id,
                                    onClick = {
                                        exitDns = id
                                        backend.setExitDnsProvider(id)
                                    },
                                    label = { Text(label) },
                                )
                            }
                        }
                        if (exitDns == "custom") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = exitDnsUrl,
                                    onValueChange = { exitDnsUrl = it.trim() },
                                    label = { Text(s[S.ExitDnsCustomUrl]) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = {
                                    backend.setExitDnsCustomUrl(exitDnsUrl)
                                    toast(s[S.Done])
                                }) { Text(s[S.Save]) }
                            }
                        }
                        if (exitDns != "system") {
                            SwitchRow(
                                title = s[S.ExitDnsFallback],
                                subtitle = s[S.ExitDnsFallbackHint],
                                checked = exitDnsFallback,
                                onCheckedChange = {
                                    exitDnsFallback = it
                                    backend.setExitDnsFallback(it)
                                },
                            )
                        }
                    } else {
                        Text(
                            s[S.ForwardingGuide],
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = socks5Host,
                                onValueChange = { socks5Host = it },
                                label = { Text(s[S.Socks5Host]) },
                                singleLine = true,
                                modifier = Modifier.weight(1.6f),
                            )
                            OutlinedTextField(
                                value = socks5Port,
                                onValueChange = {
                                    socks5Port = it.filter { char -> char.isDigit() }.take(5)
                                },
                                label = { Text(s[S.Socks5Port]) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = socks5User,
                                onValueChange = { socks5User = it },
                                label = { Text(s[S.Socks5Username]) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = socks5Pass,
                                onValueChange = { socks5Pass = it },
                                label = { Text(s[S.Socks5Password]) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    backend.setSocks5Config(
                                        host = socks5Host,
                                        port = socks5Port.toIntOrNull() ?: 7890,
                                        username = socks5User,
                                        password = socks5Pass,
                                    )
                                    toast(s[S.Done])
                                },
                            ) { Text(s[S.Save]) }
                            OutlinedButton(
                                onClick = {
                                    proxyTesting = true
                                    scope.launch {
                                        val error = Socks5Probe.test(
                                            host = socks5Host,
                                            port = socks5Port.toIntOrNull() ?: 7890,
                                            username = socks5User.trim().ifEmpty { null },
                                            password = socks5Pass,
                                        )
                                        proxyTesting = false
                                        toast(
                                            if (error == null) {
                                                s[S.ProxyTestSuccess]
                                            } else {
                                                s.format(S.ProxyTestFailed, error)
                                            },
                                        )
                                    }
                                },
                                enabled = !proxyTesting,
                            ) {
                                Text(if (proxyTesting) s[S.Loading] else s[S.ProxyTest])
                            }
                        }
                        Text(
                            s[S.ForwardingDnsHint],
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SwitchRow(
                        title = s[S.Socks5Udp],
                        subtitle = s[S.Socks5UdpHint],
                        checked = socks5Udp,
                        onCheckedChange = {
                            socks5Udp = it
                            backend.setSocks5Udp(it)
                        },
                    )
                    SwitchRow(
                        title = s[S.DnsBypassProtection],
                        subtitle = s[S.DnsBypassProtectionHint],
                        checked = dnsBypass,
                        onCheckedChange = {
                            val value = it
                            guard.run {
                                dnsBypass = value
                                backend.setDnsBypassProtection(value)
                            }
                        },
                    )
                }
            }

            item {
                SectionCard(s[S.DirectApps]) {
                    if (viaProxy) {
                        if (VpnEngine.isLoopbackHost(socks5Host) && proxyPackage.isNullOrBlank()) {
                            Text(
                                s[S.ProxyAppMissingWarning],
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s[S.ProxyApp], style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    proxyPackage ?: s[S.Unset],
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { showProxyAppDialog = true }) {
                                Text(s[S.Select])
                            }
                        }
                        Text(
                            s[S.ProxyAppHint],
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s[S.DirectApps], style = MaterialTheme.typography.bodyMedium)
                            Text(
                                s.format(S.DirectAppsCount, directPackages.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { showDirectAppsDialog = true }) {
                            Text(s[S.Select])
                        }
                    }
                    Text(
                        s[S.DirectAppsHint],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    val apps = pickerApps
    if (showProxyAppDialog && apps != null) {
        ProxyAppDialog(
            apps = apps,
            selected = proxyPackage,
            onDismiss = { showProxyAppDialog = false },
            onSelect = { packageName ->
                proxyPackage = packageName
                backend.setSocks5BypassPackage(packageName)
                showProxyAppDialog = false
            },
        )
    }

    val allowedByRulePackages = remember(apps, ruleSet.version, network) {
        apps?.let { backend.allowedByRulePackages(it) } ?: emptySet()
    }
    if (showDirectAppsDialog && apps != null) {
        DirectAppsDialog(
            title = s[S.DirectApps],
            apps = apps,
            initial = directPackages,
            allowedByRulePackages = allowedByRulePackages,
            onDismiss = { showDirectAppsDialog = false },
            onSave = { packages ->
                directPackages = packages
                guard.run { backend.setForwardingDirectPackages(packages) }
                showDirectAppsDialog = false
            },
        )
    }
}
