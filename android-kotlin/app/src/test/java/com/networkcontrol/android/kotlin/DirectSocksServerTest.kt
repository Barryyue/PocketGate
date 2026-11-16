package com.networkcontrol.android.kotlin

import com.networkcontrol.android.kotlin.engine.vpn.DirectSocksServer
import com.networkcontrol.android.kotlin.engine.vpn.Socks5Wire
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class DirectSocksServerTest {
    private val loopback: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private lateinit var echo: ServerSocket
    private lateinit var udpEcho: DatagramSocket
    private lateinit var server: DirectSocksServer

    @Before
    fun setUp() {
        echo = ServerSocket(0, 50, loopback)
        thread(isDaemon = true) {
            while (!echo.isClosed) {
                val socket = runCatching { echo.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    socket.use {
                        // 讀到用戶端半關閉為止，再整批回送：同時驗證半關閉後下行仍然送達。
                        val data = it.getInputStream().readBytes()
                        it.getOutputStream().write(data)
                    }
                }
            }
        }
        udpEcho = DatagramSocket(InetSocketAddress(loopback, 0))
        thread(isDaemon = true) {
            val buffer = ByteArray(2048)
            while (!udpEcho.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                runCatching { udpEcho.receive(packet) }.getOrNull() ?: break
                udpEcho.send(DatagramPacket(packet.data, packet.offset, packet.length, packet.socketAddress))
            }
        }
        server = DirectSocksServer(resolve = { host -> if (host == "echo.test") arrayOf(loopback) else InetAddress.getAllByName(host) })
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        echo.close()
        udpEcho.close()
    }

    private fun open(): Pair<Socket, DataInputStream> {
        val socket = Socket(loopback, server.port)
        socket.soTimeout = 5_000
        return socket to DataInputStream(socket.getInputStream())
    }

    private fun authenticate(socket: Socket, input: DataInputStream, password: String = server.password): Int {
        val out = socket.getOutputStream()
        out.write(byteArrayOf(5, 1, 2))
        assertEquals(5, input.readUnsignedByte())
        assertEquals(2, input.readUnsignedByte())
        val user = server.username.toByteArray()
        val pass = password.toByteArray()
        out.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass)
        assertEquals(1, input.readUnsignedByte())
        return input.readUnsignedByte()
    }

    private fun readReply(input: DataInputStream): Int {
        assertEquals(5, input.readUnsignedByte())
        val code = input.readUnsignedByte()
        input.readUnsignedByte()
        val length = when (input.readUnsignedByte()) {
            Socks5Wire.ATYP_IPV6 -> 16
            else -> 4
        }
        input.readFully(ByteArray(length + 2))
        return code
    }

    @Test
    fun rejectsClientsWithoutPassword() {
        val (socket, input) = open()
        socket.use {
            it.getOutputStream().write(byteArrayOf(5, 1, 0))
            assertEquals(5, input.readUnsignedByte())
            assertEquals(0xFF, input.readUnsignedByte())
        }
    }

    @Test
    fun rejectsWrongPassword() {
        val (socket, input) = open()
        socket.use { assertEquals(1, authenticate(it, input, password = "wrong")) }
    }

    @Test
    fun connectsByAddressAndRelaysAfterHalfClose() {
        val (socket, input) = open()
        socket.use {
            assertEquals(0, authenticate(it, input))
            val port = echo.localPort
            it.getOutputStream().write(
                byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, (port ushr 8).toByte(), port.toByte()),
            )
            assertEquals(Socks5Wire.REP_SUCCEEDED, readReply(input))
            val payload = ByteArray(200_000) { i -> (i % 251).toByte() }
            it.getOutputStream().write(payload)
            it.shutdownOutput()
            assertArrayEquals(payload, input.readBytes())
        }
    }

    @Test
    fun connectsByDomainName() {
        val (socket, input) = open()
        socket.use {
            assertEquals(0, authenticate(it, input))
            val name = "echo.test".toByteArray()
            val port = echo.localPort
            it.getOutputStream().write(
                byteArrayOf(5, 1, 0, 3, name.size.toByte()) + name + byteArrayOf((port ushr 8).toByte(), port.toByte()),
            )
            assertEquals(Socks5Wire.REP_SUCCEEDED, readReply(input))
            it.getOutputStream().write("hello".toByteArray())
            it.shutdownOutput()
            assertEquals("hello", String(input.readBytes()))
        }
    }

    @Test
    fun reportsRefusedConnection() {
        val closedPort = ServerSocket(0, 1, loopback).use { it.localPort }
        val (socket, input) = open()
        socket.use {
            assertEquals(0, authenticate(it, input))
            it.getOutputStream().write(
                byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, (closedPort ushr 8).toByte(), closedPort.toByte()),
            )
            assertEquals(Socks5Wire.REP_CONNECTION_REFUSED, readReply(input))
        }
    }

    @Test
    fun relaysUdpThroughAssociation() {
        val (control, input) = open()
        control.use {
            assertEquals(0, authenticate(it, input))
            it.getOutputStream().write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
            assertEquals(5, input.readUnsignedByte())
            assertEquals(0, input.readUnsignedByte())
            input.readUnsignedByte()
            assertEquals(1, input.readUnsignedByte())
            val bound = InetAddress.getByAddress(ByteArray(4).also { b -> input.readFully(b) })
            val relayPort = input.readUnsignedShort()

            DatagramSocket(InetSocketAddress(loopback, 0)).use { client ->
                client.soTimeout = 5_000
                val header = Socks5Wire.udpHeader(loopback, udpEcho.localPort)
                val message = header + "ping".toByteArray()
                client.send(DatagramPacket(message, message.size, bound, relayPort))
                val buffer = ByteArray(1024)
                val reply = DatagramPacket(buffer, buffer.size)
                client.receive(reply)
                val datagram = Socks5Wire.parseUdp(buffer, reply.length)
                assertNotNull(datagram)
                assertEquals(udpEcho.localPort, datagram!!.port)
                assertEquals("ping", String(buffer, datagram.payloadOffset, reply.length - datagram.payloadOffset))
            }
        }
    }

    @Test
    fun hevConfigCarriesCredentialsOnlyWhenSet() {
        val dir = java.nio.file.Files.createTempDirectory("direct").toFile()
        try {
            val file = java.io.File(dir, "hev.yml")
            com.networkcontrol.android.kotlin.engine.vpn.writeForwardingConfig(
                file,
                com.networkcontrol.android.kotlin.engine.vpn.VpnTunnelConfig(
                    emptySet(),
                    forwarding = true,
                    upstreamPort = server.port,
                    upstreamUsername = server.username,
                    upstreamPassword = server.password,
                ),
            )
            val text = file.readText()
            org.junit.Assert.assertTrue(text.contains("username: '${server.username}'"))
            org.junit.Assert.assertTrue(text.contains("password: '${server.password}'"))
            com.networkcontrol.android.kotlin.engine.vpn.writeForwardingConfig(
                file,
                com.networkcontrol.android.kotlin.engine.vpn.VpnTunnelConfig(emptySet(), forwarding = true),
            )
            org.junit.Assert.assertFalse(file.readText().contains("username:"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun parsesUdpDomainHeaderAndRejectsFragments() {
        val name = "example.org".toByteArray()
        val packet = byteArrayOf(0, 0, 0, 3, name.size.toByte()) + name + byteArrayOf(0x01, 0xBB.toByte()) + byteArrayOf(9, 9)
        val datagram = Socks5Wire.parseUdp(packet, packet.size)!!
        assertEquals("example.org", datagram.host)
        assertEquals(443, datagram.port)
        assertEquals(packet.size - 2, datagram.payloadOffset)
        packet[2] = 1
        assertNull(Socks5Wire.parseUdp(packet, packet.size))
    }
}
