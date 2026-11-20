package app.stemwise.qualification

import android.os.Build

/**
 * Performance thresholds belong to the owner's physical device. Emulator scheduling remains
 * useful for functional pressure and diagnostic timing, but it cannot qualify display or audio
 * hardware latency.
 */
internal fun isAndroidEmulator(
    fingerprint: String = Build.FINGERPRINT,
    model: String = Build.MODEL,
    hardware: String = Build.HARDWARE,
    product: String = Build.PRODUCT,
    manufacturer: String = Build.MANUFACTURER
): Boolean {
    val values = listOf(fingerprint, model, hardware, product, manufacturer)
        .map { it.lowercase() }
    return values.any { value ->
        value.contains("generic") ||
            value.contains("emulator") ||
            value.contains("sdk_gphone") ||
            value.contains("google_sdk") ||
            value.contains("android sdk built for") ||
            value.contains("goldfish") ||
            value.contains("ranchu") ||
            value.contains("genymotion")
    }
}
