package app.stemwise.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.IOException
import java.util.WeakHashMap
import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope

internal const val WAITING_FOR_UNMETERED_NETWORK = "Waiting for unmetered Wi-Fi"
internal data class AppNetworkState(
    val hasActiveNetwork: Boolean,
    val activeNetworkValidated: Boolean,
    val activeNetworkMetered: Boolean,
    val activeNetworkWifi: Boolean = false,
    val activeNetworkCellular: Boolean = false,
    val activeNetworkVpn: Boolean = false,
    val activeNetworkHandle: Long? = null
)

/** Pure decision used by every in-process network owner, including foreground UI actions. */
internal object AppOwnedNetworkPolicy {
    fun allowsNetwork(state: AppNetworkState): Boolean =
        state.hasActiveNetwork &&
            state.activeNetworkValidated &&
            !state.activeNetworkMetered &&
            state.activeNetworkWifi &&
            !state.activeNetworkCellular &&
            !state.activeNetworkVpn

}

internal class UnmeteredNetworkRequiredException(
    val operationStarted: Boolean = false
) : IOException(WAITING_FOR_UNMETERED_NETWORK)

internal interface AppNetworkAccess {
    val state: StateFlow<AppNetworkState>

    fun currentState(): AppNetworkState

    /** No default/unbound implementation: platform substitutes must supply explicit routing. */
    fun wifiRoute(): WifiNetworkRoute = throw UnmeteredNetworkRequiredException()

    fun newCall(client: OkHttpClient, request: Request): Call =
        throw UnmeteredNetworkRequiredException()

    fun requireUnmetered() {
        if (!AppOwnedNetworkPolicy.allowsNetwork(currentState())) {
            throw UnmeteredNetworkRequiredException()
        }
    }
}

/**
 * Runs one exact network operation only while policy permits it. If policy changes, structured
 * cancellation reaches the operation's exact OkHttp call or yt-dlp process before this returns.
 */
internal suspend fun <T> AppNetworkAccess.whileUnmetered(block: suspend () -> T): T {
    requireUnmetered()
    val admittedHandle = currentState().activeNetworkHandle
    return supervisorScope {
        val operation = async { block() }
        val restriction = async {
            state.first { snapshot ->
                !AppOwnedNetworkPolicy.allowsNetwork(snapshot) ||
                    snapshot.activeNetworkHandle != admittedHandle
            }
        }
        try {
            select {
                operation.onAwait { value -> value }
                restriction.onAwait {
                    operation.cancel(
                        CancellationException(WAITING_FOR_UNMETERED_NETWORK)
                    )
                    try {
                        operation.await()
                    } catch (_: CancellationException) {
                        // Only the expected policy cancellation may become a network wait.
                    }
                    throw UnmeteredNetworkRequiredException(operationStarted = true)
                }
            }
        } finally {
            operation.cancel()
            restriction.cancel()
        }
    }
}

internal class AndroidAppNetworkAccess(context: Context) : AppNetworkAccess {
    private val applicationContext = context.applicationContext
    private val connectivity = applicationContext
        .getSystemService(ConnectivityManager::class.java)
    private val mutableState = MutableStateFlow(readCurrentState())
    override val state: StateFlow<AppNetworkState> = mutableState
    private val boundClients = WeakHashMap<OkHttpClient, Pair<Long, OkHttpClient>>()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) = refresh()
    }
    init { connectivity.registerDefaultNetworkCallback(networkCallback) }

    override fun currentState(): AppNetworkState = readCurrentState().also { mutableState.value = it }

    override fun wifiRoute(): WifiNetworkRoute {
        val network = connectivity.activeNetwork ?: throw UnmeteredNetworkRequiredException()
        val captured = currentState()
        if (!AppOwnedNetworkPolicy.allowsNetwork(captured) || !captured.activeNetworkWifi ||
            captured.activeNetworkCellular || captured.activeNetworkVpn ||
            captured.activeNetworkHandle != network.networkHandle) throw UnmeteredNetworkRequiredException()
        return WifiNetworkRoute(network.networkHandle, network.socketFactory,
            object : Dns {
                override fun lookup(hostname: String) = network.getAllByName(hostname).toList()
            }) {
            val current = currentState()
            if (current.activeNetworkHandle != network.networkHandle ||
                !AppOwnedNetworkPolicy.allowsNetwork(current) || !current.activeNetworkWifi ||
                current.activeNetworkCellular || current.activeNetworkVpn) {
                throw UnmeteredNetworkRequiredException(operationStarted = true)
            }
        }
    }

    override fun newCall(client: OkHttpClient, request: Request): Call {
        val route = wifiRoute()
        val bound = synchronized(boundClients) {
            val prior = boundClients[client]
            if (prior?.first == route.networkHandle) prior.second else {
                prior?.second?.connectionPool?.evictAll()
                boundWifiHttpClient(client, route).also { boundClients[client] = route.networkHandle to it }
            }
        }
        return bound.newCall(request)
    }

    private fun refresh() {
        mutableState.value = readCurrentState()
    }

    private fun readCurrentState(): AppNetworkState {
        val activeNetwork = connectivity.activeNetwork
        val capabilities = activeNetwork?.let(connectivity::getNetworkCapabilities)
        val explicitlyUnmetered = capabilities?.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_NOT_METERED
        ) == true
        return AppNetworkState(
            hasActiveNetwork = activeNetwork != null,
            activeNetworkValidated = capabilities?.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED
            ) == true,
            // Require Android's explicit unmetered capability and its active-network classifier
            // to agree. A capability transition or unknown network therefore fails closed.
            activeNetworkMetered = !explicitlyUnmetered || connectivity.isActiveNetworkMetered,
            activeNetworkWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
            activeNetworkCellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
            activeNetworkVpn = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
            activeNetworkHandle = activeNetwork?.networkHandle
        )
    }
}
