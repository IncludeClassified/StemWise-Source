package app.stemwise.integration

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import okhttp3.Callback
import okio.Timeout
import okio.buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import app.stemwise.network.AppNetworkAccess
import app.stemwise.network.AppNetworkState
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.security.KeyStoreException

class MvsepAccountContractTest {
    @Test
    fun sourcePreflightUsesTheRefreshedCredentialAndRejectsAccountChanges() = runBlocking {
        for (ready in listOf(false, true)) for (change in listOf("none", "replace", "remove", "premium")) {
            val account = MvsepAccount(TestTokenStore("synthetic-token-123"),
                profileNetwork(200, premium = change == "premium"))
            account.awaitLocalLoad()
            if (ready) account.validate().getOrThrow()
            var submissions = 0
            var attempts = 0
            var checked: MvsepCredential? = null
            val client = object : MvsepServiceClient by MvsepClient(networkAccess = profileNetwork(200)) {
                override suspend fun disablePremium(token: String) {
                    checked = checkNotNull(account.credential())
                    assertEquals(checked.token, token)
                    if (change == "replace") account.saveToken("synthetic-replacement").getOrThrow()
                    if (change == "remove") account.removeToken().getOrThrow()
                }
                override suspend fun submitConfigured(token: String, source: File, operation: MvsepSourceOperation,
                    isAuthorized: () -> Boolean, onSubmissionEntered: suspend () -> Unit,
                    onAcknowledged: suspend (String) -> Unit, onRejected: suspend (String) -> Unit): String {
                    assertTrue(isAuthorized())
                    assertEquals(account.credential()!!.token, token)
                    onSubmissionEntered()
                    submissions++
                    onAcknowledged("fixture-remote")
                    return "fixture-remote"
                }
            }
            val result = runCatching {
                val credential = checkNotNull(mvsepSourceSubmissionCredential(account, client))
                assertTrue(account.isCurrent(credential))
                assertEquals(checked!!.generation, credential.generation)
                val operation = MvsepSourceOperation("mvsep-63", 63, emptyMap(),
                    setOf("bass"), mapOf("bass" to "bass"))
                client.submitConfigured(credential.token, File("unused-upload-boundary"), operation,
                    { account.isCurrent(credential) }, { attempts++ })
                account.removeToken().getOrThrow()
                assertFalse(account.isCurrent(credential))
            }
            if (change == "none") result.getOrThrow() else assertTrue(result.isFailure)
            assertEquals(if (change == "none") 1 else 0, submissions)
            assertEquals(submissions, attempts)
        }
    }

    @Test
    fun queuedSameAccountRefreshKeepsTheUploadCredentialCurrent() = runBlocking {
        val held = HeldProfileCall()
        val ready = profileNetwork(200)
        var requests = 0
        val network = object : AppNetworkAccess by ready {
            override fun newCall(client: OkHttpClient, request: Request): Call =
                if (++requests == 2) held else ready.newCall(client, request)
        }
        val account = MvsepAccount(TestTokenStore("synthetic-token-123"), network)
        account.awaitLocalLoad()
        account.validate().getOrThrow()
        val credential = checkNotNull(account.credential())
        val refresh = async { account.validate(credential) }
        var queued: kotlinx.coroutines.Deferred<Result<Unit>>? = null
        try {
            withTimeout(3_000L) { held.enqueued.await() }
            queued = async { account.validate(credential) }
            kotlinx.coroutines.yield()
            assertTrue(account.isCurrent(credential))
            assertEquals(MvsepConnectionStatus.READY, account.state.value.status)
            assertTrue(account.state.value.verifying)
            held.deliver()
            withTimeout(3_000L) { refresh.await().getOrThrow(); queued.await().getOrThrow() }
            assertTrue(account.isCurrent(credential))
            assertFalse(account.state.value.verifying)
            account.removeToken().getOrThrow()
            assertFalse(account.isCurrent(credential))
        } finally {
            refresh.cancelAndJoin()
            queued?.cancelAndJoin()
        }
    }

    @Test
    fun profileRefreshDuringAHeldUploadPreservesChunksButReplacementAndDisconnectStopThem() = runBlocking {
        val work = generateSequence(File(checkNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "Test suite") }.first(File::isDirectory).resolve("work")
            .also { check(it.isDirectory || it.mkdirs()) }
        val source = File.createTempFile("mvsep-refresh-upload-", ".flac", work)
        source.writeBytes(ByteArray(300_000) { 17 })
        try {
            for (change in listOf("refresh", "invalid-replace", "replace", "remove")) {
                val accepted = profileNetwork(200)
                val rejected = profileNetwork(401)
                val accountNetwork = object : AppNetworkAccess by accepted {
                    override fun newCall(client: OkHttpClient, request: Request): Call =
                        (if (request.url.queryParameter("api_token") == "synthetic-invalid-replacement") rejected else accepted).newCall(client, request)
                }
                val account = MvsepAccount(TestTokenStore("synthetic-token-123"), accountNetwork)
                account.awaitLocalLoad()
                account.validate().getOrThrow()
                val credential = checkNotNull(account.credential())
                val chunkEntered = java.util.concurrent.CountDownLatch(1)
                val releaseChunk = java.util.concurrent.CountDownLatch(1)
                var bytes = 0L
                val network = object : AppNetworkAccess {
                    override val state = MutableStateFlow(AppNetworkState(true, true, false, true))
                    override fun currentState() = state.value
                    override fun newCall(client: OkHttpClient, request: Request): Call = client.newBuilder()
                        .addInterceptor { chain ->
                            val upload = chain.request().url.encodedPath.endsWith("/separation/create")
                            if (upload) {
                                val body = chain.request().body as okhttp3.MultipartBody
                                val fileBody = body.parts.last().body
                                val sink = object : okio.Sink {
                                    override fun timeout() = Timeout.NONE
                                    override fun flush() = Unit
                                    override fun close() = Unit
                                    override fun write(buffer: okio.Buffer, byteCount: Long) {
                                        buffer.skip(byteCount)
                                        bytes += byteCount
                                        if (bytes == 131_072L) {
                                            chunkEntered.countDown()
                                            check(releaseChunk.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "Upload gate timed out" }
                                        }
                                    }
                                }
                                val buffered = sink.buffer()
                                fileBody.writeTo(buffered)
                                buffered.flush()
                            }
                            val payload = if (upload) """{"success":true,"data":{"hash":"accepted-refresh"}}"""
                            else """[{"render_id":63,"name":"Fixture","audio_widget":"single_upload","orientation":0,"price_coefficient":0,"output_stems":["vocals","bass","drums","guitar","piano","other","instrum"],"outputs":{"vocals":{},"bass":{},"drums":{},"guitar":{},"piano":{},"other":{},"instrum":{}}}]"""
                            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                                .message("Fixture").body(payload.toResponseBody()).build()
                        }.build().newCall(request)
                }
                val client = MvsepClient(networkAccess = network)
                val operation = MvsepSourcePlanner.plan(listOf("bass"), client.sourceAlgorithms()).operations.single()
                val upload = async { runCatching { client.submitConfigured(credential.token, source, operation,
                    { account.isCurrent(credential) }, {}) } }
                try {
                    assertTrue(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        chunkEntered.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    })
                    when (change) {
                        "refresh" -> account.validate(credential).getOrThrow()
                        "replace" -> account.saveToken("synthetic-replacement").getOrThrow()
                        "invalid-replace" -> assertTrue(account.saveToken("synthetic-invalid-replacement").isFailure)
                        else -> account.removeToken().getOrThrow()
                    }
                    releaseChunk.countDown()
                    val result = withTimeout(5_000L) { upload.await() }
                    if (change in setOf("refresh", "invalid-replace")) {
                        assertEquals("accepted-refresh", result.getOrThrow())
                        assertEquals(source.length(), bytes)
                    } else {
                        assertTrue(result.isFailure)
                        assertTrue(bytes < source.length())
                    }
                } finally {
                    releaseChunk.countDown()
                    upload.cancelAndJoin()
                }
            }
        } finally { check(source.delete()) }
    }

    @Test
    fun disconnectRetiresHeldValidationWithoutAResponseOrRegionalFallback() = runBlocking {
        val held = HeldProfileCall()
        var requests = 0
        val network = object : AppNetworkAccess {
            override val state = MutableStateFlow(AppNetworkState(true, true, false, activeNetworkWifi = true))
            override fun currentState() = state.value
            override fun newCall(client: OkHttpClient, request: Request): Call {
                requests++
                return held
            }
        }
        val store = TestTokenStore("synthetic-token-123")
        val account = MvsepAccount(store, network)
        account.awaitLocalLoad()
        val validation = launch { account.validate() }
        var removal: kotlinx.coroutines.Deferred<Result<Unit>>? = null
        try {
            withTimeout(3_000) { held.enqueued.await() }
            removal = async { account.removeToken() }
            assertTrue(withTimeout(3_000) { removal.await() }.isSuccess)
            withTimeout(3_000) { validation.join() }
            assertTrue(held.isCanceled())
            assertEquals(1, requests)
            assertEquals(null, store.value)
            // A callback racing cancellation cannot restore the removed account.
            held.deliver()
            assertEquals(MvsepConnectionStatus.NOT_CONFIGURED, account.state.value.status)
            assertEquals(null, account.credential())
        } finally {
            removal?.cancelAndJoin()
            validation.cancelAndJoin()
        }
    }

    @Test
    fun failedOrCancelledDisconnectPreservesStorageAndDoesNotCancelPreparation() = runBlocking {
        for (cancelRemoval in listOf(false, true)) {
            val held = HeldProfileCall()
            val network = object : AppNetworkAccess {
                override val state = MutableStateFlow(AppNetworkState(true, true, false, activeNetworkWifi = true))
                override fun currentState() = state.value
                override fun newCall(client: OkHttpClient, request: Request): Call = held
            }
            val store = TestTokenStore("synthetic-token-123")
            if (!cancelRemoval) store.clearFailure = KeyStoreException("clear unavailable")
            val account = MvsepAccount(store, network)
            account.awaitLocalLoad()
            val validation = launch { account.validate() }
            var cancellationRequests = 0
            try {
                withTimeout(3_000) { held.enqueued.await() }
                val removal = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    app.stemwise.ui.removeMvsepConnection(account::removeToken) { cancellationRequests++ }
                }
                if (cancelRemoval) removal.cancel()
                withTimeout(3_000) { removal.join(); validation.join() }
                if (!cancelRemoval) assertTrue(removal.await().isFailure)
                assertEquals("synthetic-token-123", store.value)
                assertTrue(account.state.value.tokenStored)
                assertEquals(0, cancellationRequests)
                store.clearFailure = null
                assertTrue(app.stemwise.ui.removeMvsepConnection(account::removeToken) { cancellationRequests++ }.isSuccess)
                assertEquals(1, cancellationRequests)
                assertEquals(null, store.value)
            } finally {
                validation.cancelAndJoin()
            }
        }
    }

    private class HeldProfileCall : Call {
        val enqueued = CompletableDeferred<Unit>()
        private var callback: Callback? = null
        private var cancelled = false
        override fun request() = Request.Builder().url("https://mvsep.com/fixture").build()
        override fun execute(): Response = error("No synchronous network")
        override fun enqueue(responseCallback: Callback) {
            callback = responseCallback
            enqueued.complete(Unit)
        }
        override fun cancel() { cancelled = true }
        override fun isCanceled() = cancelled
        override fun isExecuted() = callback != null
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = HeldProfileCall()
        fun deliver() {
            callback!!.onResponse(this, Response.Builder().request(request())
                .protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body("""{"success":true,"data":{"premium_enabled":0}}""".toResponseBody()).build())
        }
    }

    @Test
    fun savedOfflineTokenCanBeRemovedLocallyAndFailedClearRetainsIt() = runBlocking {
        val network = object : AppNetworkAccess {
            override val state = MutableStateFlow(AppNetworkState(false, false, false))
            override fun currentState() = state.value
            override fun newCall(client: OkHttpClient, request: Request): Call = error("No network for local removal")
        }
        val store = TestTokenStore("synthetic-offline-token")
        val account = MvsepAccount(store, network)
        account.awaitLocalLoad()
        assertTrue(account.state.value.tokenStored)
        assertEquals(MvsepConnectionStatus.SAVED_UNVERIFIED, account.state.value.status)
        store.clearFailure = KeyStoreException("clear unavailable")
        assertTrue(account.removeToken().isFailure)
        assertTrue(account.state.value.tokenStored)
        store.clearFailure = null
        assertTrue(account.removeToken().isSuccess)
        assertFalse(account.state.value.tokenStored)
        assertEquals(null, store.value)
    }
    @Test
    fun firstTokenSaveFailureReturnsFailureAndLeavesChecking() = runBlocking {
        val store = TestTokenStore().apply { saveFailure = KeyStoreException("storage unavailable") }
        val account = MvsepAccount(store, profileNetwork(200))
        account.awaitLocalLoad()
        val result = account.saveToken("synthetic-token-123")
        assertTrue(result.exceptionOrNull() is KeyStoreException)
        assertEquals(MvsepConnectionStatus.ERROR, account.state.value.status)
        assertFalse(account.state.value.tokenStored)
        assertEquals(null, store.value)
        assertEquals(null, account.credential())
    }

    @Test
    fun rejectedTokenClearFailureReturnsFailureAndPreservesStoredTokenWithoutAuthority() = runBlocking {
        val store = TestTokenStore("synthetic-token-123").apply {
            clearFailure = IllegalStateException("MVSEP_TOKEN_STORE_COMMIT_FAILED")
        }
        val account = MvsepAccount(store, profileNetwork(401))
        account.awaitLocalLoad()
        val result = account.validate()
        assertEquals("MVSEP_TOKEN_STORE_COMMIT_FAILED", result.exceptionOrNull()?.message)
        assertEquals(MvsepConnectionStatus.ERROR, account.state.value.status)
        assertTrue(account.state.value.tokenStored)
        assertEquals("synthetic-token-123", store.value)
        assertEquals(null, account.credential())
    }

    @Test
    fun rejectedTokenSuccessfulClearReleasesStorageAndReportsRejection() = runBlocking {
        val store = TestTokenStore("synthetic-token-123")
        val account = MvsepAccount(store, profileNetwork(401))
        account.awaitLocalLoad()
        assertTrue(account.validate().isFailure)
        assertEquals(MvsepConnectionStatus.ERROR, account.state.value.status)
        assertFalse(account.state.value.tokenStored)
        assertEquals(null, store.value)
    }

    @Test
    fun replacementTokenSaveFailurePreservesThePreviouslyVerifiedAccount() = runBlocking {
        val store = TestTokenStore("synthetic-token-123")
        val account = MvsepAccount(store, profileNetwork(200))
        account.awaitLocalLoad()
        assertTrue(account.validate().isSuccess)
        store.saveFailure = KeyStoreException("storage unavailable")
        assertTrue(account.saveToken("synthetic-replacement").isFailure)
        assertEquals(MvsepConnectionStatus.READY, account.state.value.status)
        assertEquals("synthetic-token-123", store.value)
        assertEquals("synthetic-token-123", account.credential()?.token)
    }

    private class TestTokenStore(var value: String? = null) : MvsepTokenStore {
        var saveFailure: Throwable? = null
        var clearFailure: Throwable? = null
        override fun read(): String? = value
        override fun save(token: String) {
            saveFailure?.let { throw it }
            value = token
        }
        override fun clear() {
            clearFailure?.let { throw it }
            value = null
        }
    }

    @Test
    fun receivedConfiguredAcknowledgementSurvivesTokenRevocationAndOwnerCancellation() = runBlocking {
        val roles = listOf("vocals", "bass", "drums", "guitar", "piano", "other", "instrum")
        val catalogue = org.json.JSONArray().put(JSONObject().put("render_id", 63)
            .put("name", "Fixture").put("audio_widget", "single_upload").put("orientation", 0)
            .put("price_coefficient", 0).put("output_stems", org.json.JSONArray(roles))
            .put("outputs", JSONObject().apply { roles.forEach { put(it, JSONObject()) } })).toString()
        val authorized = java.util.concurrent.atomic.AtomicBoolean(true)
        val acknowledged = CompletableDeferred<String>()
        val releasePersistence = CompletableDeferred<Unit>()
        val durable = java.util.concurrent.atomic.AtomicReference<String?>()
        val network = object : AppNetworkAccess {
            override val state = MutableStateFlow(AppNetworkState(true, true, false, true))
            override fun currentState() = state.value
            override fun newCall(client: OkHttpClient, request: Request): Call = client.newBuilder()
                .addInterceptor { chain ->
                    val isSubmit = chain.request().url.encodedPath.endsWith("/separation/create")
                    if (isSubmit) authorized.set(false)
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                        .message("Fixture").body((if (isSubmit)
                            """{"success":true,"data":{"hash":"exact-acknowledged-id"}}""" else catalogue).toResponseBody()).build()
                }.build().newCall(request)
        }
        val root = generateSequence(File(checkNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "AGENTS.md").isFile }
        val work = File(root, "Test suite/work").also { check(it.isDirectory || it.mkdirs()) }
        val source = File.createTempFile("mvsep-ack-", ".flac", work).also { it.writeBytes(byteArrayOf(1)) }
        val client = MvsepClient(networkAccess = network)
        val operation = MvsepSourcePlanner.plan(listOf("vocals"), client.sourceAlgorithms()).operations.single()
        val owner = launch {
            client.submitConfigured("synthetic-token", source, operation, authorized::get, {},
                onAcknowledged = { id ->
                    acknowledged.complete(id)
                    releasePersistence.await()
                    durable.set(id)
                })
        }
        try {
            assertEquals("exact-acknowledged-id", withTimeout(5_000L) { acknowledged.await() })
            assertFalse(authorized.get())
            owner.cancel()
            kotlinx.coroutines.yield()
            assertFalse("Receipt consumer retired before durability", owner.isCompleted)
            releasePersistence.complete(Unit)
            withTimeout(5_000L) { owner.join() }
            assertEquals("exact-acknowledged-id", durable.get())
        } finally {
            releasePersistence.complete(Unit)
            owner.cancelAndJoin()
            check(source.delete())
        }
    }

    private fun profileNetwork(statusCode: Int, premium: Boolean = false): AppNetworkAccess = object : AppNetworkAccess {
        override val state = MutableStateFlow(AppNetworkState(
            hasActiveNetwork = true, activeNetworkValidated = true, activeNetworkMetered = false, activeNetworkWifi = true))
        override fun currentState() = state.value
        override fun newCall(client: OkHttpClient, request: Request): Call = client.newBuilder()
            .addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(statusCode).message("Synthetic profile")
                    .body("""{"success":true,"data":{"premium_enabled":${if (premium) 1 else 0},"name":"Fixture"}}""".toResponseBody())
                    .build()
            }.build().newCall(request)
    }

    @Test
    fun `profile request uses the documented api token parameter`() {
        val url = mvsepUserUrl("https://de2.mvsep.com/api", "a+b/c=")

        assertEquals("/api/app/user", url.path)
        assertEquals("api_token=a%2Bb%2Fc%3D", url.query)
    }

    @Test
    fun `the phone uses one explicit reachable MVSEP region for the full job lifecycle`() {
        assertEquals("https://de.mvsep.com/api", MvsepAccount.API_BASE)
        assertEquals(
            listOf(
                "https://de.mvsep.com/api",
                "https://hk.mvsep.com/api",
                "https://de2.mvsep.com/api",
                "https://mvsep.com/api"
            ),
            MvsepAccount.API_BASES
        )
    }

    @Test
    fun `numeric zero is free and numeric one is premium`() {
        assertFalse(parseMvsepPremiumEnabled(JSONObject("""{"premium_enabled":0}""")))
        assertTrue(parseMvsepPremiumEnabled(JSONObject("""{"premium_enabled":1}""")))
    }

    @Test
    fun `missing boolean string and unknown premium states fail closed`() {
        listOf(
            """{}""",
            """{"premium_enabled":false}""",
            """{"premium_enabled":"0"}""",
            """{"premium_enabled":2}""",
            """{"premium_enabled":0.5}""",
            """{"premium_enabled":1.5}""",
            """{"premium_enabled":4294967296}""",
            """{"premium_enabled":4294967297}"""
        ).forEach { payload ->
            assertThrows(IllegalStateException::class.java) {
                parseMvsepPremiumEnabled(JSONObject(payload))
            }
        }
    }

    @Test
    fun `cold startup exposes a stored token without claiming remote verification`() {
        val state = mvsepLocallyLoadedState("stored-token-value")

        assertEquals(MvsepConnectionStatus.SAVED_UNVERIFIED, state.status)
        assertTrue(state.tokenStored)
        assertEquals(null, state.accountName)
        assertEquals(null, state.apiBase)
    }

    @Test
    fun `cold startup without a stored token stays not configured`() {
        val state = mvsepLocallyLoadedState(null)

        assertEquals(MvsepConnectionStatus.NOT_CONFIGURED, state.status)
        assertFalse(state.tokenStored)
    }

    @Test
    fun `local token storage failure is fail closed and always releases startup`() = runBlocking {
        for (failure in listOf(IllegalStateException("storage unavailable"), CancellationException("load cancelled"))) {
            var reads = 0
            val storage = object : MvsepTokenStore {
                override fun read(): String? { reads++; throw failure }
                override fun save(token: String) = error("Startup must not mutate token storage")
                override fun clear() = error("Startup must not clear token storage")
            }
            val account = MvsepAccount(storage, profileNetwork(200))
            withTimeout(3_000L) { account.awaitLocalLoad(); account.awaitLocalLoad() }
            assertEquals(1, reads)
            assertEquals(if (failure is CancellationException) MvsepConnectionStatus.NOT_CONFIGURED
                else MvsepConnectionStatus.ERROR, account.state.value.status)
            assertFalse(account.state.value.tokenStored)
            assertEquals(null, account.credential())
            if (failure !is CancellationException) assertEquals(
                "Could not load the saved MVSEP connection", account.state.value.error)
        }
    }

    @Test fun localLoadFailureCanReloadWithoutMutationOrRemoteVerification() = runBlocking {
        var reads = 0
        var requests = 0
        val network = object : AppNetworkAccess by profileNetwork(200) {
            override fun newCall(client: OkHttpClient, request: Request): Call {
                requests++
                return profileNetwork(200).newCall(client, request)
            }
        }
        val store = object : MvsepTokenStore {
            override fun read(): String? = if (++reads == 1) error("storage unavailable") else "synthetic-token-123"
            override fun save(token: String) = error("Reload must not save")
            override fun clear() = error("Reload must not clear")
        }
        val account = MvsepAccount(store, network)
        withTimeout(3_000L) { account.awaitLocalLoad() }
        assertTrue(account.state.value.localLoadFailed)
        assertTrue(account.validate().isFailure)
        assertTrue(account.state.value.localLoadFailed)
        account.reloadSavedToken().getOrThrow()
        assertEquals(2, reads)
        assertEquals(0, requests)
        assertEquals(MvsepConnectionStatus.SAVED_UNVERIFIED, account.state.value.status)
        assertTrue(account.state.value.tokenStored)
        assertFalse(account.state.value.localLoadFailed)
        account.validate().getOrThrow()
        assertEquals(MvsepConnectionStatus.READY, account.state.value.status)
        assertTrue(requests > 0)
    }

    @Test fun heldLocalReloadCannotRestoreAConcurrentDisconnectOrReplaceItsToken() = runBlocking {
        for (remove in listOf(true, false)) {
            var reads = 0
            var saved: String? = "synthetic-token-123"
            val entered = CompletableDeferred<Unit>()
            val release = java.util.concurrent.CountDownLatch(1)
            val store = object : MvsepTokenStore {
                override fun read(): String? {
                    if (++reads == 1) error("storage unavailable")
                    val previous = saved
                    entered.complete(Unit)
                    check(release.await(3, java.util.concurrent.TimeUnit.SECONDS)) { "reload hold expired" }
                    return previous
                }
                override fun save(token: String) { saved = token }
                override fun clear() { saved = null }
            }
            val account = MvsepAccount(store, profileNetwork(200))
            withTimeout(3_000L) { account.awaitLocalLoad() }
            val reload = async { account.reloadSavedToken() }
            var mutation: kotlinx.coroutines.Deferred<Result<Unit>>? = null
            try {
                withTimeout(3_000L) { entered.await() }
                mutation = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    if (remove) account.removeToken() else account.saveToken("synthetic-replacement")
                }
                release.countDown()
                withTimeout(3_000L) { reload.await(); mutation.await().getOrThrow() }
                assertEquals(if (remove) null else "synthetic-replacement", saved)
                assertEquals(if (remove) null else "synthetic-replacement", account.credential()?.token)
                assertEquals(if (remove) MvsepConnectionStatus.NOT_CONFIGURED else MvsepConnectionStatus.READY,
                    account.state.value.status)
            } finally {
                release.countDown()
                reload.cancelAndJoin()
                mutation?.cancelAndJoin()
            }
        }
    }

    @Test
    fun `cancelled profile verification never falls through to another region`() {
        val attemptedRegions = mutableListOf<String>()

        assertThrows(CancellationException::class.java) {
            runBlocking {
                validateAcrossMvsepRegions(listOf("region-1", "region-2")) { region ->
                    attemptedRegions += region
                    throw CancellationException("worker constraints were lost")
                }
            }
        }

        assertEquals(listOf("region-1"), attemptedRegions)
    }

    @Test
    fun `token mutation publishes completion only after durable commit`() = runBlocking {
        val events = mutableListOf<String>()

        completeMvsepTokenMutation(
            mutateDurably = { events += "committed" },
            publishLive = { events += "published" }
        )

        assertEquals(listOf("committed", "published"), events)
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                completeMvsepTokenMutation(
                    mutateDurably = { error("disk unavailable") },
                    publishLive = { events += "unexpected" }
                )
            }
        }
        assertFalse("unexpected" in events)
    }

    @Test
    fun `cancellation after durable commit cannot suppress live publication`() = runBlocking {
        val committed = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        var livePublished = false
        val owner = launch {
            completeMvsepTokenMutation(
                mutateDurably = {
                    committed.complete(Unit)
                    releaseCommit.await()
                },
                publishLive = { livePublished = true }
            )
        }

        try {
            withTimeout(5_000L) { committed.await() }
            owner.cancel()
            releaseCommit.complete(Unit)
            withTimeout(5_000L) { owner.join() }
        } finally {
            releaseCommit.complete(Unit)
            owner.cancelAndJoin()
        }

        assertTrue(livePublished)
    }

    @Test
    fun `token preference mutations reject failed durable commits`() {
        requireMvsepTokenPreferenceCommit(true)
        assertThrows(IllegalStateException::class.java) {
            requireMvsepTokenPreferenceCommit(false)
        }
    }

    private fun projectRoot(): File {
        val working = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(working) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory }
            ?: error("Could not locate StemWise project from ${working.absolutePath}")
    }
}
