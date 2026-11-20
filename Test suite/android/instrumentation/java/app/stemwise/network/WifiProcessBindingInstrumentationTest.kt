package app.stemwise.network

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.stemwise.StemwiseApplication
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WifiProcessBindingInstrumentationTest {
    @Test
    fun invalidBindingsStopExecutableEntryInsideTheChild() {
        val context = ApplicationProvider.getApplicationContext<StemwiseApplication>()
        val guard = File(context.applicationInfo.nativeLibraryDir, "libstemwise_wifi_binding.so")
        assertTrue("Wi-Fi child guard was not packaged", guard.isFile)
        for (value in listOf(null, "", "0", "-1", "1", "18446744073709551616")) {
            val builder = ProcessBuilder("/system/bin/sh", "-c", "printf CHILD_ENTRY_REACHED")
                .redirectErrorStream(true)
            builder.environment().apply {
                put("LD_PRELOAD", guard.absolutePath)
                remove(WIFI_NETWORK_HANDLE_ENV)
                value?.let { put(WIFI_NETWORK_HANDLE_ENV, it) }
            }
            val process = builder.start()
            try {
                assertTrue("Wi-Fi binding rejection hung", process.waitFor(5L, TimeUnit.SECONDS))
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals(125, process.exitValue())
                assertTrue(output.contains("WIFI_BINDING_UNAVAILABLE"))
                assertFalse(output.contains("CHILD_ENTRY_REACHED"))
            } finally { if (process.isAlive) process.destroyForcibly() }
        }
    }

    @Test
    fun anExactAllowedWifiBindingPermitsChildAndDescendantEntry() {
        val context = ApplicationProvider.getApplicationContext<StemwiseApplication>()
        val access = context.networkAccess
        assumeTrue("Requires a validated unmetered Wi-Fi connection", AppOwnedNetworkPolicy.allowsNetwork(access.currentState()))
        val builder = ProcessBuilder("/system/bin/sh", "-c",
            "/system/bin/sh -c 'printf DESCENDANT_REACHED'; printf PARENT_REACHED")
            .redirectErrorStream(true)
        builder.environment().putAll(access.wifiProcessEnvironment(context))
        val process = builder.start()
        try {
            assertTrue("Wi-Fi bound child hung", process.waitFor(5L, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 0, process.exitValue())
            assertEquals("DESCENDANT_REACHEDPARENT_REACHED", output)
        } finally { if (process.isAlive) process.destroyForcibly() }
    }
}
