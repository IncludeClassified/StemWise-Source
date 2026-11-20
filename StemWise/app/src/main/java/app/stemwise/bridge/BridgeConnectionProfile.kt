package app.stemwise.bridge

import android.util.AtomicFile
import app.stemwise.data.readAtomicJournal
import java.io.File
import java.net.URI
import java.security.MessageDigest
import org.json.JSONObject

/** One exact Bridge endpoint; approved jobs retain this value independently of Settings. */
data class BridgeConnectionProfile(val host: String, val bridgeId: String, val pcId: String) {
    init {
        val addressError = "Enter a lowercase Tailscale hostname ending in .ts.net, without a URL or port."
        require(Regex("[a-z0-9][a-z0-9.-]*\\.ts\\.net").matches(host)) { addressError }
        val address = URI("https://$host")
        require(host.isNotBlank() && address.host != null && address.rawAuthority == host &&
            address.userInfo == null && address.port == -1 && address.rawPath.isNullOrEmpty() &&
            address.rawQuery == null && address.rawFragment == null) { addressError }
        val identityPattern = Regex("[A-Za-z0-9_-]{1,128}")
        require(listOf(bridgeId, pcId).all(identityPattern::matches)) {
            "Enter Bridge and PC identities from your setup using 1 to 128 letters (A-Z, a-z), digits, _ or -."
        }
    }

    internal fun json(): JSONObject = JSONObject().put("host", host).put("bridgeId", bridgeId).put("pcId", pcId)
    internal fun registrationKey(): String = MessageDigest.getInstance("SHA-256")
        .digest(app.stemwise.data.CanonicalJson.encode(json())).joinToString("") { "%02x".format(it) }

    companion object {
        internal fun parse(json: JSONObject): BridgeConnectionProfile =
            BridgeConnectionProfile(json.getString("host"), json.getString("bridgeId"), json.getString("pcId"))
    }
}

internal class BridgeConnectionStore(private val file: File) {
    @Synchronized
    fun current(): BridgeConnectionProfile? = readAtomicJournal(file, "BRIDGE_CONNECTION_UNAVAILABLE")?.value?.let {
        check(it.getInt("version") == 1) { "BRIDGE_CONNECTION_VERSION_UNSUPPORTED" }
        BridgeConnectionProfile.parse(it.getJSONObject("connection"))
    }

    @Synchronized
    fun save(profile: BridgeConnectionProfile) {
        val parent = requireNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "BRIDGE_CONNECTION_UNAVAILABLE" }
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(JSONObject().put("version", 1).put("connection", profile.json()).toString().toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
        check(current() == profile) { "BRIDGE_CONNECTION_SAVE_UNCONFIRMED" }
    }
}
