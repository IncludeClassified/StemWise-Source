package app.stemwise.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BridgeWireInstrumentationTest {
    private val profile = BridgeConnectionProfile("bridge.tailnet.ts.net", "fixture-pi", "fixture-pc")
    @Test fun connectionStorePreservesPriorProfileOnWriteFailureAndPairsTwoEndpoints() {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(context.cacheDir, "bridge-profile-${java.util.UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            val file = java.io.File(directory, "connection.json")
            val store = BridgeConnectionStore(file)
            assertEquals(null, store.current())
            store.save(profile)
            assertEquals(profile, BridgeConnectionStore(file).current())
            val replacement = BridgeConnectionProfile("other.tailnet.ts.net", "other-pi", "other-pc")
            val bytes = file.readBytes()
            val blocked = java.io.File(directory, "connection.json.new").apply { check(mkdir()) }
            assertThrows(Exception::class.java) { store.save(replacement) }
            org.junit.Assert.assertArrayEquals(bytes, file.readBytes())
            if (blocked.exists()) check(blocked.delete())
            assertEquals(profile, BridgeConnectionStore(file).current())
            store.save(replacement)
            assertEquals(replacement, BridgeConnectionStore(file).current())
            val response = envelope(200, JSONObject().put("state", "reachable"))
            assertThrows(IllegalStateException::class.java) { bridgeAppResponse(response.toString(), replacement) }
            response.getJSONObject("response").put("bridgeIdentity", replacement.bridgeId).put("pcIdentity", replacement.pcId)
            assertEquals("reachable", bridgeAppResponse(response.toString(), replacement).getString("state"))
        } finally { check(directory.deleteRecursively()) }
    }

    @Test fun acceptsOnlyThePairedAppEnvelopeAndPreservesStructuredFailure() {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val success = envelope(200, JSONObject().put("state", "reachable"))
        assertEquals("reachable", bridgeAppResponse(success.toString(), profile).getString("state"))
        success.getJSONObject("response").put("pcIdentity", "another-pc")
        assertThrows(IllegalStateException::class.java) { bridgeAppResponse(success.toString(), profile) }

        val message = "This request already exists with different input. " + "detail ".repeat(100)
        val rejected = envelope(409, JSONObject().put("code", "request_conflict")
            .put("message", message).put("retryable", false))
        val error = assertThrows(BridgeApiFailure::class.java) { bridgeAppResponse(rejected.toString(), profile) }
        assertEquals(409, error.status)
        assertEquals("request_conflict", error.code)
        assertEquals(message, error.message)
        assertFalse(error.retryable)
    }

    @Test fun duplicateWireFieldsCannotOverrideTheAuthoritativeIdentity() {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val wire = envelope(200, JSONObject()).toString().replace(
            "\"pcIdentity\":\"${profile.pcId}\"",
            "\"pcIdentity\":\"another-pc\",\"pcIdentity\":\"${profile.pcId}\"")
        assertThrows(Exception::class.java) { bridgeAppResponse(wire, profile) }
    }

    @Test fun storedGuideReceiptMustMatchTheImmutableAppDescriptor() {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val expected = BridgeFile("a".repeat(32), "SEND_TO_AGENTS.md", 123L, "b".repeat(64))
        fun receipt(file: BridgeFile = expected, state: String = "stored") =
            JSONObject().put("state", state).put("file", file.json()).toString()
        requireBridgeInputReceipt(receipt(), expected)
        for (changed in listOf(expected.copy(id = "c".repeat(32)), expected.copy(name = "other.md"),
            expected.copy(size = 124L), expected.copy(sha256 = "c".repeat(64)))) {
            assertThrows(IllegalStateException::class.java) { requireBridgeInputReceipt(receipt(changed), expected) }
        }
        assertThrows(IllegalStateException::class.java) { requireBridgeInputReceipt(receipt(state = "absent"), expected) }
    }

    private fun envelope(status: Int, data: JSONObject) = JSONObject().put("httpStatus", status)
        .put("response", JSONObject().put("protocol", "phone-pc-bridge-app-v1")
            .put("bridgeIdentity", profile.bridgeId).put("pcIdentity", profile.pcId).put("data", data))
}
