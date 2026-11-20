package app.stemwise.playback

import app.stemwise.data.LastListeningSession
import app.stemwise.data.LastListeningSessionStore
import app.stemwise.data.SongPlaybackSettings
import app.stemwise.data.SongPlaybackSettingsIdentity
import app.stemwise.data.SongPlaybackSettingsStore
import app.stemwise.data.SongTrimRange
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal class SongPlaybackSettingsException(cause: Exception) :
    java.io.IOException("Could not read your saved song settings. Close and try again.", cause)

/** Owns app-local preference bytes, cached deltas and ordered durable writes; native ownership stays in the facade. */
internal class PlaybackPreferencesPersistence(
    directory: File,
    private val scope: CoroutineScope,
    private val settingsFailure: (Throwable) -> Unit,
    private val listeningFailure: (Exception) -> Unit
) {
    private val settingsStore by lazy { SongPlaybackSettingsStore(File(directory, "song-playback-settings")) }
    internal val listeningStore by lazy { LastListeningSessionStore(File(directory, "last-listening-session.json")) }
    val savedSettings = ConcurrentHashMap<SongPlaybackSettingsIdentity, Result<SongPlaybackSettings>>()
    private class PendingSettings(val value: SongPlaybackSettings)
    private val pendingSettings = ConcurrentHashMap<SongPlaybackSettingsIdentity, PendingSettings>()
    private val revision = MutableStateFlow(0L)
    val settingsRevision = revision.asStateFlow()
    @Volatile private var settingsWriter: Job? = null
    private val listeningWriteLock = Any()
    private var pendingListeningWrite: LastListeningSession? = null
    private var listeningWriterActive = false
    private var listeningWriter: Job? = null
    private var lastQueuedListeningSession: LastListeningSession? = null

    fun cachedSettings(identity: SongPlaybackSettingsIdentity): Result<SongPlaybackSettings>? =
        savedSettings[identity]?.map { saved ->
            // Pending controls cannot establish a durable trim or replace a read failure.
            pendingSettings[identity]?.value?.copy(trim = saved.trim) ?: saved
        }

    suspend fun readSettings(identity: SongPlaybackSettingsIdentity, durationMs: Long, retryFailure: Boolean,
        requireCurrent: () -> Unit): SongPlaybackSettings {
        if (retryFailure) savedSettings[identity]?.takeIf { it.isFailure }?.let { savedSettings.remove(identity, it) }
        if (cachedSettings(identity) == null) withContext(Dispatchers.IO) {
            val preparationContext = currentCoroutineContext()
            synchronized(settingsStore) {
                if (cachedSettings(identity) == null) {
                    val result = try { Result.success(settingsStore.read(identity, durationMs) ?: SongPlaybackSettings()) }
                    catch (error: Exception) { Result.failure(SongPlaybackSettingsException(error)) }
                    preparationContext.ensureActive()
                    requireCurrent()
                    if (savedSettings.putIfAbsent(identity, result) == null) changed()
                }
            }
        }
        val settings = checkNotNull(cachedSettings(identity)).getOrThrow()
        try { settings.validateDuration(durationMs) }
        catch (error: Exception) { throw SongPlaybackSettingsException(error) }
        return settings
    }

    suspend fun readTrim(identity: SongPlaybackSettingsIdentity, durationMs: Long): SongTrimRange? = withContext(Dispatchers.IO) {
        settingsWriter?.join()
        synchronized(settingsStore) {
            val result = try { Result.success(settingsStore.read(identity, durationMs) ?: SongPlaybackSettings()) }
            catch (error: Exception) { Result.failure(SongPlaybackSettingsException(error)) }
            savedSettings[identity] = result
            changed()
            result.getOrThrow().trim
        }
    }

    suspend fun resetMalformed(identity: SongPlaybackSettingsIdentity, durationMs: Long) {
        settingsWriter?.join()
        withContext(Dispatchers.IO) { settingsStore.resetMalformed(identity, durationMs) }
        savedSettings.remove(identity)
        changed()
    }

    fun persistSettings(identity: SongPlaybackSettingsIdentity, durationMs: Long, expected: SongPlaybackSettings) {
        val pending = PendingSettings(expected)
        pendingSettings[identity] = pending
        changed()
        val previous = settingsWriter
        settingsWriter = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val failure = withContext(NonCancellable + Dispatchers.IO) {
                previous?.join()
                runCatching {
                    synchronized(settingsStore) {
                        val saved = settingsStore.update(identity, durationMs) { expected.copy(trim = it.trim) }
                        savedSettings[identity] = Result.success(saved)
                        pendingSettings.remove(identity, pending)
                    }
                }.exceptionOrNull()
            }
            if (failure != null) settingsFailure(failure)
        }
    }

    suspend fun writeTrim(identity: SongPlaybackSettingsIdentity, durationMs: Long, range: SongTrimRange?,
        requireCurrent: () -> Unit, onCommitted: () -> Unit) {
        val previous = settingsWriter
        val write = scope.async(start = CoroutineStart.UNDISPATCHED) {
            val result = withContext(NonCancellable + Dispatchers.IO) {
                previous?.join()
                runCatching {
                    synchronized(settingsStore) {
                        val pending = pendingSettings[identity]
                        requireCurrent()
                        savedSettings[identity] = Result.success(settingsStore.update(identity, durationMs) {
                            (pending?.value ?: it).copy(trim = range)
                        })
                        if (pending != null) pendingSettings.remove(identity, pending)
                        changed()
                    }
                }
            }
            if (result.isSuccess) withContext(NonCancellable + Dispatchers.Main.immediate) { onCommitted() }
            result
        }
        settingsWriter = write
        write.await().getOrThrow()
    }

    fun saveListeningSession(session: LastListeningSession) {
        val startWriter = synchronized(listeningWriteLock) {
            if (session == lastQueuedListeningSession) return
            lastQueuedListeningSession = session
            pendingListeningWrite = session
            if (listeningWriterActive) false else { listeningWriterActive = true; true }
        }
        if (!startWriter) return
        listeningWriter = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable + Dispatchers.IO) {
                while (true) {
                    val next = synchronized(listeningWriteLock) {
                        pendingListeningWrite.also {
                            pendingListeningWrite = null
                            if (it == null) listeningWriterActive = false
                        }
                    } ?: break
                    try { listeningStore.write(next) }
                    catch (error: Exception) {
                        synchronized(listeningWriteLock) {
                            if (lastQueuedListeningSession == next) lastQueuedListeningSession = null
                        }
                        listeningFailure(error)
                    }
                }
            }
        }
    }

    suspend fun awaitListeningWrites() { listeningWriter?.join() }

    private fun changed() { revision.update { incrementRevision(it, "song settings") } }
}
