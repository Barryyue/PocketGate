package com.networkcontrol.android.kotlin

import com.networkcontrol.android.kotlin.engine.vpn.DirectSocksServer
import com.networkcontrol.android.kotlin.platform.Socks5Probe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** 用內建出口（一律要求帳號密碼）測試探針的 RFC 1929 驗證流程。 */
class Socks5AuthTest {
    private lateinit var server: DirectSocksServer

    @Before
    fun setUp() {
        server = DirectSocksServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun handshakeSucceedsWithCredentials() = runBlocking {
        assertNull(Socks5Probe.handshake("127.0.0.1", server.port, username = server.username, password = server.password))
    }

    @Test
    fun handshakeReportsMissingOrWrongCredentials() = runBlocking {
        assertEquals("proxy requires username / password", Socks5Probe.handshake("127.0.0.1", server.port))
        assertEquals(
            "wrong username / password",
            Socks5Probe.handshake("127.0.0.1", server.port, username = server.username, password = "nope"),
        )
    }
}
