package app.stemwise.data

import android.util.AtomicFile
import java.io.File
import org.json.JSONObject

internal const val MAX_ATOMIC_JOURNAL_RECORDS = 1024
internal const val MAX_ATOMIC_JOURNAL_BYTES = 16L * 1024L * 1024L
internal const val MAX_ATOMIC_JOURNAL_AGGREGATE_BYTES = MAX_ATOMIC_JOURNAL_BYTES
private val journalIoLocks = Array(64) { Any() }
internal fun atomicJournalIoLock(file: File): Any = journalIoLocks[(file.canonicalPath.hashCode() and Int.MAX_VALUE) % journalIoLocks.size]

internal data class AtomicJournalRecord(
    val file: File,
    val value: JSONObject,
    val byteLength: Long = 0L
)

internal data class AtomicJournalEnvelope(
    val transactionId: String,
    val packageId: String,
    val operation: String,
    val libraryId: String,
    val treeUri: String,
    val rootEpoch: Long,
    val sequence: Long,
    val state: String,
    val details: JSONObject
)

internal fun checkedAtomicJournalFiles(
    directory: File,
    errorCode: String,
    maximum: Int = MAX_ATOMIC_JOURNAL_RECORDS,
    boundedErrorCode: String = errorCode,
    ignoreUnrelatedChildren: Boolean = false
): List<File> {
    require(maximum > 0)
    val canonicalDirectory = directory.canonicalFile
    val baseNames = linkedSetOf<String>()
    checkedPrivateChildren(canonicalDirectory, errorCode).forEach { child ->
        val baseName = checkedAtomicJournalBaseName(
            child,
            canonicalDirectory,
            errorCode
        )
        if (baseName == null) {
            check(ignoreUnrelatedChildren) { errorCode }
        } else {
            baseNames += baseName
            check(baseNames.size <= maximum) { boundedErrorCode }
        }
    }
    return baseNames.sorted().map { name ->
        File(canonicalDirectory, name).canonicalFile.also { base ->
            check(base.parentFile == canonicalDirectory) { errorCode }
        }
    }
}

internal fun checkedAtomicJournalRecords(
    directory: File,
    errorCode: String,
    maximum: Int = MAX_ATOMIC_JOURNAL_RECORDS,
    boundedErrorCode: String = errorCode,
    maximumBytes: Long = MAX_ATOMIC_JOURNAL_BYTES,
    maximumAggregateBytes: Long = MAX_ATOMIC_JOURNAL_AGGREGATE_BYTES
): List<AtomicJournalRecord> {
    require(maximumBytes in 1..Int.MAX_VALUE.toLong())
    require(maximumAggregateBytes > 0L)
    val retained = checkedAtomicJournalFiles(
        directory,
        errorCode,
        maximum,
        boundedErrorCode
    ).filter { base ->
        synchronized(atomicJournalIoLock(base)) {
            val backup = File(base.path + ".bak")
            if (base.exists() || backup.exists()) true else {
                val pending = File(base.path + ".new")
                if (pending.exists()) check(pending.isFile && pending.delete()) { errorCode }
                check(listOf(base, backup, pending).all { java.nio.file.Files.notExists(it.toPath()) }) { errorCode }
                false
            }
        }
    }
    var retainedBytes = 0L
    retained.forEach { base ->
        synchronized(atomicJournalIoLock(base)) {
            // AtomicFile restores a backup before opening the base; count its committed bytes once.
            val committed = File(base.path + ".bak").takeIf(File::exists) ?: base
            if (java.nio.file.Files.notExists(committed.toPath())) return@forEach
            check(committed.isFile) { errorCode }
            val bytes = committed.length()
            check(bytes in 1..maximumBytes) { errorCode }
            retainedBytes = try {
                Math.addExact(retainedBytes, bytes)
            } catch (error: ArithmeticException) {
                throw IllegalStateException(errorCode, error)
            }
            check(retainedBytes <= maximumAggregateBytes) { errorCode }
        }
    }
    var parsedBytes = 0L
    return retained.mapNotNull { base ->
        val remaining = maximumAggregateBytes - parsedBytes
        check(remaining > 0L) { errorCode }
        val record = readAtomicJournal(base, errorCode, minOf(maximumBytes, remaining)) ?: return@mapNotNull null
        parsedBytes = try {
            Math.addExact(parsedBytes, record.byteLength)
        } catch (error: ArithmeticException) {
            throw IllegalStateException(errorCode, error)
        }
        check(parsedBytes <= maximumAggregateBytes) { errorCode }
        record
    }
}

internal fun requireAtomicJournalAdmission(
    directory: File,
    file: File,
    errorCode: String,
    maximum: Int = MAX_ATOMIC_JOURNAL_RECORDS
) {
    require(maximum > 0)
    val canonicalDirectory = directory.canonicalFile
    val target = file.canonicalFile
    check(target.parentFile == canonicalDirectory && ATOMIC_JOURNAL_NAME.matches(target.name)) {
        errorCode
    }
    val seen = hashSetOf<String>()
    var exactReentry = false
    var overLimit = false
    checkedPrivateChildren(canonicalDirectory, errorCode).forEach { child ->
        val baseName = checkedAtomicJournalBaseName(child, canonicalDirectory, errorCode)
            ?: error(errorCode)
        if (baseName == target.name) exactReentry = true
        if (baseName !in seen) {
            if (seen.size < maximum) seen += baseName else overLimit = true
        }
    }
    check(!overLimit && (exactReentry || seen.size < maximum)) { errorCode }
}

internal fun readAtomicJournal(
    file: File,
    errorCode: String,
    maximumBytes: Long = MAX_ATOMIC_JOURNAL_BYTES
): AtomicJournalRecord? = synchronized(atomicJournalIoLock(file)) {
    readAtomicJournalLocked(file, errorCode, maximumBytes)
}

private fun readAtomicJournalLocked(
    file: File,
    errorCode: String,
    maximumBytes: Long
): AtomicJournalRecord? {
    require(maximumBytes in 1..Int.MAX_VALUE.toLong())
    val base = file.canonicalFile
    check(base.parentFile == file.parentFile?.canonicalFile && ATOMIC_JOURNAL_NAME.matches(base.name)) {
        errorCode
    }
    val backup = File(base.path + ".bak")
    val pending = File(base.path + ".new")
    val companions = listOf(base, backup, pending)
    if (companions.all { java.nio.file.Files.notExists(it.toPath()) }) return null
    if (java.nio.file.Files.notExists(base.toPath()) && java.nio.file.Files.notExists(backup.toPath())) {
        check(pending.isFile && pending.delete()) { errorCode }
        check(companions.all { java.nio.file.Files.notExists(it.toPath()) }) { errorCode }
        return null
    }
    var byteLength = 0L
    val bytes = try {
        AtomicFile(base).openRead().use { input ->
            val size = input.channel.size()
            if (size !in 1..maximumBytes) throw AtomicJournalFormatException(errorCode)
            byteLength = size
            ByteArray(size.toInt()).also { buffer ->
                var offset = 0
                while (offset < buffer.size) {
                    val read = input.read(buffer, offset, buffer.size - offset)
                    if (read <= 0) throw java.io.IOException(errorCode)
                    offset += read
                }
                if (input.read() != -1) throw java.io.IOException(errorCode)
            }
        }
    } catch (error: AtomicJournalFormatException) { throw error }
    catch (error: Throwable) { throw IllegalStateException(errorCode, error) }
    val value = try { app.stemwise.readStrictJsonObject(bytes, maximumBytes) }
    catch (error: Exception) { throw AtomicJournalFormatException(errorCode, error) }
    return AtomicJournalRecord(base, value, byteLength)
}

internal fun requireAtomicJournalEnvelope(
    record: AtomicJournalRecord,
    errorCode: String,
    expectedSchemaVersion: Long = 2L
): AtomicJournalEnvelope {
    val value = record.value
    val transactionId = exactAtomicJournalString(value, "transactionId", errorCode)
    val envelope = AtomicJournalEnvelope(
        transactionId = transactionId,
        packageId = exactAtomicJournalString(value, "packageId", errorCode),
        operation = exactAtomicJournalString(value, "operation", errorCode),
        libraryId = exactAtomicJournalString(value, "libraryId", errorCode),
        treeUri = exactAtomicJournalString(value, "treeUri", errorCode),
        rootEpoch = exactAtomicJournalLong(value.opt("rootEpoch"))
            ?.takeIf { it > 0L } ?: error(errorCode),
        sequence = exactAtomicJournalLong(value.opt("sequence"))
            ?.takeIf { it >= 1L } ?: error(errorCode),
        state = exactAtomicJournalString(value, "state", errorCode),
        details = value.opt("details") as? JSONObject ?: error(errorCode)
    )
    check(exactAtomicJournalLong(value.opt("schemaVersion")) == expectedSchemaVersion) { errorCode }
    check(ATOMIC_JOURNAL_TOKEN.matches(transactionId)) { errorCode }
    check(record.file.name == "$transactionId.json") { errorCode }
    return envelope
}

internal fun deleteAtomicJournal(file: File, errorCode: String) {
    synchronized(atomicJournalIoLock(file)) {
        val base = file.canonicalFile
        check(base.parentFile == file.parentFile?.canonicalFile && ATOMIC_JOURNAL_NAME.matches(base.name)) { errorCode }
        AtomicFile(base).delete()
        check(listOf(base, File(base.path + ".bak"), File(base.path + ".new"))
            .all { java.nio.file.Files.notExists(it.toPath()) }) { errorCode }
    }
}

private fun exactAtomicJournalString(value: JSONObject, key: String, errorCode: String): String =
    (value.opt(key) as? String)?.takeIf(String::isNotBlank) ?: error(errorCode)

internal fun exactAtomicJournalLong(value: Any?): Long? = when (value) {
    is Byte -> value.toLong()
    is Short -> value.toLong()
    is Int -> value.toLong()
    is Long -> value
    else -> null
}

internal val ATOMIC_JOURNAL_TOKEN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
internal val ATOMIC_JOURNAL_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.json$")

private fun checkedAtomicJournalBaseName(
    child: File,
    canonicalDirectory: File,
    errorCode: String
): String? {
    val exact = child.canonicalFile
    check(exact.parentFile == canonicalDirectory) { errorCode }
    if (!exact.isFile) return null
    return when {
        ATOMIC_JOURNAL_NAME.matches(exact.name) -> exact.name
        exact.name.endsWith(".bak") &&
            ATOMIC_JOURNAL_NAME.matches(exact.name.removeSuffix(".bak")) ->
            exact.name.removeSuffix(".bak")
        exact.name.endsWith(".new") &&
            ATOMIC_JOURNAL_NAME.matches(exact.name.removeSuffix(".new")) ->
            exact.name.removeSuffix(".new")
        else -> null
    }
}

/** Only complete bytes that fail parsing/validation can be quarantined; unavailable reads stay retryable. */
internal class AtomicJournalFormatException(code: String, cause: Throwable? = null) : IllegalStateException(code, cause)

internal fun quarantineMalformedPlaybackRecord(file: File) {
    val target = File(file.parentFile, "${file.name}.invalid-${java.util.UUID.randomUUID()}")
    java.nio.file.Files.move(file.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
}
