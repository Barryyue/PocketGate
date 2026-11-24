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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
    var blockQuic by remember { mutableStateOf(backend.blockQuic()) }
    var socks5User by remember { mutableStateOf(backend.socks5Username()) }
    var socks5Pass by remember { mutableStateOf(backend.socks5Password()) }
    var proxyTesting by remember { mutableStateOf(false) }
    var probeReport by remember { mutableStateOf<com.networkcontrol.android.kotlin.platform.ProxyProbeReport?>(null) }
    var proxyPackage by remember { mutableStateOf(backend.socks5BypassPackage()) }
    var showProxyAppDialog by remember { mutableStateOf(false) }
    var directPackages by remember { mutableStateOf(backend.forwardingDirectPackages()) }
    var showDirectAppsDialog by remember { mutableStateOf(false) }
    var splitExitEnabled by remember { mutableStateOf(backend.forwardingSplitExitEnabled()) }
    var proxyPackages by remember { mutableStateOf(backend.forwardingProxyPackages()) }
    var showSplitProxyAppsDialog by remember { mutableStateOf(false) }
    var privateDnsStrict by remember { mutableStateOf(backend.privateDnsMode() == "hostname") }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        privateDnsStrict = backend.privateDnsMode() == "hostname"
    }

    val pickerApps by produceState<List<InstalledApp>?>(
        initialValue = null,
        showProxyAppDialog,
        showDirectAppsDialog,
        showSplitProxyAppsDialog,
    ) {
        if (showProxyAppDialog || showDirectAppsDialog || showSplitProxyAppsDialog) {
            value = withContext(Dispatchers.IO) { backend.installedApps() }
        }
    }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    BackHandler { onBack() }

    SettingsSubScreenScaffold(title = s[S.ForwardingMode], onBack = onBack) {
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
                    SwitchRow(
                        title = s[S.BlockQuic],
                        subtitle = s[S.BlockQuicHint],
                        checked = blockQuic,
                        onCheckedChange = { enabled ->
                            guard.run {
                                blockQuic = enabled
                                backend.setBlockQuic(enabled)
                            }
                        },
                    )
                    Text(
                        s[S.ForwardingAbiHint],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                SettingsGroup(s[S.ForwardingUpstream]) {
                    SettingsSegmentedRow(
                        title = s[S.ForwardingUpstream],
                        options = listOf(
                            ForwardingUpstream.DIRECT to s[S.UpstreamDirect],
                            ForwardingUpstream.SOCKS5 to s[S.UpstreamSocks5],
                        ),
                        selected = forwardingUpstream,
                        onSelect = { selected ->
                            forwardingUpstream = selected
                            backend.setForwardingUpstream(selected)
                        },
                    )
                    SettingsDivider(insetWithIcon = false)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SwitchRow(
                            title = s[S.SplitExitTitle],
                            subtitle = s[S.SplitExitHint],
                            checked = splitExitEnabled,
                            onCheckedChange = { enabled ->
                                guard.run {
                                    splitExitEnabled = enabled
                                    backend.setForwardingSplitExitEnabled(enabled)
                                }
                            },
                        )
                        if (splitExitEnabled) {
                            OutlinedButton(
                                onClick = { showSplitProxyAppsDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "${s[S.SplitExitProxyApps]} (${s.format(S.SplitExitProxyAppsCount, proxyPackages.size)})",
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
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
                            val customUrlInvalid = exitDnsUrl.isNotEmpty() && !exitDnsUrl.startsWith("https://", ignoreCase = true)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = exitDnsUrl,
                                    onValueChange = { exitDnsUrl = it.trim() },
                                    label = { Text(s[S.ExitDnsCustomUrl]) },
                                    isError = customUrlInvalid,
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    enabled = exitDnsUrl.startsWith("https://", ignoreCase = true),
                                    onClick = {
                                        val url = exitDnsUrl.trim()
                                        if (!url.startsWith("https://", ignoreCase = true)) {
                                            toast(s[S.InvalidHttpsUrl])
                                            return@TextButton
                                        }
                                        exitDnsUrl = url
                                        backend.setExitDnsCustomUrl(url)
                                        toast(s[S.Done])
                                    },
                                ) { Text(s[S.Save]) }
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
                        val socks5HostValid = socks5Host.trim().isNotEmpty()
                        val socks5PortInt = socks5Port.toIntOrNull()
                        val socks5PortValid = socks5PortInt != null && socks5PortInt in 1..65535
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
                                isError = !socks5HostValid,
                                singleLine = true,
                                modifier = Modifier.weight(1.6f),
                            )
                            OutlinedTextField(
                                value = socks5Port,
                                onValueChange = {
                                    socks5Port = it.filter { char -> char.isDigit() }.take(5)
                                },
                                label = { Text(s[S.Socks5Port]) },
                                isError = !socks5PortValid,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        OutlinedTextField(
                            value = socks5User,
                            onValueChange = { socks5User = it },
                            label = { Text(s[S.Socks5Username]) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = socks5Pass,
                            onValueChange = { socks5Pass = it },
                            label = { Text(s[S.Socks5Password]) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            androidx.compose.material3.Button(
                                enabled = socks5HostValid && socks5PortValid,
                                onClick = {
                                    val host = socks5Host.trim()
                                    val port = socks5Port.toIntOrNull()
                                    if (host.isEmpty() || port == null || port !in 1..65535) {
                                        toast(s[S.InvalidHostOrPort])
                                        return@Button
                                    }
                                    socks5Host = host
                                    backend.setSocks5Config(
                                        host = host,
                                        port = port,
                                        username = socks5User,
                                        password = socks5Pass,
                                    )
                                    toast(s[S.Done])
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text(s[S.Save], maxLines = 1, softWrap = false) }
                            OutlinedButton(
                                onClick = {
                                    val host = socks5Host.trim()
                                    val port = socks5Port.toIntOrNull()
                                    if (host.isEmpty() || port == null || port !in 1..65535) {
                                        toast(s[S.InvalidHostOrPort])
                                        return@OutlinedButton
                                    }
                                    socks5Host = host
                                    proxyTesting = true
                                    probeReport = null
                                    scope.launch {
                                        val report = Socks5Probe.diagnose(
                                            host = host,
                                            port = port,
                                            username = socks5User.trim().ifEmpty { null },
                                            password = socks5Pass,
                                        )
                                        probeReport = report
                                        proxyTesting = false
                                        toast(
                                            if (report.allOk) {
                                                "${s[S.ProxyTestSuccess]} (${report.latencyMs}ms)"
                                            } else {
                                                val err = when {
                                                    !report.tcpOk -> report.tcpMessage
                                                    !report.authOk -> report.authMessage
                                                    else -> report.outboundMessage
                                                }
                                                s.format(S.ProxyTestFailed, err)
                                            },
                                        )
                                    }
                                },
                                enabled = !proxyTesting && socks5HostValid && socks5PortValid,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    if (proxyTesting) s[S.Loading] else s[S.ProxyTest],
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                        }
                        probeReport?.let { report ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                ProbeStageRow(s[S.ProbeStepTcp], report.tcpOk, report.tcpMessage)
                                ProbeStageRow(s[S.ProbeStepAuth], report.authOk, report.authMessage)
                                ProbeStageRow(
                                    s[S.ProbeStepOutbound],
                                    report.outboundOk,
                                    if (report.outboundOk) "${report.latencyMs} ms" else report.outboundMessage,
                                )
                                if (report.tcpOk && report.authOk && !report.outboundOk) {
                                    Text(
                                        s[S.ProbeOutboundHint],
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
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
                    if (privateDnsStrict) {
                        Text(
                            "${s[S.PrivateDnsTitle]}：${s[S.PrivateDnsHint]}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    }
                }
            }

            if (viaProxy) {
                item {
                    SectionCard(s[S.ProxyApp]) {
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
                }
            }

            item {
                SectionCard(s[S.DirectApps]) {
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

    val apps = pickerApps
    if (showSplitProxyAppsDialog && apps != null) {
        DirectAppsDialog(
            title = s[S.SplitExitProxyApps],
            apps = apps,
            initial = proxyPackages,
            onDismiss = { showSplitProxyAppsDialog = false },
            onSave = { selected ->
                guard.run {
                    proxyPackages = selected
                    backend.setForwardingProxyPackages(selected)
                }
                showSplitProxyAppsDialog = false
            },
        )
    }
    if (showProxyAppDialog && apps != null) {
        ProxyAppDialog(
            apps = apps,
            selected = proxyPackage,
            onDismiss = { showProxyAppDialog = false },
            onSelect = { packageName ->
                guard.run {
                    proxyPackage = packageName
                    backend.setSocks5BypassPackage(packageName)
                }
                showProxyAppDialog = false
            },
        )
    }

    val allowedByRulePackages = remember(apps, ruleSet.version, network) {
        apps?.let { backend.allowedByRulePackages(it) } ?: emptySet()
    }
    val deniedByRulePackages = remember(apps, ruleSet.version) {
        apps?.let { backend.deniedByRulePackages(it) } ?: emptySet()
    }
    if (showDirectAppsDialog && apps != null) {
        DirectAppsDialog(
            title = s[S.DirectApps],
            apps = apps,
            initial = directPackages,
            allowedByRulePackages = allowedByRulePackages,
            deniedByRulePackages = deniedByRulePackages,
            onDismiss = { showDirectAppsDialog = false },
            onSave = { packages ->
                guard.run {
                    directPackages = packages
                    backend.setForwardingDirectPackages(packages)
                }
                showDirectAppsDialog = false
            },
        )
    }
}

@Composable
private fun ProbeStageRow(label: String, ok: Boolean, detail: String) {
    val color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${if (ok) "✓" else "✗"} $label",
            style = MaterialTheme.typography.bodySmall,
            color = color,
            fontWeight = FontWeight.Medium,
        )
        Text(
            detail,
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
