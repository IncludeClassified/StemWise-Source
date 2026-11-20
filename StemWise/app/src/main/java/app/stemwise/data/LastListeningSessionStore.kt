package app.stemwise.data

import android.util.AtomicFile
import app.stemwise.playback.PlaybackQueueSnapshot
import app.stemwise.playback.PlaybackSourceMode
import app.stemwise.playback.RepeatMode
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Position uses the immutable Original clock; per-song controls have their own atomic row. */
internal data class LastListeningSession(
    val identity: SongPlaybackSettingsIdentity,
    val queue: PlaybackQueueSnapshot,
    val sourcePositionMs: Long,
    val sourceMode: PlaybackSourceMode = PlaybackSourceMode.ORIGINAL
) {
    init {
        require(queue.currentId == identity.songId && sourcePositionMs >= 0L) {
            "LAST_LISTENING_SESSION_INVALID"
        }
    }
}

/** One app-local last session, bound to the selected root and the exact current Original. */
internal class LastListeningSessionStore(private val file: File) {
    private val positionFile = File(file.parentFile, "${file.name.removeSuffix(".json")}-position.json")
    private var saved: LastListeningSession? = null
    private var revision: String? = null

    @Synchronized
    fun read(): LastListeningSession? {
        val base = readRecoverable(file) { value ->
            check(sessionLong(value.opt("storageVersion")) == 1L) { "LAST_LISTENING_SESSION_RECORD_INVALID" }
            val token = value.getString("revision")
            check(Regex("[a-f0-9]{32}").matches(token)) { "LAST_LISTENING_SESSION_RECORD_INVALID" }
            token to parseLastListeningSession(value.getJSONObject("session"))
        } ?: return null
        val position = readRecoverable(positionFile) { value ->
            check(sessionLong(value.opt("version")) == 1L) { "LAST_LISTENING_SESSION_RECORD_INVALID" }
            val token = value.getString("revision")
            val cursor = sessionLong(value.opt("sourcePositionMs"))
            check(cursor >= 0L) { "LAST_LISTENING_SESSION_RECORD_INVALID" }
            Triple(token, cursor, PlaybackSourceMode.valueOf(sessionString(value.opt("sourceMode"))))
        }
        return if (position?.first == base.first) base.second.copy(sourcePositionMs = position.second,
            sourceMode = position.third) else base.second
    }

    private fun <T> readRecoverable(target: File, parse: (JSONObject) -> T): T? = try {
        val value = readAtomicJournal(target, "LAST_LISTENING_SESSION_UNAVAILABLE")?.value
        if (value == null) null else try { parse(value) }
        catch (error: Exception) { throw AtomicJournalFormatException("LAST_LISTENING_SESSION_RECORD_INVALID", error) }
    } catch (malformed: AtomicJournalFormatException) {
        quarantineMalformedPlaybackRecord(target)
        null
    }

    @Synchronized
    fun write(session: LastListeningSession) {
        val prior = saved
        val sameQueue = prior != null && prior.identity == session.identity &&
            prior.queue.order === session.queue.order && prior.queue.currentIndex == session.queue.currentIndex &&
            prior.queue.shuffleEnabled == session.queue.shuffleEnabled && prior.queue.shuffleSeed == session.queue.shuffleSeed &&
            prior.queue.repeatMode == session.queue.repeatMode
        if (!sameQueue) {
            // Admission reads before replacement; an unavailable prior record never authorizes overwriting it.
            read()
            session.queue.validate()
            val token = java.util.UUID.randomUUID().toString().replace("-", "")
            val value = JSONObject().put("storageVersion", 1).put("revision", token)
                .put("session", lastListeningSessionJson(session))
            writeAtomic(file, value)
            val confirmed = read()
            check(confirmed == session) { "LAST_LISTENING_SESSION_SAVE_UNCONFIRMED" }
            revision = token
        } else {
            val token = checkNotNull(revision)
            val value = JSONObject().put("version", 1).put("revision", token)
                .put("sourcePositionMs", session.sourcePositionMs).put("sourceMode", session.sourceMode.name)
            writeAtomic(positionFile, value)
            val confirmed = readAtomicJournal(positionFile, "LAST_LISTENING_SESSION_UNAVAILABLE")?.value
            check(confirmed != null && confirmed.toString() == value.toString()) { "LAST_LISTENING_SESSION_SAVE_UNCONFIRMED" }
        }
        saved = session
    }

    private fun writeAtomic(target: File, value: JSONObject) {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        check(bytes.size.toLong() <= MAX_ATOMIC_JOURNAL_BYTES) { "LAST_LISTENING_SESSION_TOO_LARGE" }
        val parent = checkNotNull(target.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "LAST_LISTENING_SESSION_UNAVAILABLE" }
        val atomic = AtomicFile(target)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    @Synchronized
    fun clear() {
        read()
        deleteAtomicJournal(file, "LAST_LISTENING_SESSION_CLEAR_UNCONFIRMED")
        deleteAtomicJournal(positionFile, "LAST_LISTENING_SESSION_CLEAR_UNCONFIRMED")
        saved = null
        revision = null
    }
}

internal fun lastListeningSessionJson(session: LastListeningSession): JSONObject = JSONObject()
    .put("version", 1)
    .put("libraryId", session.identity.libraryId)
    .put("treeUri", session.identity.treeUri)
    .put("songId", session.identity.songId)
    .put("originalSha256", session.identity.originalSha256)
    .put("queue", JSONObject()
        .put("order", JSONArray(session.queue.order))
        .put("currentIndex", session.queue.currentIndex)
        .put("shuffleEnabled", session.queue.shuffleEnabled)
        .put("shuffleSeed", session.queue.shuffleSeed.toString())
        .put("repeatMode", session.queue.repeatMode.name))
    .put("sourcePositionMs", session.sourcePositionMs)
    .put("sourceMode", session.sourceMode.name)

internal fun parseLastListeningSession(value: JSONObject): LastListeningSession {
    requireSessionKeys(value, setOf("version", "libraryId", "treeUri", "songId", "originalSha256",
        "queue", "sourcePositionMs", "sourceMode"))
    check(sessionLong(value.opt("version")) == 1L) { "LAST_LISTENING_SESSION_RECORD_INVALID" }
    val queue = value.getJSONObject("queue")
    requireSessionKeys(queue, setOf("order", "currentIndex", "shuffleEnabled", "shuffleSeed", "repeatMode"))
    val index = sessionLong(queue.opt("currentIndex"))
    check(index in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "LAST_LISTENING_SESSION_RECORD_INVALID" }
    val order = queue.getJSONArray("order")
    return LastListeningSession(
        SongPlaybackSettingsIdentity(sessionString(value.opt("libraryId")), sessionString(value.opt("treeUri")),
            sessionString(value.opt("songId")), sessionString(value.opt("originalSha256"))),
        PlaybackQueueSnapshot(
            List(order.length()) { sessionString(order.opt(it)) }, index.toInt(),
            queue.opt("shuffleEnabled") as? Boolean ?: error("LAST_LISTENING_SESSION_RECORD_INVALID"),
            sessionString(queue.opt("shuffleSeed")).let { seed ->
                seed.toLongOrNull()?.takeIf { it.toString() == seed } ?: error("LAST_LISTENING_SESSION_RECORD_INVALID")
            }, RepeatMode.valueOf(sessionString(queue.opt("repeatMode")))
        ),
        sessionLong(value.opt("sourcePositionMs")), PlaybackSourceMode.valueOf(sessionString(value.opt("sourceMode")))
    ).also { it.queue.validate() }
}

private fun requireSessionKeys(value: JSONObject, keys: Set<String>) {
    check(value.keys().asSequence().toSet() == keys) { "LAST_LISTENING_SESSION_RECORD_INVALID" }
}

private fun sessionLong(value: Any?): Long =
    exactAtomicJournalLong(value) ?: error("LAST_LISTENING_SESSION_RECORD_INVALID")

private fun sessionString(value: Any?): String =
    (value as? String)?.takeIf(String::isNotBlank) ?: error("LAST_LISTENING_SESSION_RECORD_INVALID")
