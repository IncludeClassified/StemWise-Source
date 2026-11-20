package app.stemwise.integration

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.ResponseBody

internal const val MAX_MVSEP_JSON_RESPONSE_BYTES = 2L * 1024L * 1024L
internal const val MVSEP_JSON_RESPONSE_TOO_LARGE = "MVSEP_JSON_RESPONSE_TOO_LARGE"

/** Account, job-status and algorithm-catalog JSON only; stem media uses its own streaming limit. */
internal fun ResponseBody.readBoundedMvsepJson(): String {
    val declared = contentLength()
    check(declared < 0L || declared <= MAX_MVSEP_JSON_RESPONSE_BYTES) {
        MVSEP_JSON_RESPONSE_TOO_LARGE
    }
    val output = ByteArrayOutputStream()
    byteStream().use { input ->
        val buffer = ByteArray(16 * 1024)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            copied = Math.addExact(copied, read.toLong())
            check(copied <= MAX_MVSEP_JSON_RESPONSE_BYTES) {
                MVSEP_JSON_RESPONSE_TOO_LARGE
            }
            output.write(buffer, 0, read)
        }
    }
    val charset = contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
    return output.toByteArray().toString(charset).removePrefix("\uFEFF")
}

/** HTTP options only; AppNetworkAccess supplies the exact permitted Wi-Fi sockets and DNS. */
internal object MvsepNetwork {
    val client: OkHttpClient = OkHttpClient.Builder()
        .dns(Dns.SYSTEM)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    fun isApprovedMvsepHost(host: String): Boolean {
        val normalized = host.lowercase()
        return normalized == "mvsep.com" || normalized.endsWith(".mvsep.com")
    }
}
