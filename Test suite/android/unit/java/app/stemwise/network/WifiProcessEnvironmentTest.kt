package app.stemwise.network

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class WifiProcessEnvironmentTest {
    @Test
    fun missingBindingLibraryOrUnspecifiedNetworkRejectsBeforeAnyChildLaunch() {
        val directory = Files.createTempDirectory("stemwise-wifi-environment").toFile()
        try {
            val guard = File(directory, "libstemwise_wifi_binding.so")
            assertEquals("WIFI_BINDING_UNAVAILABLE", assertThrows(IllegalStateException::class.java) {
                wifiBindingEnvironment(23L, guard)
            }.message)
            guard.writeBytes(byteArrayOf(1))
            for (handle in listOf(0L, -1L)) assertThrows(IllegalStateException::class.java) {
                wifiBindingEnvironment(handle, guard)
            }
            assertEquals(mapOf("LD_PRELOAD" to guard.absolutePath,
                WIFI_NETWORK_HANDLE_ENV to "23"), wifiBindingEnvironment(23L, guard))
        } finally { check(directory.deleteRecursively()) }
    }
}
