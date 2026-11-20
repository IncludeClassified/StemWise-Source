package app.stemwise.ui

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import android.content.ContentResolver
import app.stemwise.integration.withCancellableProviderInput
import app.stemwise.integration.withCancellableProviderOutput
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The picker owns the destination; success requires exact UTF-8 provider readback. */
internal suspend fun writePlaybackSnapshot(resolver: ContentResolver, uri: Uri, snapshot: String): Result<Unit> =
    withContext(Dispatchers.IO) {
        try {
            val bytes = snapshot.toByteArray(Charsets.UTF_8)
            checkNotNull(resolver.withCancellableProviderOutput(uri) { _, write, flush ->
                write(bytes, 0, bytes.size)
                flush()
            })
            checkNotNull(resolver.withCancellableProviderInput(uri) { _, read ->
                val buffer = ByteArray(64 * 1024)
                var count = 0
                while (true) {
                    val next = read(buffer)
                    if (next < 0) break
                    check(next > 0 && next <= bytes.size - count) { "PLAYBACK_SNAPSHOT_READBACK_MISMATCH" }
                    for (index in 0 until next) check(buffer[index] == bytes[count + index]) {
                        "PLAYBACK_SNAPSHOT_READBACK_MISMATCH"
                    }
                    count += next
                }
                check(count == bytes.size) { "PLAYBACK_SNAPSHOT_READBACK_INCOMPLETE" }
            })
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

internal enum class PlaybackSnapshotExportPhase { CAPTURED, PICKER, WRITING, SAVED, CANCELLED, FAILED }

internal data class PlaybackSnapshotExportRecord(
    val id: String,
    val phase: PlaybackSnapshotExportPhase,
    val sha256: String,
    val bytes: Long,
    val destination: String? = null,
    val acquiredGrantFlags: Int = 0
) {
    val ownsPersistentGrant get() = acquiredGrantFlags != 0
    val busy get() = phase in setOf(PlaybackSnapshotExportPhase.CAPTURED, PlaybackSnapshotExportPhase.PICKER,
        PlaybackSnapshotExportPhase.WRITING)
    val canCancel get() = phase == PlaybackSnapshotExportPhase.CAPTURED || phase == PlaybackSnapshotExportPhase.PICKER
    val message get() = when (phase) {
        PlaybackSnapshotExportPhase.SAVED -> "Playback snapshot saved"
        PlaybackSnapshotExportPhase.CANCELLED -> "Playback snapshot export cancelled"
        PlaybackSnapshotExportPhase.FAILED -> "Could not save the playback snapshot. Try another location."
        else -> null
    }
}

/** One current export receipt and private immutable payload; no document content enters saved state. */
internal class PlaybackSnapshotExportStore(context: Context) {
    private val resolver = context.contentResolver
    private val directory by lazy { File(context.noBackupFilesDir, "playback-snapshot-export") }
    private val receipt by lazy { AtomicFile(File(directory, "current.json")) }

    suspend fun current(): PlaybackSnapshotExportRecord? = owned { read()?.also { cleanupTerminal(it) } }

    suspend fun prepare(snapshot: String): String = owned {
        val previous = read()
        check(previous?.busy != true) { "PLAYBACK_SNAPSHOT_EXPORT_PENDING" }
        previous?.let { cleanupTerminal(it) }
        check(directory.isDirectory || directory.mkdirs()) { "PLAYBACK_SNAPSHOT_PRIVATE_STORAGE_UNAVAILABLE" }
        // Only unreferenced captures from an interrupted prepare are disposable here.
        directory.listFiles().orEmpty().filter { it.name.startsWith("capture-") }.forEach { file ->
            val candidate = file.name.removePrefix("capture-").removeSuffix(".bak").removeSuffix(".new").removeSuffix(".json")
            if (runCatching { UUID.fromString(candidate).toString() == candidate }.getOrDefault(false)) {
                payload(candidate).delete()
            }
        }
        val id = UUID.randomUUID().toString()
        val bytes = snapshot.toByteArray(Charsets.UTF_8)
        writeAtomic(payload(id), bytes)
        write(PlaybackSnapshotExportRecord(id, PlaybackSnapshotExportPhase.CAPTURED, hash(bytes), bytes.size.toLong()))
        id
    }

    suspend fun markPickerLaunched(id: String): Boolean = owned {
        val record = read()?.takeIf { it.id == id && it.phase == PlaybackSnapshotExportPhase.CAPTURED }
            ?: return@owned false
        write(record.copy(phase = PlaybackSnapshotExportPhase.PICKER))
        true
    }

    suspend fun acceptResult(id: String, uri: Uri?): Boolean = owned {
        val record = read()?.takeIf { it.id == id && it.phase == PlaybackSnapshotExportPhase.PICKER }
            ?: return@owned false
        if (uri == null) {
            val cancelled = record.copy(phase = PlaybackSnapshotExportPhase.CANCELLED)
            write(cancelled)
            cleanupTerminal(cancelled)
        } else {
            val existing = resolver.persistedUriPermissions.filter { it.uri == uri }.fold(0) { flags, grant ->
                flags or (if (grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                    (if (grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            }
            val requested = (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) and existing.inv()
            val acquired = if (requested == 0) 0 else try {
                resolver.takePersistableUriPermission(uri, requested)
                requested
            } catch (_: SecurityException) { 0 } // Transient-only destinations still require exact write/readback.
            write(record.copy(phase = PlaybackSnapshotExportPhase.WRITING, destination = uri.toString(), acquiredGrantFlags = acquired))
        }
        true
    }

    suspend fun cancel(id: String): Boolean = owned {
        val record = read()?.takeIf { it.id == id && it.canCancel } ?: return@owned false
        val cancelled = record.copy(phase = PlaybackSnapshotExportPhase.CANCELLED)
        write(cancelled)
        cleanupTerminal(cancelled)
        true
    }

    suspend fun pickerFailed(id: String) = owned {
        val record = read()?.takeIf { it.id == id && it.canCancel } ?: return@owned
        val failed = record.copy(phase = PlaybackSnapshotExportPhase.FAILED)
        write(failed)
        cleanupTerminal(failed)
    }

    suspend fun finish(id: String): PlaybackSnapshotExportRecord? = owned {
        val record = read()?.takeIf { it.id == id } ?: return@owned null
        if (record.phase != PlaybackSnapshotExportPhase.WRITING) return@owned record
        val result = try {
            val bytes = payload(id).openRead().use { it.readBytes() }
            check(bytes.size.toLong() == record.bytes && hash(bytes) == record.sha256) { "PLAYBACK_SNAPSHOT_CAPTURE_CHANGED" }
            writePlaybackSnapshot(resolver, Uri.parse(checkNotNull(record.destination)), bytes.toString(Charsets.UTF_8))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
        val terminal = record.copy(phase = if (result.isSuccess) PlaybackSnapshotExportPhase.SAVED else PlaybackSnapshotExportPhase.FAILED)
        write(terminal) // A cancelled writer retains WRITING and its captured bytes for recreation.
        cleanupTerminal(terminal)
        read()
    }

    private fun payload(id: String): AtomicFile {
        check(UUID.fromString(id).toString() == id) { "PLAYBACK_SNAPSHOT_OPERATION_INVALID" }
        return AtomicFile(File(directory, "capture-$id.json"))
    }

    private fun read(): PlaybackSnapshotExportRecord? {
        if (!receipt.baseFile.exists() && !File(receipt.baseFile.path + ".bak").exists()) return null
        val json = JSONObject(receipt.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
        val id = json.getString("id")
        check(UUID.fromString(id).toString() == id) { "PLAYBACK_SNAPSHOT_OPERATION_INVALID" }
        return PlaybackSnapshotExportRecord(id, PlaybackSnapshotExportPhase.valueOf(json.getString("phase")),
            json.getString("sha256"), json.getLong("bytes"), json.optString("destination").takeIf(String::isNotEmpty),
            json.getInt("acquiredGrantFlags").also { check(it in 0..3) { "PLAYBACK_SNAPSHOT_GRANT_INVALID" } })
    }

    private fun write(record: PlaybackSnapshotExportRecord) {
        writeAtomic(receipt, JSONObject().put("id", record.id).put("phase", record.phase.name)
            .put("sha256", record.sha256).put("bytes", record.bytes).put("destination", record.destination.orEmpty())
            .put("acquiredGrantFlags", record.acquiredGrantFlags).toString().toByteArray(Charsets.UTF_8))
    }

    private fun cleanupTerminal(record: PlaybackSnapshotExportRecord) {
        if (record.busy) return
        payload(record.id).delete()
        if (record.ownsPersistentGrant) {
            try { resolver.releasePersistableUriPermission(Uri.parse(checkNotNull(record.destination)),
                record.acquiredGrantFlags) }
            catch (_: SecurityException) { /* Already revoked; no private capture needs this grant. */ }
        }
        if (record.destination != null) write(record.copy(destination = null, acquiredGrantFlags = 0))
    }

    private fun writeAtomic(file: AtomicFile, bytes: ByteArray) {
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Throwable) { file.failWrite(output); throw error }
    }

    private suspend fun <T> owned(block: suspend () -> T): T = withContext(Dispatchers.IO) { currentExport.withLock { block() } }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
    private companion object { val currentExport = Mutex() }
}
