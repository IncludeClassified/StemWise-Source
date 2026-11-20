package app.stemwise.integration

import android.system.Os
import android.util.AtomicFile
import app.stemwise.data.RootAuthority
import app.stemwise.data.ProcessingJobEntity
import app.stemwise.readStrictJsonObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Retains a received remote identity until its exact selected-root publication is durable. */
internal class SourceAcknowledgementStore(
    private val directory: File,
    private val publicationTimeoutMs: Long = 10_000L
) {
    init { require(publicationTimeoutMs > 0L) }
    private fun file(root: RootAuthority, jobId: String): AtomicFile {
        val identity = JSONArray().put(root.libraryId).put(root.treeUri).put(jobId)
        val name = MessageDigest.getInstance("SHA-256").digest(identity.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return AtomicFile(File(directory, "$name.json"))
    }

    private fun retain(root: RootAuthority, acknowledged: PortableSourceJobAuthority) {
        check(root.libraryId == acknowledged.libraryId && acknowledged.currentRemoteJobId != null) {
            PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED
        }
        check(directory.isDirectory || directory.mkdirs()) { "SOURCE_ACKNOWLEDGEMENT_STORAGE_FAILED" }
        val bytes = JSONObject().put("libraryId", root.libraryId).put("treeUri", root.treeUri)
            .put("authority", acknowledged.toBytes().toString(Charsets.UTF_8)).toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAXIMUM_BYTES) { "SOURCE_ACKNOWLEDGEMENT_STORAGE_FAILED" }
        val target = file(root, acknowledged.jobId)
        val output = target.startWrite()
        try {
            output.write(bytes)
            target.finishWrite(output)
        } catch (failure: Throwable) {
            target.failWrite(output)
            throw failure
        }
    }

    fun read(root: RootAuthority, current: PortableSourceJobAuthority): PortableSourceJobAuthority? {
        val acknowledged = readRecord(root, current.jobId) ?: return null
        check(acknowledged.sameIdentity(current)) { PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED }
        return selectPortableSourceJobAuthority(current.toBytes(), acknowledged.toBytes())
    }

    fun read(root: RootAuthority, job: ProcessingJobEntity): PortableSourceJobAuthority? =
        readRecord(root, job.id)?.also { acknowledged ->
            check(acknowledged.libraryId == job.libraryId && acknowledged.videoId == job.songId &&
                acknowledged.sourceVersionId == job.sourceVersionId &&
                "source-plan:${acknowledged.planSha256}" == job.modelId) { PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED }
        }

    private fun readRecord(root: RootAuthority, jobId: String): PortableSourceJobAuthority? {
        val target = file(root, jobId)
        if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists()) return null
        val bytes = target.openRead().use { input ->
            val size = Os.fstat(input.fd).st_size
            check(size in 1..MAXIMUM_BYTES) { PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED }
            ByteArray(size.toInt()).also { bytes ->
                var offset = 0
                while (offset < bytes.size) {
                    val count = input.read(bytes, offset, bytes.size - offset)
                    check(count > 0) { PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED }
                    offset += count
                }
                check(input.read() == -1) { PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED }
            }
        }
        val value = readStrictJsonObject(bytes, MAXIMUM_BYTES)
        check(value.keys().asSequence().toSet() == setOf("libraryId", "treeUri", "authority") &&
            value.opt("libraryId") == root.libraryId && value.opt("treeUri") == root.treeUri) {
            PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED
        }
        val acknowledged = parsePortableSourceJobAuthority((value.get("authority") as String).toByteArray(Charsets.UTF_8))
        check(acknowledged.jobId == jobId && acknowledged.libraryId == root.libraryId &&
            acknowledged.currentRemoteJobId != null) {
            PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED
        }
        return acknowledged
    }

    fun remove(root: RootAuthority, jobId: String) {
        val target = file(root, jobId)
        target.delete()
        check(!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists()) {
            "SOURCE_ACKNOWLEDGEMENT_CLEANUP_FAILED"
        }
    }

    suspend fun <T> settle(root: RootAuthority, acknowledged: PortableSourceJobAuthority, publish: suspend () -> T): T =
        withContext(NonCancellable + Dispatchers.IO) {
            retain(root, acknowledged)
            val durable = withTimeoutOrNull(publicationTimeoutMs) { Result.success(publish()) }
                ?: throw IOException("SOURCE_ACKNOWLEDGEMENT_PUBLICATION_PENDING")
            remove(root, acknowledged.jobId)
            durable.getOrThrow()
        }

    private companion object {
        const val MAXIMUM_BYTES = PORTABLE_SOURCE_JOB_MAX_BYTES + 8192L
    }
}
