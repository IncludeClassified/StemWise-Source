package app.stemwise.network

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlin.concurrent.thread
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class WifiBoundNetworkingTest {
    @Test
    fun actualHttpUsesOnlyTheCapturedWifiDnsAndSocketsAndAnIsolatedPool() {
        val loopback = InetAddress.getByName("127.0.0.1")
        ServerSocket(0, 1, loopback).use { server ->
            server.soTimeout = 5_000
            val served = CompletableFuture<String>()
            val worker = thread {
                runCatching {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val reader = socket.getInputStream().bufferedReader()
                        val first = reader.readLine()
                        while (!reader.readLine().isNullOrEmpty()) Unit
                        socket.getOutputStream().write(
                            "HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\nwifi".toByteArray()
                        )
                        first
                    }
                }.fold(served::complete, served::completeExceptionally)
            }
            val defaultSockets = CountingSocketFactory()
            val wifiSockets = CountingSocketFactory()
            val wifiLookups = AtomicInteger()
            val base = OkHttpClient.Builder().socketFactory(defaultSockets).dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    throw UnknownHostException("default DNS must not be used")
            }).build()
            val route = WifiNetworkRoute(7L, wifiSockets, object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    assertEquals("wifi.invalid", hostname)
                    wifiLookups.incrementAndGet()
                    return listOf(loopback)
                }
            }) {}
            val bound = boundWifiHttpClient(base, route)
            try {
                assertNotSame(base.connectionPool, bound.connectionPool)
                bound.newCall(Request.Builder().url("http://wifi.invalid:${server.localPort}/song").build())
                    .execute().use { assertEquals("wifi", it.body!!.string()) }
                assertEquals("GET /song HTTP/1.1", served.get(5, TimeUnit.SECONDS))
                assertEquals(1, wifiLookups.get())
                assertEquals(1, wifiSockets.created.get())
                assertEquals(0, defaultSockets.created.get())
            } finally {
                bound.connectionPool.evictAll()
                server.close()
                worker.join(5_000L)
            }
            assertFalse("HTTP fixture thread leaked", worker.isAlive)
        }
    }

    @Test
    fun lostWifiCannotOpenAnotherSocketThroughTheDefaultNetwork() {
        val wifiSockets = CountingSocketFactory()
        val defaultSockets = CountingSocketFactory()
        var allowed = true
        val route = WifiNetworkRoute(9L, wifiSockets, Dns.SYSTEM) {
            if (!allowed) throw UnmeteredNetworkRequiredException(operationStarted = true)
        }
        val bound = boundWifiHttpClient(OkHttpClient.Builder().socketFactory(defaultSockets).build(), route)
        allowed = false
        val error = assertThrows(UnmeteredNetworkRequiredException::class.java) {
            bound.newCall(Request.Builder().url("http://127.0.0.1:1/").build()).execute()
        }
        assertTrue(error.operationStarted)
        assertEquals(0, wifiSockets.created.get())
        assertEquals(0, defaultSockets.created.get())
    }

    private class CountingSocketFactory : SocketFactory() {
        val created = AtomicInteger()
        override fun createSocket(): Socket = Socket().also { created.incrementAndGet() }
        override fun createSocket(host: String, port: Int): Socket = createSocket().apply {
            connect(java.net.InetSocketAddress(host, port))
        }
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
            createSocket().apply { bind(java.net.InetSocketAddress(local, localPort)); connect(java.net.InetSocketAddress(host, port)) }
        override fun createSocket(host: InetAddress, port: Int): Socket = createSocket().apply {
            connect(java.net.InetSocketAddress(host, port))
        }
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
            createSocket().apply { bind(java.net.InetSocketAddress(local, localPort)); connect(java.net.InetSocketAddress(host, port)) }
    }
}
