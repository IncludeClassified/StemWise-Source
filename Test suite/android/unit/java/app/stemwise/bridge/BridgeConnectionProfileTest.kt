package app.stemwise.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BridgeConnectionProfileTest {
    @Test fun profileRejectsHostsTheSdkCannotUseBeforePairing() {
        for (host in listOf("127.0.0.1", "[::1]", "bridge.example", "Bridge.tailnet.ts.net", "bridge.tailnet.TS.NET")) {
            val error = assertThrows(IllegalArgumentException::class.java) { BridgeConnectionProfile(host, "pi", "pc") }
            assertEquals("Enter a lowercase Tailscale hostname ending in .ts.net, without a URL or port.", error.message)
        }
    }

    @Test fun profileRequiresExactSdkNodeIdentities() {
        val host = "bridge.tailnet.ts.net"
        for (identity in listOf("_", "-", "Node_A-1", "A".repeat(128))) {
            for (ids in listOf(identity to "pc", "pi" to identity)) {
                val profile = BridgeConnectionProfile(host, ids.first, ids.second)
                assertEquals(profile, BridgeConnectionProfile.parse(profile.json()))
            }
        }
        for (identity in listOf("", "node.id", "node/id", "node:1", "node@peer", "nœud", "pi id", "pc\n", "A".repeat(129))) {
            for (ids in listOf(identity to "pc", "pi" to identity)) {
                val error = assertThrows(IllegalArgumentException::class.java) { BridgeConnectionProfile(host, ids.first, ids.second) }
                assertEquals("Enter Bridge and PC identities from your setup using 1 to 128 letters (A-Z, a-z), digits, _ or -.", error.message)
            }
        }
    }

    @Test fun profileRoundTripsAndRejectsAmbiguousAddressesAndMissingIdentities() {
        val profile = BridgeConnectionProfile("bridge.tailnet.ts.net", "peer-pi", "peer-pc")
        assertEquals(profile, BridgeConnectionProfile.parse(profile.json()))
        for (host in listOf("", " bridge.tailnet.ts.net", "https://bridge.tailnet.ts.net", "bridge.tailnet.ts.net/path",
            "user@bridge.tailnet.ts.net", "bridge.tailnet.ts.net:80", "bridge.tailnet.ts.net?query", "bridge.tailnet.ts.net#fragment")) {
            assertThrows(Exception::class.java) { BridgeConnectionProfile(host, "pi", "pc") }
        }
        for (ids in listOf("" to "pc", "pi" to "", "pi id" to "pc")) {
            assertThrows(IllegalArgumentException::class.java) { BridgeConnectionProfile("bridge.tailnet.ts.net", ids.first, ids.second) }
        }
    }
}
