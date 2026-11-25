package com.networkcontrol.android.kotlin

import com.networkcontrol.android.kotlin.core.focus.FocusSchedule
import com.networkcontrol.android.kotlin.core.model.AppRoutingDecision
import com.networkcontrol.android.kotlin.core.model.AppRoutingReason
import com.networkcontrol.android.kotlin.core.model.FilterMode
import com.networkcontrol.android.kotlin.core.model.InstalledApp
import com.networkcontrol.android.kotlin.core.model.Profile
import com.networkcontrol.android.kotlin.core.model.UserSettings
import com.networkcontrol.android.kotlin.core.rules.RuleSet
import com.networkcontrol.android.kotlin.data.backup.FullBackupCodec
import com.networkcontrol.android.kotlin.data.backup.PortablePrefsSnapshot
import com.networkcontrol.android.kotlin.engine.vpn.DirectSocksServer
import com.networkcontrol.android.kotlin.engine.vpn.ExitRoute
import com.networkcontrol.android.kotlin.engine.vpn.Socks5Wire
import com.networkcontrol.android.kotlin.engine.vpn.SplitExitRouter
import com.networkcontrol.android.kotlin.engine.vpn.UpstreamSocks5Config
import com.networkcontrol.android.kotlin.engine.vpn.VpnRoutingPlanner
import com.networkcontrol.android.kotlin.platform.automation.ExternalAutomationCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class OptPhaseTest {

    @Test
    fun foregroundOnlyBlocksInBackgroundAndAllowsInForeground() {
        val app = InstalledApp(
            packageName = "com.example.chat",
            uid = 10123,
            label = "Chat",
            isSystem = false,
            signerSha256 = "sig1",
            versionCode = 1L,
            lastUpdated = 0L,
        )
        val rules = RuleSet.Empty

        // 背景（前台為其他應用）：阻斷
        val bgPlan = VpnRoutingPlanner.plan(
            rules = rules,
            mode = FilterMode.Blacklist,
            apps = listOf(app),
            selfPackage = "com.networkcontrol.android.kotlin",
            foregroundOnlyPackages = setOf(app.packageName),
            foregroundPackage = "com.other.app",
        )
        assertEquals(AppRoutingDecision.Block, bgPlan.find(app.uid)?.decision)
        assertEquals(AppRoutingReason.BackgroundBlocked, bgPlan.find(app.uid)?.reason)

        // 息屏（foregroundPackage == null）：阻斷
        val screenOffPlan = VpnRoutingPlanner.plan(
            rules = rules,
            mode = FilterMode.Blacklist,
            apps = listOf(app),
            selfPackage = "com.networkcontrol.android.kotlin",
            foregroundOnlyPackages = setOf(app.packageName),
            foregroundPackage = null,
        )
        assertEquals(AppRoutingDecision.Block, screenOffPlan.find(app.uid)?.decision)
        assertEquals(AppRoutingReason.BackgroundBlocked, screenOffPlan.find(app.uid)?.reason)

        // 前台（foregroundPackage == app）：按正常模式放行
        val fgPlan = VpnRoutingPlanner.plan(
            rules = rules,
            mode = FilterMode.Blacklist,
            apps = listOf(app),
            selfPackage = "com.networkcontrol.android.kotlin",
            foregroundOnlyPackages = setOf(app.packageName),
            foregroundPackage = app.packageName,
        )
        assertEquals(AppRoutingDecision.Bypass, fgPlan.find(app.uid)?.decision)
        assertEquals(AppRoutingReason.Mode, fgPlan.find(app.uid)?.reason)
    }

    @Test
    fun externalAutomationAuthAndCommandParsing() {
        assertFalse(ExternalAutomationCommand.authorize(enabled = false, expectedToken = "", providedToken = null))
        assertTrue(ExternalAutomationCommand.authorize(enabled = true, expectedToken = "", providedToken = null))
        assertFalse(ExternalAutomationCommand.authorize(enabled = true, expectedToken = "secret", providedToken = null))
        assertFalse(ExternalAutomationCommand.authorize(enabled = true, expectedToken = "secret", providedToken = "wrong"))
        assertTrue(ExternalAutomationCommand.authorize(enabled = true, expectedToken = "secret", providedToken = "secret"))

        assertEquals(
            ExternalAutomationCommand.SetProtection(true),
            ExternalAutomationCommand.parse("protection_on"),
        )
        assertEquals(
            ExternalAutomationCommand.SetMode(FilterMode.BlockAll),
            ExternalAutomationCommand.parse("set_mode", mode = "block_all"),
        )
        assertEquals(
            ExternalAutomationCommand.SwitchProfile("Work"),
            ExternalAutomationCommand.parse("switch_profile", profile = "Work"),
        )
        assertEquals(
            ExternalAutomationCommand.FocusStart(45),
            ExternalAutomationCommand.parse("focus_start", minutes = 45),
        )
        assertNull(ExternalAutomationCommand.parse("unknown_cmd"))
    }

    @Test
    fun splitExitRouterNormalizesExpiresAndChainsToUpstreamSocks5() {
        val router = SplitExitRouter(ttlMs = 5_000L)
        val upstreamCfg = UpstreamSocks5Config("127.0.0.1", 1080)
        router.record("Example.COM.", 443, ExitRoute.Socks5(upstreamCfg), nowMs = 1_000L)
        assertEquals(ExitRoute.Socks5(upstreamCfg), router.resolve("example.com", 443, nowMs = 2_000L))
        assertEquals(ExitRoute.Direct, router.resolve("example.com", 443, nowMs = 7_000L))

        // 真實本機 socket 鏈路測試：client -> routerServer -> upstreamSocks5 -> echoServer
        val echoServer = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val echoThread = Thread {
            runCatching {
                echoServer.accept().use { s ->
                    val data = ByteArray(16)
                    val n = s.getInputStream().read(data)
                    if (n > 0) s.getOutputStream().write(data, 0, n)
                }
            }
        }.apply { start() }

        val upstreamServer = DirectSocksServer().apply { start() }
        val routerServer = DirectSocksServer(
            routeFor = {
                ExitRoute.Socks5(
                    UpstreamSocks5Config(
                        host = "127.0.0.1",
                        port = upstreamServer.port,
                        username = upstreamServer.username,
                        password = upstreamServer.password,
                    ),
                )
            },
        ).apply { start() }

        try {
            Socket("127.0.0.1", routerServer.port).use { client ->
                client.soTimeout = 5_000
                val out = client.getOutputStream()
                val inp = DataInputStream(client.getInputStream())
                // RFC 1928 + RFC 1929 auth against routerServer
                out.write(byteArrayOf(5, 1, Socks5Wire.METHOD_USER_PASS.toByte()))
                assertEquals(5, inp.readUnsignedByte())
                assertEquals(Socks5Wire.METHOD_USER_PASS, inp.readUnsignedByte())
                val u = routerServer.username.toByteArray()
                val p = routerServer.password.toByteArray()
                out.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p)
                assertEquals(1, inp.readUnsignedByte())
                assertEquals(0, inp.readUnsignedByte())
                // CONNECT 127.0.0.1:<echoPort>
                val port = echoServer.localPort
                out.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, (port ushr 8).toByte(), (port and 0xFF).toByte()))
                assertEquals(5, inp.readUnsignedByte())
                assertEquals(0, inp.readUnsignedByte())
                inp.readUnsignedByte() // rsv
                val atyp = inp.readUnsignedByte()
                if (atyp == 1) inp.skipBytes(4) else inp.skipBytes(16)
                inp.skipBytes(2)

                out.write("PING".toByteArray())
                out.flush()
                val resp = ByteArray(4)
                inp.readFully(resp)
                assertEquals("PING", String(resp))
            }
            assertEquals(1L, routerServer.counters.upstreamProxied.get())
        } finally {
            routerServer.close()
            upstreamServer.close()
            runCatching { echoServer.close() }
            echoThread.join(1_000)
        }
    }

    @Test
    fun backupCodecPreservesNewPortablePrefs() {
        val json = FullBackupCodec.export(
            rules = emptyList(),
            profiles = listOf(Profile(id = Profile.DEFAULT_ID, name = "Default", mode = FilterMode.Ask)),
            activeProfileId = Profile.DEFAULT_ID,
            userSettings = UserSettings(),
            portablePrefs = PortablePrefsSnapshot(
                foregroundOnlyPackages = setOf("com.a", "com.b"),
                forwardingSplitExitEnabled = true,
                forwardingProxyPackages = setOf("com.proxy.target"),
            ),
        )
        val parsed = FullBackupCodec.parse(json)
        assertNotNull(parsed.portablePrefs)
        assertEquals(setOf("com.a", "com.b"), parsed.portablePrefs?.foregroundOnlyPackages)
        assertEquals(true, parsed.portablePrefs?.forwardingSplitExitEnabled)
        assertEquals(setOf("com.proxy.target"), parsed.portablePrefs?.forwardingProxyPackages)
        assertEquals(setOf("com.a"), FocusSchedule(allowedPackages = setOf("com.a")).allowedPackages)
    }

    @Test
    fun splitExitUdpChainsThroughUpstreamSocks5() {
        val echoUdp = java.net.DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val echoThread = Thread {
            runCatching {
                val buf = ByteArray(64)
                val pkt = java.net.DatagramPacket(buf, buf.size)
                echoUdp.receive(pkt)
                echoUdp.send(java.net.DatagramPacket(pkt.data, pkt.offset, pkt.length, pkt.address, pkt.port))
            }
        }.apply { start() }

        val upstreamServer = DirectSocksServer().apply { start() }
        val routerServer = DirectSocksServer(
            routeFor = {
                ExitRoute.Socks5(
                    UpstreamSocks5Config(
                        host = "127.0.0.1",
                        port = upstreamServer.port,
                        username = upstreamServer.username,
                        password = upstreamServer.password,
                    ),
                )
            },
        ).apply { start() }

        try {
            Socket("127.0.0.1", routerServer.port).use { ctrl ->
                ctrl.soTimeout = 5_000
                val out = ctrl.getOutputStream()
                val inp = DataInputStream(ctrl.getInputStream())
                out.write(byteArrayOf(5, 1, Socks5Wire.METHOD_USER_PASS.toByte()))
                assertEquals(5, inp.readUnsignedByte())
                assertEquals(Socks5Wire.METHOD_USER_PASS, inp.readUnsignedByte())
                val u = routerServer.username.toByteArray()
                val p = routerServer.password.toByteArray()
                out.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p)
                assertEquals(1, inp.readUnsignedByte())
                assertEquals(0, inp.readUnsignedByte())

                // UDP ASSOCIATE 0.0.0.0:0
                out.write(byteArrayOf(5, Socks5Wire.CMD_UDP_ASSOCIATE.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
                assertEquals(5, inp.readUnsignedByte())
                assertEquals(0, inp.readUnsignedByte())
                inp.readUnsignedByte()
                val atyp = inp.readUnsignedByte()
                val bndAddr = if (atyp == 1) ByteArray(4).also { inp.readFully(it) } else ByteArray(16).also { inp.readFully(it) }
                val bndPort = inp.readUnsignedShort()
                val relayTarget = java.net.InetSocketAddress(InetAddress.getByAddress(bndAddr), bndPort)

                java.net.DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { clientUdp ->
                    clientUdp.soTimeout = 5_000
                    val header = Socks5Wire.udpHeader(InetAddress.getByName("127.0.0.1"), echoUdp.localPort)
                    val payload = "UDP-OK".toByteArray()
                    val full = header + payload
                    clientUdp.send(java.net.DatagramPacket(full, full.size, relayTarget))

                    val recvBuf = ByteArray(128)
                    val recvPkt = java.net.DatagramPacket(recvBuf, recvBuf.size)
                    clientUdp.receive(recvPkt)
                    val parsed = Socks5Wire.parseUdp(recvBuf, recvPkt.length)
                    assertNotNull(parsed)
                    val body = String(recvBuf, parsed!!.payloadOffset, recvPkt.length - parsed.payloadOffset)
                    assertEquals("UDP-OK", body)
                }
            }
            assertEquals(1L, routerServer.counters.upstreamUdpProxied.get())
        } finally {
            routerServer.close()
            upstreamServer.close()
            runCatching { echoUdp.close() }
            echoThread.join(1_000)
        }
    }
}
