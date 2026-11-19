package com.networkcontrol.android.kotlin

import com.networkcontrol.android.kotlin.core.model.AppIdentity
import com.networkcontrol.android.kotlin.core.model.AppMatcher
import com.networkcontrol.android.kotlin.core.model.AppRouting
import com.networkcontrol.android.kotlin.core.model.AppRoutingDecision
import com.networkcontrol.android.kotlin.core.model.AppRoutingReason
import com.networkcontrol.android.kotlin.core.model.Direction
import com.networkcontrol.android.kotlin.core.model.DomainCandidates
import com.networkcontrol.android.kotlin.core.model.FilterMode
import com.networkcontrol.android.kotlin.core.model.FlowInfo
import com.networkcontrol.android.kotlin.core.model.L4Protocol
import com.networkcontrol.android.kotlin.core.model.NetworkZone
import com.networkcontrol.android.kotlin.core.model.RemoteTarget
import com.networkcontrol.android.kotlin.core.model.Rule
import com.networkcontrol.android.kotlin.core.model.RuleConditions
import com.networkcontrol.android.kotlin.core.model.RuleLifetime
import com.networkcontrol.android.kotlin.core.model.RuleSource
import com.networkcontrol.android.kotlin.core.model.VerdictAction
import com.networkcontrol.android.kotlin.core.model.VpnRoutingPlan
import com.networkcontrol.android.kotlin.core.rules.RuleSet
import com.networkcontrol.android.kotlin.engine.blocklist.BlocklistManager
import com.networkcontrol.android.kotlin.engine.blocklist.BlocklistParser
import com.networkcontrol.android.kotlin.engine.blocklist.DomainBlocklist
import com.networkcontrol.android.kotlin.engine.vpn.ForwardingDecision
import com.networkcontrol.android.kotlin.engine.vpn.ForwardingPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class BlocklistTest {
    private fun parse(text: String) = BlocklistParser.parse(text.byteInputStream())

    // ---- 解析與匹配 ----

    @Test
    fun adblockRulesBlockDomainAndSubdomains() {
        val list = parse("! Title: Test\n||ads.example.com^\n||tracker.net^\$important\n").list
        assertTrue(list.matches("ads.example.com"))
        assertTrue(list.matches("x.y.ads.example.com"))
        assertTrue(list.matches("ADS.Example.com."))
        assertTrue(list.matches("tracker.net"))
        assertFalse(list.matches("example.com"))
        assertFalse(list.matches("notads.example.com"))
    }

    @Test
    fun hostsAndPlainEntriesAreExact() {
        val list = parse("# comment\n0.0.0.0 ads.example.com\n127.0.0.1 localhost\nplain.example.org # trailing\n").list
        assertTrue(list.matches("ads.example.com"))
        assertFalse(list.matches("sub.ads.example.com"))
        assertTrue(list.matches("plain.example.org"))
        assertFalse(list.matches("localhost"))
        assertEquals(2, list.size)
    }

    @Test
    fun wildcardAndExceptionsAreHonoured() {
        val list = parse("*.cdn-ads.com\n||example.com^\n@@||good.example.com^\n").list
        assertTrue(list.matches("x.cdn-ads.com"))
        assertTrue(list.matches("bad.example.com"))
        assertFalse(list.matches("good.example.com"))
        assertFalse(list.matches("a.good.example.com"))
    }

    @Test
    fun unsupportedRulesAreSkipped() {
        val result = parse(
            "/ads[0-9]+/\n||example.com/path^\n||rewrite.com^\$dnsrewrite=1.2.3.4\n|http://x.com\n0.0.0.0 0.0.0.0\n||ok.com^\n",
        )
        assertEquals(1, result.list.size)
        assertTrue(result.list.matches("ok.com"))
        assertTrue(result.skipped >= 4)
    }

    @Test
    fun topLevelDomainAloneNeverMatches() {
        val list = parse("||com^\n").list
        // "com" 不是合法的主機名稱（沒有點），整條規則被略過。
        assertFalse(list.matches("example.com"))
        assertEquals(0, list.size)
    }

    @Test
    fun ipLiteralsAreNotDomains() {
        val list = parse("1.2.3.4\n||5.6.7.8^\n").list
        assertEquals(0, list.size)
        assertFalse(list.matches("1.2.3.4"))
    }

    @Test
    fun compiledListRoundTripsThroughFile() {
        val dir = Files.createTempDirectory("bl").toFile()
        try {
            val original = parse("||a.com^\nb.com\n@@||ok.a.com^\n").list
            val file = File(dir, "x.bin")
            original.writeTo(file)
            val loaded = DomainBlocklist.readFrom(file)
            assertTrue(loaded.matches("x.a.com"))
            assertTrue(loaded.matches("b.com"))
            assertFalse(loaded.matches("ok.a.com"))
            assertEquals(original.size, loaded.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- 判決順序 ----

    private val app = AppIdentity(path = "android-app://com.example", displayName = "Example")

    private fun plan(decision: AppRoutingDecision, mode: FilterMode = FilterMode.Ask, reason: AppRoutingReason = AppRoutingReason.Rule) =
        VpnRoutingPlan(mode, emptySet(), mapOf(10100 to AppRouting(10100, "com.example", listOf("com.example"), decision, reason)), emptyList())

    @Test
    fun blocklistBlocksAllowedAppsButNotUserAllowedDomains() {
        val allowed = plan(AppRoutingDecision.Bypass)
        assertEquals(ForwardingDecision.Blocklisted, ForwardingPolicy.decide(allowed, 10100, blocklisted = { true }) { null })
        assertEquals(ForwardingDecision.Forward, ForwardingPolicy.decide(allowed, 10100, blocklisted = { false }) { null })
        // 使用者的條件允許規則優先於名單
        assertEquals(ForwardingDecision.Forward, ForwardingPolicy.decide(allowed, 10100, blocklisted = { true }) { VerdictAction.Allow })
    }

    @Test
    fun blocklistReplacesPromptsButKeepsHardBlocks() {
        assertEquals(
            ForwardingDecision.Blocklisted,
            ForwardingPolicy.decide(plan(AppRoutingDecision.BlockAndAsk, reason = AppRoutingReason.Mode), 10100, blocklisted = { true }) { null },
        )
        assertEquals(ForwardingDecision.Block, ForwardingPolicy.decide(plan(AppRoutingDecision.Block), 10100, blocklisted = { true }) { null })
        // 查不到擁有者時名單仍然生效
        assertEquals(ForwardingDecision.Blocklisted, ForwardingPolicy.decide(plan(AppRoutingDecision.Bypass), null, blocklisted = { true }) { null })
    }

    @Test
    fun blocklistIgnoredForSystemUidAndDisabledMode() {
        assertEquals(ForwardingDecision.Forward, ForwardingPolicy.decide(plan(AppRoutingDecision.Bypass), 1000, blocklisted = { true }) { null })
        val disabled = VpnRoutingPlan(FilterMode.Disabled, emptySet(), emptyMap(), emptyList())
        assertEquals(ForwardingDecision.Forward, ForwardingPolicy.decide(disabled, 10100, blocklisted = { true }) { null })
    }

    @Test
    fun domainAllowRuleIsFoundEvenWhenShadowedByAppRule() {
        val appAllow = Rule(
            id = "app", app = AppMatcher(app.path), conditions = RuleConditions(direction = Direction.Outbound),
            action = VerdictAction.Allow, lifetime = RuleLifetime.Permanent, source = RuleSource.User, createdAt = 1, updatedAt = 1,
        )
        val domainAllow = appAllow.copy(
            id = "domain", app = null,
            conditions = RuleConditions(direction = Direction.Outbound, remote = RemoteTarget.Domain("ads.example.com")),
        )
        val rules = RuleSet(listOf(appAllow, domainAllow))
        val flow = FlowInfo(
            flowId = 0, direction = Direction.Outbound, protocol = L4Protocol.Tcp,
            local = InetSocketAddress("10.111.222.1", 40000), remote = InetSocketAddress("100.64.0.3", 443),
            app = app, timestamp = 0,
        )
        assertTrue(rules.allowsDomain(flow, app, DomainCandidates(listOf("ads.example.com")), NetworkZone.Internet))
        assertFalse(rules.allowsDomain(flow, app, DomainCandidates(listOf("other.com")), NetworkZone.Internet))
    }

    // ---- 下載與管理 ----

    private lateinit var http: MiniHttpServer
    private lateinit var dir: File
    private lateinit var scope: CoroutineScope
    private val hits = AtomicInteger()
    private var body = "||ads.example.com^\n||track.example.net^\n"

    @Before
    fun startServer() {
        http = MiniHttpServer { path, headers ->
            when (path) {
                "/list.txt" -> {
                    hits.incrementAndGet()
                    if (headers["if-none-match"] == "\"v1\"") {
                        MiniHttpServer.Response(304)
                    } else {
                        MiniHttpServer.Response(200, mapOf("ETag" to "\"v1\""), body.toByteArray())
                    }
                }
                "/broken.txt" -> MiniHttpServer.Response(500)
                else -> MiniHttpServer.Response(404)
            }
        }
        dir = Files.createTempDirectory("blm").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun stopServer() {
        scope.cancel()
        http.close()
        dir.deleteRecursively()
    }

    private fun base() = "http://127.0.0.1:${http.port}"

    /** 測試只允許連本機伺服器，內建名單的真實地址一律失敗。 */
    private fun manager(offlineMode: () -> Boolean = { false }) = BlocklistManager(
        dir,
        scope,
        open = { url ->
            if (url.host != "127.0.0.1") throw java.io.IOException("offline")
            url.openConnection() as java.net.HttpURLConnection
        },
        autoDownload = false,
        offlineMode = offlineMode,
    )

    @Test
    fun subscriptionDownloadsFallsBackToMirrorAndUsesConditionalRequests() = runBlocking {
        val manager = manager()
        manager.initialize()
        manager.setEnabled(true)
        // 只留一個自訂來源，避免測試去連真正的網路
        manager.state.value.sources.filter { it.builtin && it.enabled }.forEach { manager.setSourceEnabled(it.id, false) }
        assertNull(manager.addCustom("test", "${base()}/list.txt"))
        val id = manager.state.value.sources.first { !it.builtin }.id

        val first = manager.updateNow()
        assertEquals(1, first.updated)
        assertEquals("custom", manager.matcher.value.match("x.ads.example.com")?.substringBefore('-'))
        assertEquals(id, manager.matcher.value.match("track.example.net"))
        assertNull(manager.matcher.value.match("example.com"))

        val second = manager.updateNow()
        assertEquals(1, second.unchanged)

        // 重新建立管理器：從編譯好的檔案載入，不需要再下載
        val before = hits.get()
        val reloaded = manager()
        reloaded.initialize()
        assertEquals(id, reloaded.matcher.value.match("ads.example.com"))
        assertEquals(before, hits.get())

        reloaded.setEnabled(false)
        assertNull(reloaded.matcher.value.match("ads.example.com"))
    }

    @Test
    fun failedUpdateKeepsPreviousList() = runBlocking {
        val manager = manager()
        manager.initialize()
        manager.setEnabled(true)
        manager.state.value.sources.filter { it.builtin && it.enabled }.forEach { manager.setSourceEnabled(it.id, false) }
        manager.addCustom("test", "${base()}/list.txt")
        manager.updateNow()
        body = ""
        http.override = { MiniHttpServer.Response(500) }
        val result = manager.updateNow()
        assertEquals(1, result.failed)
        assertTrue(manager.matcher.value.match("ads.example.com") != null)
        assertTrue(manager.state.value.sources.first { !it.builtin }.lastError!!.contains("500"))
    }

    @Test
    fun rejectsPlainHttpForRemoteHostsAndImportsFiles() = runBlocking {
        val manager = manager()
        manager.initialize()
        manager.setEnabled(true)
        assertEquals("https required", manager.addCustom("x", "http://example.com/list.txt"))
        val count = manager.import("local", "0.0.0.0 imported.example\n||wild.example^\n".byteInputStream())
        assertEquals(2, count)
        assertTrue(manager.matcher.value.match("a.wild.example") != null)
    }

    @Test
    fun firstEnableSelectsDefaultListsOnlyOnce() = runBlocking {
        val manager = manager()
        manager.initialize()
        manager.setEnabled(true)
        val enabled = manager.state.value.sources.filter { it.enabled }.map { it.id }.toSet()
        assertEquals(setOf("anti-ad", "hagezi-multi"), enabled)
        manager.setSourceEnabled("anti-ad", false)
        manager.setEnabled(false)
        manager.setEnabled(true)
        assertFalse(manager.state.value.sources.first { it.id == "anti-ad" }.enabled)
    }

    @Test
    fun offlineModeBlocksOnlineDownloadsButAllowsLocalImport() = runBlocking {
        val manager = manager(offlineMode = { true })
        manager.initialize()
        manager.setEnabled(true)
        manager.state.value.sources.filter { it.builtin && it.enabled }.forEach { manager.setSourceEnabled(it.id, false) }
        assertNull(manager.addCustom("test", "${base()}/list.txt"))

        val before = hits.get()
        val result = manager.updateNow()
        assertEquals(0, result.updated)
        assertEquals(0, result.unchanged)
        assertEquals(0, result.failed)
        assertEquals(before, hits.get())

        val count = manager.import("offline-local", "||offline.example^\n".byteInputStream())
        assertEquals(1, count)
        assertTrue(manager.matcher.value.match("sub.offline.example") != null)
    }
}

/** 測試用的極簡 HTTP/1.1 伺服器（每個請求一個連線，回應後關閉）。 */
class MiniHttpServer(private val handler: (String, Map<String, String>) -> Response) : AutoCloseable {
    class Response(val code: Int, val headers: Map<String, String> = emptyMap(), val body: ByteArray = ByteArray(0))

    @Volatile
    var override: ((String) -> Response)? = null
    private val server = java.net.ServerSocket(0, 50, java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    val port: Int get() = server.localPort

    init {
        kotlin.concurrent.thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                kotlin.concurrent.thread(isDaemon = true) { socket.use(::serve) }
            }
        }
    }

    private fun serve(socket: java.net.Socket) {
        val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = reader.readLine() ?: return
        val path = requestLine.split(' ').getOrNull(1) ?: "/"
        val headers = HashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val response = override?.invoke(path) ?: handler(path, headers)
        val out = socket.getOutputStream()
        val crlf = "\r\n"
        val head = buildString {
            append("HTTP/1.1 ${response.code} X$crlf")
            response.headers.forEach { (k, v) -> append("$k: $v$crlf") }
            append("Content-Length: ${response.body.size}$crlf")
            append("Connection: close$crlf$crlf")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(response.body)
        out.flush()
    }

    override fun close() {
        server.close()
    }
}
