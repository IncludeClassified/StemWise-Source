package app.stemwise.integration

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import okhttp3.Request
import app.stemwise.network.AppNetworkAccess
import app.stemwise.network.AppOwnedNetworkPolicy
import app.stemwise.network.UnmeteredNetworkRequiredException
import app.stemwise.network.WAITING_FOR_UNMETERED_NETWORK
import app.stemwise.network.whileUnmetered

enum class MvsepConnectionStatus {
    NOT_CONFIGURED,
    CHECKING,
    SAVED_UNVERIFIED,
    READY,
    ERROR
}

data class MvsepConnectionState(
    val status: MvsepConnectionStatus,
    val accountName: String? = null,
    val premiumMinutes: Double? = null,
    val premiumEnabled: Boolean = false,
    val error: String? = null,
    val tokenStored: Boolean = false,
    val apiBase: String? = null,
    val waitingForUnmeteredNetwork: Boolean = false,
    val verifying: Boolean = false,
    val localLoadFailed: Boolean = false
)

/** Narrow authenticated-session seam used by the durable separation owner and its fakes. */
internal interface MvsepAccountAccess {
    val state: StateFlow<MvsepConnectionState>
    suspend fun validate(expectedCredential: MvsepCredential? = null): Result<Unit>
    fun credential(): MvsepCredential?
    fun isCurrent(credential: MvsepCredential): Boolean
    fun apiBase(): String
}

class MvsepCredential internal constructor(
    val token: String,
    internal val generation: Long
)

internal fun parseMvsepPremiumEnabled(data: JSONObject): Boolean =
    when (val raw = data.opt("premium_enabled")) {
        is Byte, is Short, is Int, is Long -> when ((raw as Number).toLong()) {
            0L -> false
            1L -> true
            else -> error("MVSEP returned an unknown premium setting")
        }
        else -> error("MVSEP did not return a valid premium setting")
    }

internal interface MvsepTokenStore {
    fun read(): String?
    fun save(token: String)
    fun clear()
}

class MvsepAccount internal constructor(
    private val tokenStore: MvsepTokenStore,
    private val networkAccess: AppNetworkAccess
) : MvsepAccountAccess {
    internal constructor(context: Context, networkAccess: AppNetworkAccess) :
        this(SecureTokenStore(context.applicationContext), networkAccess)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val accountOperationMutex = Mutex()
    private val validationMutex = Mutex()
    private val tokenStoreMutex = Mutex()
    private val generation = AtomicLong(0L)
    private val stateLock = Any()
    private var activeValidation: Deferred<MvsepConnectionState>? = null
    private var pendingRemovals = 0
    @Volatile private var cachedToken: String? = null
    private val mutableState = MutableStateFlow(
        MvsepConnectionState(status = MvsepConnectionStatus.NOT_CONFIGURED)
    )
    override val state: StateFlow<MvsepConnectionState> = mutableState.asStateFlow()
    private val tokenLoadReady = CompletableDeferred<Unit>()

    init {
        val operation = generation.incrementAndGet()
        scope.launch {
            try {
                val token = tokenStoreMutex.withLock { tokenStore.read() }
                synchronized(stateLock) {
                    if (generation.get() == operation) {
                        cachedToken = token
                        mutableState.value = mvsepLocallyLoadedState(token)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                synchronized(stateLock) {
                    if (generation.get() == operation) {
                        cachedToken = null
                        mutableState.value = mvsepLocalLoadFailureState()
                    }
                }
            } finally {
                tokenLoadReady.complete(Unit)
            }
        }
    }

    internal suspend fun awaitLocalLoad() = tokenLoadReady.await()

    suspend fun reloadSavedToken(): Result<Unit> = accountOperationMutex.withLock {
        tokenLoadReady.await()
        val operation = synchronized(stateLock) {
            if (!mutableState.value.localLoadFailed || pendingRemovals > 0) {
                return@withLock Result.failure(IllegalStateException("MVSEP connection changed"))
            }
            generation.incrementAndGet()
        }
        try {
            val token = withContext(Dispatchers.IO) { tokenStoreMutex.withLock { tokenStore.read() } }
            currentCoroutineContext().ensureActive()
            synchronized(stateLock) {
                if (generation.get() != operation || pendingRemovals > 0) {
                    return@withLock Result.failure(IllegalStateException("MVSEP connection changed"))
                }
                cachedToken = token
                mutableState.value = mvsepLocallyLoadedState(token)
            }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            synchronized(stateLock) {
                if (generation.get() == operation) mutableState.value = mvsepLocalLoadFailureState()
            }
            Result.failure(error)
        }
    }

    suspend fun saveToken(token: String): Result<Unit> = accountOperationMutex.withLock {
        val normalized = token.trim()
        if (normalized.length < 12) {
            return@withLock Result.failure(
                IllegalArgumentException("Enter the complete MVSEP API token")
            )
        }
        tokenLoadReady.await()
        val (previous, operation) = synchronized(stateLock) {
            val snapshot = cachedToken to mutableState.value
            val operation = generation.get()
            mutableState.value = if (snapshot.first != null && snapshot.second.status == MvsepConnectionStatus.READY)
                snapshot.second.copy(verifying = true) else MvsepConnectionState(MvsepConnectionStatus.CHECKING)
            snapshot to operation
        }
        var result = try {
            validateTokenSerialized(normalized)
        } catch (cancelled: CancellationException) {
            if (!restorePreviousAccount(operation, previous.first, previous.second)) {
                synchronized(stateLock) {
                    if (generation.get() == operation) {
                        cachedToken = null
                        mutableState.value =
                            MvsepConnectionState(MvsepConnectionStatus.NOT_CONFIGURED)
                    }
                }
            }
            throw cancelled
        }
        if (generation.get() != operation) {
            return@withLock Result.failure(
                CancellationException("MVSEP connection changed")
            )
        }
        val verified = result.getOrNull()
        if (verified != null) {
            val stored = runCatching {
                completeMvsepTokenMutation(
                    mutateDurably = {
                        withContext(Dispatchers.IO) {
                            tokenStoreMutex.withLock {
                                check(generation.get() == operation) {
                                    "MVSEP connection changed"
                                }
                                tokenStore.save(normalized)
                            }
                        }
                    },
                    publishLive = {
                        synchronized(stateLock) {
                            check(generation.get() == operation) { "MVSEP connection changed" }
                            generation.incrementAndGet()
                            cachedToken = normalized
                            mutableState.value = verified
                        }
                    }
                )
            }
            if (stored.isSuccess) return@withLock Result.success(Unit)
            val error = stored.exceptionOrNull() ?: CancellationException("MVSEP connection changed")
            result = Result.failure(error)
        }
        result.exceptionOrNull()?.let { error ->
            if (!restorePreviousAccount(operation, previous.first, previous.second)) {
                synchronized(stateLock) {
                    if (generation.get() == operation) {
                        cachedToken = null
                        mutableState.value = MvsepConnectionState(
                            status = MvsepConnectionStatus.ERROR,
                            error = friendlyError(error),
                            tokenStored = false,
                            waitingForUnmeteredNetwork = networkAccessBlocked()
                        )
                    }
                }
            }
        }
        Result.failure(result.exceptionOrNull() ?: error("MVSEP validation failed"))
    }

    override suspend fun validate(expectedCredential: MvsepCredential?): Result<Unit> = accountOperationMutex.withLock {
        tokenLoadReady.await()
        val tokenAndOperation = synchronized(stateLock) {
            if (expectedCredential != null && !isCurrent(expectedCredential)) {
                return@withLock Result.failure(IllegalStateException("MVSEP_DISCONNECTED"))
            }
            val token = cachedToken
            if (token == null) {
                if (!mutableState.value.localLoadFailed) mutableState.value =
                    MvsepConnectionState(MvsepConnectionStatus.NOT_CONFIGURED)
                null
            } else {
                val operation = generation.get()
                val previous = mutableState.value
                mutableState.value = if (previous.status == MvsepConnectionStatus.READY) {
                    previous.copy(verifying = true, error = null)
                } else MvsepConnectionState(status = MvsepConnectionStatus.CHECKING, tokenStored = true)
                Triple(token, operation, previous)
            }
        } ?: return@withLock Result.failure(
            IllegalStateException("MVSEP token is not set")
        )
        validateKnownToken(tokenAndOperation.first, tokenAndOperation.second, tokenAndOperation.third)
    }

    private suspend fun validateKnownToken(token: String, operation: Long, previous: MvsepConnectionState): Result<Unit> {
        val result = try {
            validateTokenSerialized(token)
        } catch (cancelled: CancellationException) {
            synchronized(stateLock) {
                if (generation.get() == operation && cachedToken == token) {
                    mutableState.value = previous
                }
            }
            throw cancelled
        }
        val failure = result.exceptionOrNull()
        if (failure == null) {
            synchronized(stateLock) {
                if (generation.get() == operation && cachedToken == token) {
                    val verified = result.getOrThrow()
                    if (previous.status == MvsepConnectionStatus.READY &&
                        previous.premiumEnabled != verified.premiumEnabled) {
                        generation.incrementAndGet()
                    }
                    mutableState.value = verified
                }
            }
        } else if (generation.get() == operation) {
            if (failure is MvsepTokenRejectedException) {
                val cleared = runCatching { completeMvsepTokenMutation(
                    mutateDurably = {
                        withContext(Dispatchers.IO) {
                            tokenStoreMutex.withLock {
                                check(generation.get() == operation && cachedToken == token) {
                                    "MVSEP connection changed"
                                }
                                tokenStore.clear()
                            }
                        }
                    },
                    publishLive = {
                        synchronized(stateLock) {
                            check(generation.get() == operation && cachedToken == token) {
                                "MVSEP connection changed"
                            }
                            cachedToken = null
                            mutableState.value = MvsepConnectionState(
                                status = MvsepConnectionStatus.ERROR,
                                error = friendlyError(failure),
                                tokenStored = false,
                                waitingForUnmeteredNetwork = networkAccessBlocked()
                            )
                        }
                    }
                ) }
                cleared.exceptionOrNull()?.let { storageFailure ->
                    if (storageFailure is CancellationException) throw storageFailure
                    synchronized(stateLock) {
                        if (generation.get() == operation && cachedToken == token) {
                            mutableState.value = MvsepConnectionState(
                                status = MvsepConnectionStatus.ERROR,
                                error = friendlyError(storageFailure),
                                tokenStored = true
                            )
                        }
                    }
                    return Result.failure(storageFailure)
                }
            } else {
                synchronized(stateLock) {
                    if (generation.get() == operation && cachedToken == token) {
                        mutableState.value = if (previous.status == MvsepConnectionStatus.READY) {
                            previous.copy(error = friendlyError(failure), verifying = false,
                                waitingForUnmeteredNetwork = networkAccessBlocked())
                        } else MvsepConnectionState(
                            status = MvsepConnectionStatus.ERROR, error = friendlyError(failure),
                            tokenStored = true, waitingForUnmeteredNetwork = networkAccessBlocked())
                    }
                }
            }
        }
        return result.map { Unit }
    }

    suspend fun removeToken(): Result<Unit> {
        currentCoroutineContext().ensureActive()
        val validation = synchronized(stateLock) {
            pendingRemovals++
            activeValidation
        }
        validation?.cancel(CancellationException("MVSEP disconnected locally"))
        try {
            return accountOperationMutex.withLock { removeTokenUnderLock() }
        } finally {
            synchronized(stateLock) { pendingRemovals-- }
        }
    }

    private suspend fun removeTokenUnderLock(): Result<Unit> {
        tokenLoadReady.await()
        val (previousToken, previousState, operation) = synchronized(stateLock) {
            val prior = cachedToken to mutableState.value
            val nextGeneration = generation.incrementAndGet()
            cachedToken = null
            mutableState.value = MvsepConnectionState(
                status = MvsepConnectionStatus.CHECKING,
                tokenStored = prior.first != null
            )
            Triple(prior.first, prior.second, nextGeneration)
        }
        val result = runCatching {
            completeMvsepTokenMutation(
                mutateDurably = {
                    withContext(Dispatchers.IO) {
                        tokenStoreMutex.withLock {
                            check(generation.get() == operation && cachedToken == null) {
                                "MVSEP connection changed"
                            }
                            tokenStore.clear()
                        }
                    }
                },
                publishLive = {
                    synchronized(stateLock) {
                        check(generation.get() == operation && cachedToken == null) {
                            "MVSEP connection changed"
                        }
                        mutableState.value =
                            MvsepConnectionState(MvsepConnectionStatus.NOT_CONFIGURED)
                    }
                }
            )
        }
        result.exceptionOrNull()?.let {
            synchronized(stateLock) {
                if (generation.get() == operation && cachedToken == null) {
                    cachedToken = previousToken
                    mutableState.value = previousState
                }
            }
        }
        return result
    }

    private fun restorePreviousAccount(
        operation: Long,
        previousToken: String?,
        previousState: MvsepConnectionState
    ): Boolean = synchronized(stateLock) {
        if (generation.get() == operation && previousToken != null) {
            cachedToken = previousToken
            mutableState.value = if (previousState.status == MvsepConnectionStatus.CHECKING) {
                MvsepConnectionState(
                    status = MvsepConnectionStatus.ERROR,
                    error = "Previous MVSEP connection needs verification",
                    tokenStored = true
                )
            } else {
                previousState.copy(tokenStored = true)
            }
            true
        } else {
            false
        }
    }

    internal fun token(): String? = cachedToken.takeIf {
        mutableState.value.status == MvsepConnectionStatus.READY
    }

    override fun credential(): MvsepCredential? = token()?.let {
        MvsepCredential(it, generation.get())
    }

    override fun isCurrent(credential: MvsepCredential): Boolean =
        generation.get() == credential.generation &&
            mutableState.value.status == MvsepConnectionStatus.READY &&
            mutableState.value.tokenStored &&
            cachedToken == credential.token

    override fun apiBase(): String = mutableState.value.apiBase ?: API_BASE

    private suspend fun validateTokenSerialized(
        token: String
    ): Result<MvsepConnectionState> = validationMutex.withLock {
        supervisorScope {
            val request = async(start = CoroutineStart.LAZY) { validateTokenRequest(token) }
            synchronized(stateLock) {
                if (pendingRemovals > 0) request.cancel(CancellationException("MVSEP disconnected locally"))
                activeValidation = request
            }
            try {
                Result.success(request.await())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Result.failure(error)
            } finally {
                synchronized(stateLock) {
                    if (activeValidation === request) activeValidation = null
                }
            }
        }
    }

    private suspend fun validateTokenRequest(token: String): MvsepConnectionState {
        return validateAcrossMvsepRegions(API_BASES) { apiBase ->
            validateTokenAt(apiBase, token)
        }
    }

    private suspend fun validateTokenAt(apiBase: String, token: String): MvsepConnectionState {
        // MVSEP currently rejects POST for this endpoint (HTTP 405), so its
        // documented HTTPS query contract is required. Never log this URL.
        val request = Request.Builder()
            .url(mvsepUserUrl(apiBase, token).toString())
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", "StemWise Android")
            .build()
        return networkAccess.whileUnmetered {
            networkAccess.newCall(MvsepNetwork.client, request).executeCancellable { response ->
                val body = response.body?.readBoundedMvsepJson().orEmpty()
                if (response.code == 400 || response.code == 401) {
                    throw MvsepTokenRejectedException()
                }
                check(response.isSuccessful) {
                    "MVSEP returned ${response.code}"
                }
                val root = JSONObject(body)
                if (!root.optBoolean("success")) {
                    val message = root.optString("message")
                    if (
                        message.contains("credential", ignoreCase = true) ||
                        message.contains("token", ignoreCase = true) ||
                        message.contains("key", ignoreCase = true)
                    ) {
                        throw MvsepTokenRejectedException()
                    }
                    error(message.ifBlank { "MVSEP rejected this token" })
                }
                val data = root.optJSONObject("data")
                    ?: error("MVSEP returned an incomplete account response")
                val premiumEnabled = parseMvsepPremiumEnabled(data)
                MvsepConnectionState(
                    status = MvsepConnectionStatus.READY,
                    accountName = data.optString("name").takeIf { it.isNotBlank() },
                    premiumMinutes = data.optDouble("premium_minutes", Double.NaN)
                        .takeIf { it.isFinite() },
                    premiumEnabled = premiumEnabled,
                    tokenStored = true,
                    apiBase = apiBase
                )
            }
        }
    }

    private fun friendlyError(error: Throwable): String {
        val raw = error.message.orEmpty()
        return when {
            error is UnmeteredNetworkRequiredException -> WAITING_FOR_UNMETERED_NETWORK
            raw.contains("rejected", ignoreCase = true) -> "Token not accepted"
            networkAccessBlocked() && (
                raw.contains("Unable to resolve", ignoreCase = true) ||
                    raw.contains("timed out", ignoreCase = true) ||
                    raw.contains("connect", ignoreCase = true)
                ) -> WAITING_FOR_UNMETERED_NETWORK
            raw.contains("Unable to resolve", ignoreCase = true) ||
                raw.contains("timed out", ignoreCase = true) ||
                raw.contains("connect", ignoreCase = true) -> "Could not reach MVSEP"
            raw.isNotBlank() -> raw.take(120)
            else -> "Could not verify MVSEP"
        }
    }

    private fun networkAccessBlocked(): Boolean =
        !AppOwnedNetworkPolicy.allowsNetwork(networkAccess.currentState())

    companion object {
        const val API_BASE = "https://de.mvsep.com/api"
        val API_BASES = listOf(
            API_BASE,
            "https://hk.mvsep.com/api",
            "https://de2.mvsep.com/api",
            "https://mvsep.com/api"
        )
    }
}

internal fun mvsepLocallyLoadedState(token: String?): MvsepConnectionState =
    if (token == null) {
        MvsepConnectionState(status = MvsepConnectionStatus.NOT_CONFIGURED)
    } else {
        MvsepConnectionState(
            status = MvsepConnectionStatus.SAVED_UNVERIFIED,
            tokenStored = true
        )
    }

private class MvsepTokenRejectedException : IllegalArgumentException(
    "MVSEP rejected this token"
)

internal suspend fun validateAcrossMvsepRegions(
    apiBases: List<String>,
    validateAt: suspend (String) -> MvsepConnectionState
): MvsepConnectionState {
    var lastFailure: Throwable? = null
    for (apiBase in apiBases) {
        try {
            return validateAt(apiBase)
        } catch (rejected: MvsepTokenRejectedException) {
            // All documented regions share one account database; a definite credential
            // rejection is authoritative and must not be replayed against another region.
            throw rejected
        } catch (cancelled: CancellationException) {
            // Cancellation belongs to the caller (including WorkManager constraint loss).
            // Falling through here would detach a new provider request from that owner.
            throw cancelled
        } catch (blocked: UnmeteredNetworkRequiredException) {
            throw blocked
        } catch (error: Throwable) {
            lastFailure = error
        }
    }
    throw lastFailure ?: IllegalStateException("Could not reach MVSEP")
}

internal fun mvsepUserUrl(apiBase: String, token: String): URL {
    val encodedToken = URLEncoder.encode(token, Charsets.UTF_8.name())
    return URL("$apiBase/app/user?api_token=$encodedToken")
}

internal suspend fun completeMvsepTokenMutation(
    mutateDurably: suspend () -> Unit,
    publishLive: () -> Unit
) = withContext(NonCancellable) {
    mutateDurably()
    publishLive()
}

internal fun mvsepLocalLoadFailureState(): MvsepConnectionState = MvsepConnectionState(
    status = MvsepConnectionStatus.ERROR,
    error = "Could not load the saved MVSEP connection",
    localLoadFailed = true,
    tokenStored = false
)

internal fun requireMvsepTokenPreferenceCommit(committed: Boolean) {
    check(committed) { "MVSEP_TOKEN_STORE_COMMIT_FAILED" }
}

private class SecureTokenStore(context: Context) : MvsepTokenStore {
    private val applicationContext = context.applicationContext
    // The token store is first used by MvsepAccount's IO scope. Delaying this
    // lookup avoids synchronous preference-directory access during app startup.
    private val preferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    }

    override fun save(token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        requireMvsepTokenPreferenceCommit(
            preferences.edit()
                .putString(KEY_ENCRYPTED_TOKEN, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .commit()
        )
    }

    override fun read(): String? {
        val encrypted = preferences.getString(KEY_ENCRYPTED_TOKEN, null)
            ?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?: return null
        val iv = preferences.getString(KEY_IV, null)
            ?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        // A failed read can be transient. The account owner reports it without deleting credentials.
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    override fun clear() {
        requireMvsepTokenPreferenceCommit(
            preferences.edit()
                .remove(KEY_ENCRYPTED_TOKEN)
                .remove(KEY_IV)
                .commit()
        )
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE
        ).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    companion object {
        private const val PREFERENCES = "stemwise-secure"
        private const val KEY_ENCRYPTED_TOKEN = "mvsep_token"
        private const val KEY_IV = "mvsep_token_iv"
        private const val KEY_ALIAS = "stemwise-mvsep-token"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
