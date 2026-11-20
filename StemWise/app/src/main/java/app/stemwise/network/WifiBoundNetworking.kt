package app.stemwise.network

import java.net.Proxy
import javax.net.SocketFactory
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

/** An exact Android Network, never the process default route or default DNS. */
internal class WifiNetworkRoute(
    val networkHandle: Long,
    val socketFactory: SocketFactory,
    val dns: Dns,
    val requireCurrent: () -> Unit
)

internal fun boundWifiHttpClient(base: OkHttpClient, route: WifiNetworkRoute): OkHttpClient {
    check(route.networkHandle != 0L) { "WIFI_BINDING_UNAVAILABLE" }
    route.requireCurrent()
    return base.newBuilder()
        // A pool from an unbound/default client must never supply an already-connected socket.
        .connectionPool(ConnectionPool())
        .socketFactory(route.socketFactory)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                route.requireCurrent()
                return route.dns.lookup(hostname).also { route.requireCurrent() }
            }
        })
        // Prevent global/PAC proxy selection from introducing another network-owning path.
        .proxy(Proxy.NO_PROXY)
        .addInterceptor { chain ->
            route.requireCurrent()
            chain.proceed(chain.request())
        }
        .build()
}

/** Coil and other callers must obtain sockets through the same app-owned Wi-Fi authority. */
internal class WifiBoundCallFactory(
    private val networkAccess: AppNetworkAccess,
    private val base: OkHttpClient
) : Call.Factory {
    override fun newCall(request: Request): Call = networkAccess.newCall(base, request)
}
