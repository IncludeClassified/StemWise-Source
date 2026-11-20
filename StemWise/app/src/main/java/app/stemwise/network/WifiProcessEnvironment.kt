package app.stemwise.network

import android.content.Context
import java.io.File

internal const val WIFI_NETWORK_HANDLE_ENV = "STEMWISE_WIFI_NETWORK_HANDLE"

internal fun wifiBindingEnvironment(networkHandle: Long, guard: File): Map<String, String> {
    check(networkHandle > 0L && guard.isFile) { "WIFI_BINDING_UNAVAILABLE" }
    return mapOf("LD_PRELOAD" to guard.absolutePath, WIFI_NETWORK_HANDLE_ENV to networkHandle.toString())
}

/** No app-global environment mutation: every owned subprocess receives its own exact route. */
internal fun AppNetworkAccess.wifiProcessEnvironment(context: Context): Map<String, String> {
    val route = wifiRoute()
    route.requireCurrent()
    return wifiBindingEnvironment(route.networkHandle,
        File(context.applicationInfo.nativeLibraryDir, "libstemwise_wifi_binding.so"))
}
