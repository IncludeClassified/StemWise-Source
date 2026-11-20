package app.stemwise.enrichment

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.storage.StorageManager
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import app.stemwise.data.LibraryRootManager
import app.stemwise.data.RootAuthority
import app.stemwise.data.RootHandle
import app.stemwise.data.authority
import app.stemwise.data.PackageMutationCoordinator
import app.stemwise.data.SourceBindingRegistry
import app.stemwise.data.SelectedLibraryContract
import app.stemwise.data.CanonicalJson
import app.stemwise.data.checkedPrivateChildren
import app.stemwise.data.cleanupExactPrivateChildren
import app.stemwise.data.deleteExactPrivateTree
import app.stemwise.data.measureSelectedLibraryDocument
import app.stemwise.data.parsePortableAuthorityCopies
import app.stemwise.data.readBoundedProviderDocument
import app.stemwise.data.withCheckedPrivateCleanup
import app.stemwise.data.canonicalMediaMimeMatches
import app.stemwise.data.exactAtomicJournalLong
import app.stemwise.data.inspectPreparedSourceAudio
import app.stemwise.data.inspectOriginalAudio
import app.stemwise.data.CanonicalPackagePublisher
import app.stemwise.integration.withCancellableProviderAssetFileDescriptor
import app.stemwise.integration.withCancellableProviderOutput
import app.stemwise.integration.withCancellableProviderInput
import app.stemwise.integration.runCancellableBlockingOperation
import app.stemwise.playback.BackgroundWorkCoordinator
import app.stemwise.suspendResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

private const val MAX_PNG_BYTES = 16L * 1024L * 1024L
private const val MAX_PNG_EDGE = 4_096
private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
)

private fun readBigEndianInt(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xff) shl 24) or
        ((bytes[offset + 1].toInt() and 0xff) shl 16) or
        ((bytes[offset + 2].toInt() and 0xff) shl 8) or
        (bytes[offset + 3].toInt() and 0xff)


internal fun validateEnrichmentCoverPng(bytes: ByteArray) {
    if (bytes.size.toLong() !in 1..MAX_PNG_BYTES) throw EnrichmentImportException("PNG_TOO_LARGE")
    if (bytes.size < 33 || !bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) {
        throw EnrichmentImportException("PNG_INVALID")
    }
    if (String(bytes, 12, 4, Charsets.US_ASCII) != "IHDR") throw EnrichmentImportException("PNG_INVALID")
    val width = readBigEndianInt(bytes, 16)
    val height = readBigEndianInt(bytes, 20)
    if (width !in 1..MAX_PNG_EDGE || height !in 1..MAX_PNG_EDGE) {
        throw EnrichmentImportException("PNG_DIMENSIONS_INVALID")
    }
    var offset = 8
    var foundEnd = false
    while (offset + 12 <= bytes.size) {
        val length = readBigEndianInt(bytes, offset)
        if (length < 0 || length > MAX_PNG_BYTES || offset + 12L + length > bytes.size) {
            throw EnrichmentImportException("PNG_INVALID")
        }
        val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
        if (type == "acTL") throw EnrichmentImportException("PNG_ANIMATED")
        offset += 12 + length
        if (type == "IEND") {
            foundEnd = true
            break
        }
    }
    if (!foundEnd || offset != bytes.size) throw EnrichmentImportException("PNG_INVALID")
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw EnrichmentImportException("PNG_INVALID")
    try {
        if (bitmap.width != width || bitmap.height != height) throw EnrichmentImportException("PNG_INVALID")
    } finally {
        bitmap.recycle()
    }
}


data class EnrichmentImportItem(
    val videoId: String?,
    val packageId: String?,
    val status: String,
    val errorCode: String? = null,
    val sourceName: String? = null
) {
    val outcome: EnrichmentImportOutcome
        get() = EnrichmentImportOutcome.fromWireValue(status)
    val isSuccess: Boolean
        get() = outcome.success
}

enum class EnrichmentImportOutcome(
    val wireValue: String,
    val success: Boolean
) {
    IMPORTED("imported", true),
    UPDATED("updated", true),
    UNCHANGED("unchanged", true),
    SUPERSEDED("superseded", true),
    UNMATCHED("unmatched", false),
    INVALID("invalid", false);

    companion object {
        fun fromWireValue(value: String): EnrichmentImportOutcome =
            entries.firstOrNull { it.wireValue == value } ?: INVALID
    }
}

data class EnrichmentImportResult(val items: List<EnrichmentImportItem>, internal val materializedBytes: Long = 0L) {
    val importedCount: Int get() = items.count {
        it.outcome == EnrichmentImportOutcome.IMPORTED ||
            it.outcome == EnrichmentImportOutcome.UPDATED
    }
    val updatedCount: Int get() = items.count {
        it.outcome == EnrichmentImportOutcome.UPDATED
    }
    val unchangedCount: Int get() = items.count {
        it.outcome == EnrichmentImportOutcome.UNCHANGED
    }
    val supersededCount: Int get() = items.count { it.outcome == EnrichmentImportOutcome.SUPERSEDED }
    val failedCount: Int get() = items.count { !it.isSuccess }
}

/**
 * A duplicate selected file is a no-op only after its deterministic representative succeeded.
 * Propagating a representative failure prevents a failed/unmatched import from being reported as
 * though the same revision were already installed.
 */
internal fun duplicateEnrichmentImportItem(
    videoId: String?,
    sourceName: String?,
    representative: EnrichmentImportItem?
): EnrichmentImportItem = when {
    representative == null -> EnrichmentImportItem(
        videoId = videoId,
        packageId = null,
        status = EnrichmentImportOutcome.INVALID.wireValue,
        errorCode = "ENRICHMENT_DUPLICATE_REPRESENTATIVE_MISSING",
        sourceName = sourceName
    )
    representative.isSuccess -> EnrichmentImportItem(
        videoId = videoId ?: representative.videoId,
        packageId = representative.packageId,
        status = EnrichmentImportOutcome.UNCHANGED.wireValue,
        sourceName = sourceName
    )
    else -> representative.copy(
        videoId = videoId ?: representative.videoId,
        sourceName = sourceName
    )
}

internal enum class EnrichmentArchiveShape { SINGLE_SONG, BULK }

internal fun classifyEnrichmentArchiveRoot(names: Set<String>): EnrichmentArchiveShape? = when {
    names.size == 3 && names.containsAll(setOf("song.json", "cover.png")) &&
        names.count(SelectedLibraryContract.originalFiles::contains) == 1 ->
        EnrichmentArchiveShape.SINGLE_SONG
    names.isNotEmpty() && names.all { it.lowercase(Locale.ROOT).endsWith(".zip") } ->
        EnrichmentArchiveShape.BULK
    else -> null
}

internal enum class EnrichmentRevisionDecision { APPLY, UNCHANGED, STALE, CONFLICT }

internal fun enrichmentGeneratedSections(json: JSONObject): Map<String, JSONObject> =
    listOf("metadata", "artwork", "lyrics", "analysis")
        .filter(json::has)
        .associateWith { key ->
            json.opt(key) as? JSONObject
                ?: throw EnrichmentImportException("MANIFEST_JSON_INVALID")
        }

internal fun decideEnrichmentRevision(
    installedState: String,
    installedUpdatedAt: String?,
    installedInputSha256: String?,
    incomingGeneratedAt: String,
    incomingInputSha256: String
): EnrichmentRevisionDecision {
    if (installedInputSha256 == incomingInputSha256) {
        return EnrichmentRevisionDecision.UNCHANGED
    }
    if (installedState != "enriched" || installedUpdatedAt.isNullOrBlank()) {
        return EnrichmentRevisionDecision.APPLY
    }
    val installedTime = runCatching { Instant.parse(installedUpdatedAt) }.getOrNull()
        ?: return EnrichmentRevisionDecision.CONFLICT
    val incomingTime = runCatching { Instant.parse(incomingGeneratedAt) }.getOrNull()
        ?: return EnrichmentRevisionDecision.CONFLICT
    return when {
        incomingTime.isAfter(installedTime) -> EnrichmentRevisionDecision.APPLY
        incomingTime.isBefore(installedTime) -> EnrichmentRevisionDecision.STALE
        else -> EnrichmentRevisionDecision.CONFLICT
    }
}

internal const val ENRICHMENT_ARTIFACT_MAX_BYTES = 16L * 1024L * 1024L

/** Strict JSON reader shared by enrichment preflight and focused device contract tests. */
internal fun parseStrictManifestJson(bytes: ByteArray): JSONObject {
    if (bytes.isEmpty() || bytes.size > ENRICHMENT_ARTIFACT_MAX_BYTES) {
        strictJsonFailure("MANIFEST_TOO_LARGE")
    }
    return try {
        app.stemwise.readStrictJsonObject(bytes, ENRICHMENT_ARTIFACT_MAX_BYTES)
    } catch (error: Exception) {
        strictJsonFailure(if (error.message == "JSON_UTF8_INVALID") "MANIFEST_UTF8_INVALID" else "MANIFEST_JSON_INVALID")
    }
}

internal data class PortableEnrichmentTransaction(
    val libraryId: String,
    val videoId: String,
    val transactionId: String,
    val hadOldPair: Boolean,
    val oldJsonSha256: String,
    val oldJsonBytes: Long,
    val oldCoverSha256: String,
    val oldCoverBytes: Long,
    val newJsonSha256: String,
    val newJsonBytes: Long,
    val newCoverSha256: String,
    val newCoverBytes: Long
)

private val PORTABLE_ENRICHMENT_TRANSACTION_KEYS = setOf(
    "schemaVersion",
    "kind",
    "libraryId",
    "videoId",
    "transactionId",
    "hadOldPair",
    "oldJsonSha256",
    "oldJsonBytes",
    "oldCoverSha256",
    "oldCoverBytes",
    "newJsonSha256",
    "newJsonBytes",
    "newCoverSha256",
    "newCoverBytes"
)
private val PORTABLE_ENRICHMENT_TRANSACTION_FILES = setOf(
    SelectedLibraryContract.ENRICHMENT_TRANSACTION,
    SelectedLibraryContract.ENRICHMENT_TRANSACTION_TEMP
)
private val PORTABLE_SHA256 = Regex("^[a-f0-9]{64}$")

internal fun PortableEnrichmentTransaction.toPortableJsonBytes(): ByteArray =
    JSONObject()
        .put("schemaVersion", 1)
        .put("kind", "stemwise.enrichment-transaction")
        .put("libraryId", libraryId)
        .put("videoId", videoId)
        .put("transactionId", transactionId)
        .put("hadOldPair", hadOldPair)
        .put("oldJsonSha256", oldJsonSha256)
        .put("oldJsonBytes", oldJsonBytes)
        .put("oldCoverSha256", oldCoverSha256)
        .put("oldCoverBytes", oldCoverBytes)
        .put("newJsonSha256", newJsonSha256)
        .put("newJsonBytes", newJsonBytes)
        .put("newCoverSha256", newCoverSha256)
        .put("newCoverBytes", newCoverBytes)
        .toString()
        .toByteArray(Charsets.UTF_8)

internal fun parsePortableEnrichmentTransaction(
    bytes: ByteArray
): PortableEnrichmentTransaction {
    check(bytes.size in 1..PORTABLE_ENRICHMENT_TRANSACTION_MAX_BYTES.toInt()) {
        "RECOVERY_PENDING"
    }
    val value = parseStrictManifestJson(bytes)
    check(value.keys().asSequence().toSet() == PORTABLE_ENRICHMENT_TRANSACTION_KEYS) {
        "RECOVERY_PENDING"
    }
    check(
        exactAtomicJournalLong(value.opt("schemaVersion")) == 1L &&
            value.opt("kind") == "stemwise.enrichment-transaction"
    ) { "RECOVERY_PENDING" }
    val transaction = PortableEnrichmentTransaction(
        libraryId = (value.opt("libraryId") as? String).orEmpty(),
        videoId = (value.opt("videoId") as? String).orEmpty(),
        transactionId = (value.opt("transactionId") as? String).orEmpty(),
        hadOldPair = value.opt("hadOldPair") as? Boolean ?: error("RECOVERY_PENDING"),
        oldJsonSha256 = (value.opt("oldJsonSha256") as? String).orEmpty(),
        oldJsonBytes = exactAtomicJournalLong(value.opt("oldJsonBytes"))
            ?: error("RECOVERY_PENDING"),
        oldCoverSha256 = (value.opt("oldCoverSha256") as? String).orEmpty(),
        oldCoverBytes = exactAtomicJournalLong(value.opt("oldCoverBytes"))
            ?: error("RECOVERY_PENDING"),
        newJsonSha256 = (value.opt("newJsonSha256") as? String).orEmpty(),
        newJsonBytes = exactAtomicJournalLong(value.opt("newJsonBytes"))
            ?: error("RECOVERY_PENDING"),
        newCoverSha256 = (value.opt("newCoverSha256") as? String).orEmpty(),
        newCoverBytes = exactAtomicJournalLong(value.opt("newCoverBytes"))
            ?: error("RECOVERY_PENDING")
    )
    check(
        runCatching { UUID.fromString(transaction.libraryId) }.isSuccess &&
            SelectedLibraryContract.isVideoId(transaction.videoId) &&
            runCatching { UUID.fromString(transaction.transactionId) }.isSuccess &&
            transaction.newJsonSha256.matches(PORTABLE_SHA256) &&
            transaction.newCoverSha256.matches(PORTABLE_SHA256) &&
            transaction.newJsonBytes in 1..ENRICHMENT_ARTIFACT_MAX_BYTES &&
            transaction.newCoverBytes in 1..MAX_PNG_BYTES
    ) { "RECOVERY_PENDING" }
    check(
        if (transaction.hadOldPair) {
            transaction.oldJsonSha256.matches(PORTABLE_SHA256) &&
                transaction.oldCoverSha256.matches(PORTABLE_SHA256) &&
                transaction.oldJsonBytes in 1..ENRICHMENT_ARTIFACT_MAX_BYTES &&
                transaction.oldCoverBytes in 1..MAX_PNG_BYTES
        } else {
            transaction.oldJsonSha256.isEmpty() && transaction.oldJsonBytes == 0L &&
                transaction.oldCoverSha256.isEmpty() && transaction.oldCoverBytes == 0L
        }
    ) { "RECOVERY_PENDING" }
    return transaction
}

internal fun selectPortableEnrichmentTransaction(
    copies: Map<String, ByteArray>
): PortableEnrichmentTransaction? = selectPortableEnrichmentTransactionCopies(
    copies,
    ::parsePortableEnrichmentTransaction
)

internal fun <T> selectPortableEnrichmentTransactionCopies(
    copies: Map<String, ByteArray>,
    parse: (ByteArray) -> T
): T? {
    check(copies.keys.all { it in PORTABLE_ENRICHMENT_TRANSACTION_FILES }) {
        "RECOVERY_PENDING"
    }
    val parsed = parsePortableAuthorityCopies(
        copies,
        "RECOVERY_PENDING",
        parse = parse
    ).map { it.second }
    if (parsed.isEmpty()) return null
    check(parsed.drop(1).all { it == parsed.first() }) { "RECOVERY_PENDING" }
    return parsed.first().takeIf {
        SelectedLibraryContract.ENRICHMENT_TRANSACTION in copies
    }
}

internal const val PORTABLE_ENRICHMENT_TRANSACTION_MAX_BYTES = 16L * 1024L

private fun strictJsonFailure(code: String): Nothing = throw EnrichmentImportException(code)

/**
 * Complete PC song ZIP intake, including exactly one Original and all enrichment sections.
 * Matching remains by exact video ID, with native recording bytes independently bound.
 */
class SongEnrichmentImporter(
    private val context: Context,
    private val roots: LibraryRootManager,
    private val mutations: PackageMutationCoordinator,
    private val sourceBindings: SourceBindingRegistry,
    private val backgroundWorkCoordinator: BackgroundWorkCoordinator,
    private val originalPublisher: () -> CanonicalPackagePublisher = {
        error("ORIGINAL_IMPORT_UNAVAILABLE")
    },
    private val commitCheckpoint: (String) -> Unit = {}
) {
    private val scratchLifetime = Mutex()
    @Volatile internal var bundleCommittedForTest: (() -> Unit)? = null
    @Volatile internal var bundleArchiveCopyForTest: (() -> Unit)? = null
    @Volatile internal var privateStorageAvailableForTest: ((File) -> Long)? = null

    /**
     * Imports one or several selected inputs as one preflighted operation.
     * Accepts a complete single-song ZIP or a bulk ZIP containing complete song ZIPs.
     */
    suspend fun importStagedFiles(
        archives: List<File>,
        sourceNames: List<String?> = emptyList(),
        expectedRoot: RootAuthority? = null,
        expectedVideoId: String? = null,
        onPackageChanged: (String) -> Unit = {},
        onProgress: suspend (completed: Int, total: Int, phase: String) -> Unit = { _, _, _ -> }
    ): Result<EnrichmentImportResult> = withContext(Dispatchers.IO) {
        scratchLifetime.withLock {
            try {
                val admittedRoot = requireRootAuthority(expectedRoot)
                if (archives.isEmpty() || archives.size > MAX_SONG_INTAKE_ARCHIVES) {
                    fail("ZIP_ENTRY_LIMIT")
                }
                var selectedBytes = 0L
                archives.forEach { archive ->
                    backgroundWorkCoordinator.checkpoint()
                    if (!archive.isFile || archive.length() !in 1..MAX_SONG_INTAKE_BYTES) {
                        fail("ZIP_TOO_LARGE")
                    }
                    selectedBytes = Math.addExact(selectedBytes, archive.length())
                }
                if (selectedBytes > MAX_SONG_INTAKE_BYTES) {
                    fail("ZIP_TOO_LARGE")
                }
                val scratch = stagingDirectory(UUID.randomUUID().toString())
                val imported = withCheckedPrivateCleanup(
                    cleanup = { cleanupTransactionScratch(scratch) }
                ) {
                    importArchives(
                        archives.mapIndexed { index, archive ->
                            SelectedArchive(
                                file = archive,
                                sourceName = sourceNames.getOrNull(index)
                                    ?.takeIf(String::isNotBlank)
                                    ?: archive.name
                            )
                        },
                        scratch,
                        admittedRoot,
                        expectedVideoId,
                        onPackageChanged,
                        onProgress
                    )
                }
                Result.success(imported)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Result.failure(error)
            }
        }
    }

    suspend fun importPcSong(
        archive: File,
        expectedVideoId: String,
        expectedRoot: RootAuthority,
        onProgress: suspend (Int, Int, String) -> Unit = { _, _, _ -> }
    ): Result<EnrichmentImportResult> = importStagedFiles(
        listOf(archive), expectedRoot = expectedRoot, expectedVideoId = expectedVideoId,
        onProgress = onProgress
    )

    private fun requireRootAuthority(expected: RootAuthority? = null): RootAuthority {
        val current = roots.captureHandle()?.authority() ?: fail("ROOT_UNAVAILABLE")
        if (expected != null) {
            roots.requireSameLibrary(expected)
            if (current != expected) fail("LIBRARY_CHANGED")
        }
        return expected ?: current
    }

    private fun stagingDirectory(transactionId: String): File =
        File(importerScratchRoot(), transactionId).apply {
            check(IMPORT_TRANSACTION_ID.matches(transactionId)) {
                "ENRICHMENT_SCRATCH_OWNERSHIP_INVALID"
            }
            if (!mkdirs() && !isDirectory) fail("EXTERNAL_COMMIT_FAILED")
        }

    /** Cold-open/root-switch barrier for reproducible importer transactions. */
    suspend fun cleanupOrphanScratch() = withContext(Dispatchers.IO) {
        scratchLifetime.withLock {
            val cache = context.cacheDir.canonicalFile
            val root = importerScratchRoot().canonicalFile
            check(root.parentFile == cache) { "ENRICHMENT_SCRATCH_CLEANUP_REFUSED" }
            cleanupExactPrivateChildren(root, "ENRICHMENT_SCRATCH_CLEANUP_FAILED") { child ->
                child.isDirectory && IMPORT_TRANSACTION_ID.matches(child.name)
            }
            if (root.exists()) check(root.delete()) { "ENRICHMENT_SCRATCH_CLEANUP_FAILED" }
        }
    }

    private fun importerScratchRoot(): File = File(context.cacheDir, "enrichment")

    private fun cleanupTransactionScratch(directory: File) {
        val root = importerScratchRoot().canonicalFile
        val target = directory.canonicalFile
        check(target.parentFile == root && IMPORT_TRANSACTION_ID.matches(target.name)) {
            "ENRICHMENT_SCRATCH_CLEANUP_REFUSED"
        }
        deleteExactPrivateTree(root, target, "ENRICHMENT_SCRATCH_CLEANUP_FAILED")
        if (
            root.exists() && checkedPrivateChildren(
                root,
                "ENRICHMENT_SCRATCH_ENUMERATION_FAILED"
            ).isEmpty()
        ) {
            check(root.delete()) { "ENRICHMENT_SCRATCH_CLEANUP_FAILED" }
        }
    }

    private fun deleteScratchEntry(root: File, target: File) {
        if (target.exists()) {
            deleteExactPrivateTree(root, target, "ENRICHMENT_SCRATCH_CLEANUP_FAILED")
        }
    }

    private suspend fun importArchives(
        archives: List<SelectedArchive>,
        staging: File,
        expectedRoot: RootAuthority,
        expectedVideoId: String?,
        onPackageChanged: (String) -> Unit,
        onProgress: suspend (completed: Int, total: Int, phase: String) -> Unit
    ): EnrichmentImportResult {
        val workItems = mutableListOf<ArchiveItem>()
        archives.forEach { selected ->
            backgroundWorkCoordinator.checkpoint()
            if (workItems.size >= MAX_SONG_INTAKE_ARCHIVES) fail("ZIP_ENTRY_LIMIT")
            try {
                val names = inspectRoot(selected.file, MAX_SONG_INTAKE_ARCHIVES - workItems.size)
                val shape = classifyEnrichmentArchiveRoot(names) ?: fail("ZIP_FORMAT_UNRECOGNIZED")
                if (shape == EnrichmentArchiveShape.SINGLE_SONG) {
                    workItems += ArchiveItem(selected.file, null, selected.sourceName)
                } else {
                    names.forEach { entryName -> workItems += ArchiveItem(selected.file, entryName, entryName) }
                }
            } catch (error: Exception) {
                if (error is CancellationException || error is ExtractionBudgetExceeded ||
                    app.stemwise.data.isImportStorageFailure(error) ||
                    error is java.io.IOException && error !is EnrichmentImportException && error !is java.util.zip.ZipException) throw error
                workItems += ArchiveItem(selected.file, null, selected.sourceName, error)
            }
        }
        val total = workItems.size
        if (total !in 1..MAX_SONG_INTAKE_ARCHIVES) fail("ZIP_ENTRY_LIMIT")

        val scratch = File(staging, "selected-child.zip")
        val preflight = mutableListOf<PreflightItem>()
        val validationBudget = ExtractionBudget(MAX_SONG_INTAKE_BYTES)
        onProgress(0, total, "Validating")
        val validationReaders = mutableMapOf<File, ZipFile>()
        try {
            workItems.forEachIndexed { index, item ->
                backgroundWorkCoordinator.checkpoint()
                roots.requireSameLibrary(expectedRoot)
                val identity = suspendResult {
                    item.rootFailure?.let { throw it }
                    val materialized = materializeArchiveItem(
                            item,
                            File(staging, "candidate-$index.zip"),
                            validationBudget,
                            validationReaders
                        )
                    readValidatedCandidate(materialized, File(staging, "payload-$index"), validationBudget)
                }
                identity.exceptionOrNull()?.let {
                    if (it is ExtractionBudgetExceeded || app.stemwise.data.isImportStorageFailure(it) ||
                        it is java.io.IOException && it !is EnrichmentImportException && it !is java.util.zip.ZipException) throw it
                }
                preflight += PreflightItem(
                    sourceName = item.sourceName,
                    candidate = identity.getOrNull(),
                    errorCode = identity.exceptionOrNull()?.let(::errorCode),
                    validationFailure = identity.exceptionOrNull()
                )
                onProgress(preflight.size, total, "Validating")
            }
        } finally {
            validationReaders.values.forEach { runCatching(it::close) }
            deleteScratchEntry(staging, scratch)
        }
        check(preflight.size == total) { "ZIP_ENTRY_LIMIT" }
        if (expectedVideoId != null) {
            preflight.firstOrNull { it.errorCode != null }?.let {
                val failure = checkNotNull(it.validationFailure)
                throw if (failure is EnrichmentImportException) failure
                    else EnrichmentImportException(checkNotNull(it.errorCode)).apply { initCause(failure) }
            }
            requirePcSongIdentity(preflight.map { it.videoId }, expectedVideoId)
        }
        applyBatchRevisionPlan(preflight)
        val results = MutableList<EnrichmentImportItem?>(total) { null }
        onProgress(0, total, "Applying")
        try {
            batchApplicationOrder(preflight).forEachIndexed { completed, itemIndex ->
                backgroundWorkCoordinator.checkpoint()
                val checked = preflight[itemIndex]
                val item = workItems[itemIndex]
                val payloadDirectory = File(staging, "payload-$itemIndex")
                results[itemIndex] = when {
                    checked.errorCode != null -> checked.invalidResult()
                    checked.disposition == EnrichmentBatchRevisionDisposition.SUPERSEDED -> {
                        val winner = preflight.indices.firstOrNull { candidate ->
                            preflight[candidate].videoId == checked.videoId &&
                                preflight[candidate].disposition == EnrichmentBatchRevisionDisposition.APPLY
                        }?.let(results::get)
                        if (winner?.isSuccess == true) EnrichmentImportItem(checked.videoId, null,
                            EnrichmentImportOutcome.SUPERSEDED.wireValue, sourceName = checked.sourceName)
                        else EnrichmentImportItem(checked.videoId, null, "invalid",
                            winner?.errorCode ?: "ENRICHMENT_REPLACEMENT_UNAVAILABLE", checked.sourceName)
                    }
                    checked.disposition == EnrichmentBatchRevisionDisposition.DUPLICATE ->
                        checked.duplicateResult(
                            checked.duplicateOfIndex?.let(results::get)
                        )
                    else -> suspendResult {
                        importOne(checkNotNull(checked.candidate), checked.sourceName, expectedRoot, expectedVideoId, onPackageChanged)
                    }.getOrElse { error ->
                        if (error is ExtractionBudgetExceeded) throw error
                        EnrichmentImportItem(
                            checked.videoId,
                            null,
                            "invalid",
                            errorCode(error),
                            checked.sourceName
                        )
                    }
                }
                if (results[itemIndex]?.outcome in setOf(EnrichmentImportOutcome.IMPORTED, EnrichmentImportOutcome.UPDATED)) {
                    bundleCommittedForTest?.invoke()
                }
                if (item.entryName != null) deleteScratchEntry(staging, File(staging, "candidate-$itemIndex.zip"))
                deleteScratchEntry(staging, payloadDirectory)
                onProgress(completed + 1, total, "Applying")
            }
        } finally {
            deleteScratchEntry(staging, scratch)
        }
        return EnrichmentImportResult(results.map(::requireNotNull), validationBudget.expandedBytes)
    }

    private suspend fun materializeArchiveItem(
        item: ArchiveItem,
        scratch: File,
        extractionBudget: ExtractionBudget,
        readers: MutableMap<File, ZipFile>
    ): File {
        val entryName = item.entryName ?: return item.archive
        deleteScratchEntry(checkNotNull(scratch.parentFile), scratch)
        val zip = readers.getOrPut(item.archive) { ZipFile(item.archive) }
        val entry = zip.getEntry(entryName)
            ?.takeUnless(ZipEntry::isDirectory)
            ?: fail("INNER_ZIP_INVALID")
        if (safeRootName(entry) != entryName) fail("INNER_ZIP_INVALID")
        if (entry.size !in 1..MAX_SONG_BUNDLE_BYTES) fail("ZIP_TOO_LARGE")
        app.stemwise.data.requireImportStorageCapacity(allocatableBytes(scratch.parentFile!!),
            entry.size, "STORAGE_RESERVE_LOW")
        val copied = zip.getInputStream(entry).buffered().use { input ->
            scratch.outputStream().buffered().use { output ->
                copyBounded(input, output, MAX_SONG_BUNDLE_BYTES, scratch.parentFile, extractionBudget = extractionBudget)
            }
        }
        if (copied <= 0L) fail("INNER_ZIP_INVALID")
        return scratch
    }

    private suspend fun importOne(
        candidate: ValidatedCandidate,
        sourceName: String,
        expectedRoot: RootAuthority,
        expectedVideoId: String?,
        onPackageChanged: (String) -> Unit
    ): EnrichmentImportItem {
        val payload = readValidatedPayload(candidate)
        var stagedBundle: Pair<RootHandle, BundleStage>? = null
        return try {
            expectedVideoId?.let { requirePcSongIdentity(listOf(payload.videoId), it) }
            val root = roots.captureHandle()?.takeIf { it.authority() == expectedRoot }
                ?: fail("LIBRARY_CHANGED")
            onPackageChanged(payload.videoId)
            val bundle = stageBundle(candidate.archive, payload, root)
            stagedBundle = root to bundle
            payload.original.let { original ->
                originalPublisher().publishOriginal(root, payload.videoId, original,
                    payload.originalIdentity.sha256, beforeOriginalPublication = {
                        bundle.journal.write("BUNDLE_STAGED")
                    }).getOrThrow()
            }
            commitCheckpoint("BUNDLE_ORIGINAL_PUBLISHED")
            val result = commit(payload, expectedRoot).copy(sourceName = sourceName)
            verifyAndRetireBundle(root, bundle.transactionId, payload.videoId,
                enrichmentRevisionIdentity(payload.enrichment, sha256(payload.coverBytes)), payload.originalIdentity.sha256)
            result
        } catch (cancelled: CancellationException) {
            // A rolled-back update must not become a new accepted update on the next cold open.
            withContext(NonCancellable) {
                stagedBundle?.let { (root, bundle) ->
                    suspendResult { retireCancelledBundle(root, bundle.transactionId, payload.videoId) }
                        .exceptionOrNull()?.let(cancelled::addSuppressed)
                }
            }
            throw cancelled
        } catch (error: Throwable) {
            Log.w("StemWiseImport", "Could not apply enrichment payload $sourceName", error)
            val code = commitErrorCode(error)
            if (code in BUNDLE_TERMINAL_REJECTIONS) {
                stagedBundle?.let { (root, bundle) ->
                    suspendResult { retireRejectedBundle(root, bundle.transactionId, payload.videoId, payload.originalIdentity.sha256) }
                        .exceptionOrNull()?.let(error::addSuppressed)
                }
            }
            EnrichmentImportItem(
                payload.videoId,
                null,
                if (code == "SONG_NOT_FOUND") "unmatched" else "invalid",
                code,
                sourceName
            )
        }
    }

    private fun bundleArchive(transactionId: String): File {
        check(transactionId.matches(Regex("^bundle-[a-f0-9]{64}$"))) { "BUNDLE_RECOVERY_REQUIRED" }
        return File(context.filesDir, "song-bundle-intake/$transactionId.zip").canonicalFile.also {
            check(it.parentFile == File(context.filesDir, "song-bundle-intake").canonicalFile) { "BUNDLE_RECOVERY_REQUIRED" }
        }
    }

    private suspend fun archiveSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHexString()
    }

    private data class BundleStage(val transactionId: String, val journal: PackageMutationCoordinator.Journal)
    private data class VerifiedStagedArchive(val file: File, val sha256: String, val size: Long, val source: String?)

    /** Only these durable intake owners keep their bytes until publication or explicit cleanup. */
    private fun borrowedArchiveSource(file: File): String? {
        val canonical = file.canonicalFile
        val bridge = File(context.noBackupFilesDir, "pc-song-jobs").canonicalFile
        val manual = File(context.noBackupFilesDir, "import-staging").canonicalFile
        return when {
            canonical.parentFile == bridge && Regex("[a-f0-9]{32}\\.zip").matches(canonical.name) -> "bridge/${canonical.name}"
            canonical.parentFile == manual && canonical.extension in setOf("zip", "json") &&
                isEnrichmentStagingName(canonical.name) -> "manual/${canonical.name}"
            else -> null
        }
    }

    private fun borrowedArchiveFile(source: String): File {
        val kind = source.substringBefore('/')
        val name = source.substringAfter('/', "")
        val root = when (kind) {
            "bridge" -> File(context.noBackupFilesDir, "pc-song-jobs")
            "manual" -> File(context.noBackupFilesDir, "import-staging")
            else -> fail("BUNDLE_RECOVERY_REQUIRED")
        }.canonicalFile
        val file = File(root, name).canonicalFile
        check(file.parentFile == root && borrowedArchiveSource(file) == source) { "BUNDLE_RECOVERY_REQUIRED" }
        return file
    }

    private fun bundleTransactionId(libraryId: String, treeUri: String, videoId: String, archiveHash: String): String =
        "bundle-" + sha256(("$libraryId|$treeUri|$videoId|$archiveHash").toByteArray(Charsets.UTF_8))

    private suspend fun archiveMatches(file: File, hash: String, size: Long?): Boolean =
        file.isFile && (size == null || file.length() == size) && archiveSha256(file) == hash

    private fun archiveSize(details: JSONObject): Long? = if (details.has("archiveSize")) {
        exactAtomicJournalLong(details.get("archiveSize"))?.takeIf { it in 1..MAX_SONG_INTAKE_BYTES }
            ?: fail("BUNDLE_RECOVERY_REQUIRED")
    } else null

    private fun archiveSource(details: JSONObject): File? = when (val source = details.opt("archiveSource")) {
        null, JSONObject.NULL -> null
        is String -> borrowedArchiveFile(source)
        else -> fail("BUNDLE_RECOVERY_REQUIRED")
    }

    private suspend fun bundleInput(transactionId: String, details: JSONObject): File? {
        val target = bundleArchive(transactionId)
        val source = archiveSource(details)
        val hash = details.getString("archiveSha256")
        val size = archiveSize(details)
        if (archiveMatches(target, hash, size)) return target
        if (source != null && archiveMatches(source, hash, size)) return source
        check(!target.exists() && source?.exists() != true) { "BUNDLE_RECOVERY_REQUIRED" }
        return null
    }

    private suspend fun copyBundleArchive(source: File, target: File, size: Long) {
        app.stemwise.data.requireImportStorageCapacity(allocatableBytes(target.parentFile!!), size, "STORAGE_RESERVE_LOW")
        source.inputStream().buffered().use { input ->
            java.io.FileOutputStream(target).use { output ->
                bundleArchiveCopyForTest?.invoke()
                copyBounded(input, output, MAX_SONG_BUNDLE_BYTES, target.parentFile)
                output.flush()
                output.fd.sync()
            }
        }
    }

    /** Cleanup hands accepted bytes to their existing transaction before releasing the intake owner. */
    internal suspend fun preserveBundleArchiveInputs(files: Collection<File>) = mutations.withRootLease({}) {
        val deleting = files.mapTo(hashSetOf()) { it.canonicalFile }
        if (deleting.isEmpty()) return@withRootLease
        val records = app.stemwise.data.checkedAtomicJournalRecords(File(context.filesDir, "operation-journals"), "BUNDLE_RECOVERY_REQUIRED")
        for (record in records) {
            val envelope = app.stemwise.data.requireAtomicJournalEnvelope(record, "BUNDLE_RECOVERY_REQUIRED")
            if (envelope.operation != "SONG_BUNDLE_IMPORT_V1" || envelope.state == "COMPLETE") continue
            val owner = RootAuthority(envelope.libraryId, envelope.treeUri, envelope.rootEpoch)
            mutations.withPackageRead(owner, envelope.packageId) {
                val current = app.stemwise.data.readAtomicJournal(record.file, "BUNDLE_RECOVERY_REQUIRED") ?: return@withPackageRead
                val envelope = app.stemwise.data.requireAtomicJournalEnvelope(current, "BUNDLE_RECOVERY_REQUIRED")
                check(envelope.libraryId == owner.libraryId && envelope.treeUri == owner.treeUri &&
                    envelope.rootEpoch == owner.epoch && envelope.packageId == record.value.getString("packageId")) { "BUNDLE_RECOVERY_REQUIRED" }
                if (envelope.state == "COMPLETE") return@withPackageRead
                val source = archiveSource(envelope.details)?.takeIf { it in deleting } ?: return@withPackageRead
                val hash = envelope.details.getString("archiveSha256")
                check(bundleTransactionId(envelope.libraryId, envelope.treeUri, envelope.packageId, hash) == envelope.transactionId) {
                    "BUNDLE_RECOVERY_REQUIRED"
                }
                val size = archiveSize(envelope.details) ?: fail("BUNDLE_RECOVERY_REQUIRED")
                val target = bundleArchive(envelope.transactionId)
                if (!source.exists()) {
                    check(archiveMatches(target, hash, size)) { "BUNDLE_RECOVERY_REQUIRED" }
                    return@withPackageRead
                }
                check(archiveMatches(source, hash, size)) { "BUNDLE_RECOVERY_REQUIRED" }
                check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "BUNDLE_STORAGE_UNAVAILABLE" }
                if (target.exists()) {
                    check(target.isFile) { "BUNDLE_RECOVERY_REQUIRED" }
                    if (archiveMatches(target, hash, size)) {
                        check(source.delete()) { "BUNDLE_CLEANUP_PENDING" }
                        return@withPackageRead
                    }
                    check(target.delete()) { "BUNDLE_CLEANUP_PENDING" }
                }
                try {
                    java.nio.file.Files.move(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                } catch (error: java.nio.file.FileSystemException) {
                    // A provider/volume boundary needs independent bytes; the source survives every failed copy.
                    check(source.exists() && !target.exists()) { "BUNDLE_RECOVERY_REQUIRED" }
                    copyBundleArchive(source, target, size)
                    check(archiveMatches(target, hash, size)) { "BUNDLE_RECOVERY_REQUIRED" }
                    check(source.delete()) { "BUNDLE_CLEANUP_PENDING" }
                }
                check(archiveMatches(target, hash, size)) { "BUNDLE_RECOVERY_REQUIRED" }
            }
        }
    }

    private suspend fun stageBundle(verified: VerifiedStagedArchive, payload: Payload, root: RootHandle): BundleStage {
        val archiveHash = verified.sha256
        val transactionId = bundleTransactionId(root.libraryId, root.treeUri.toString(), payload.videoId, archiveHash)
        val target = bundleArchive(transactionId)
        val stagedJournal = mutations.withPackageMutation(root, payload.videoId, "SONG_BUNDLE_IMPORT_V1", transactionId,
            requireExactRoot = { roots.requireSameLibrary(root) }, holdRootLock = false) { journal ->
            if (verified.source == null) {
                check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "BUNDLE_STORAGE_UNAVAILABLE" }
                if (target.exists() && archiveSha256(target) != archiveHash) {
                    check(journal.wasRecoveredInState("PREPARED") && target.delete()) { "BUNDLE_RECOVERY_REQUIRED" }
                }
                if (!target.exists()) copyBundleArchive(verified.file, target, verified.size)
                check(archiveMatches(target, archiveHash, verified.size)) { "BUNDLE_RECOVERY_REQUIRED" }
            } else {
                check(archiveMatches(verified.file, archiveHash, verified.size)) { "BUNDLE_RECOVERY_REQUIRED" }
                java.io.FileOutputStream(verified.file, true).use { it.fd.sync() }
            }
            val state = if (journal.wasRecoveredInState("BUNDLE_STAGED")) "BUNDLE_STAGED" else "BUNDLE_PREPARED"
            journal.write(state, JSONObject().put("archiveSha256", archiveHash).put("archiveSize", verified.size)
                .put("archiveSource", verified.source ?: JSONObject.NULL)
                .put("videoId", payload.videoId)
                .put("payloadSha256", enrichmentRevisionIdentity(payload.enrichment, sha256(payload.coverBytes)))
                .put("originalSha256", payload.originalIdentity.sha256))
            journal
        }
        return BundleStage(transactionId, stagedJournal)
    }

    private suspend fun verifyAndRetireBundle(root: RootHandle, transactionId: String, videoId: String,
        payloadHash: String, originalHash: String) {
        commitCheckpoint("BUNDLE_BEFORE_VERIFICATION")
        originalPublisher().verifyPublishedSong(root, videoId, originalHash)
        mutations.withPackageMutation(root, videoId, "SONG_BUNDLE_IMPORT_V1", transactionId,
            requireExactRoot = { roots.requireSameLibrary(root) }) { journal ->
            val manifest = roots.checkedAuthoritativeSongSnapshot(root, videoId, requireDurableIdentity = true)
                ?.file(SelectedLibraryContract.ENRICHMENT) ?: fail("BUNDLE_READBACK_FAILED")
            val bytes = readDocument(manifest.document, MAX_MANIFEST_BYTES)
            val installed = parseStrictJson(bytes)
            val install = installed.getJSONObject("install")
            val samePayload = install.getString("payloadSha256") == payloadHash
            val verifiedRevision = journal.recoveredLong("BUNDLE_VERIFIED", "verifiedContentRevision")
            val verifiedAt = journal.recoveredString("BUNDLE_VERIFIED", "verifiedGeneratedAt")
            check(samePayload || verifiedRevision != null && verifiedAt != null &&
                install.getLong("contentRevision") > verifiedRevision &&
                Instant.parse(installed.getString("generatedAt")) > Instant.parse(verifiedAt)) { "BUNDLE_READBACK_FAILED" }
            check(sourceBindings.activePackageIdsBySource(root)["youtube-video:$videoId"] == videoId) { "BUNDLE_READBACK_FAILED" }
            journal.write("BUNDLE_VERIFIED", JSONObject().put("verifiedContentRevision", install.getLong("contentRevision"))
                .put("verifiedGeneratedAt", installed.getString("generatedAt")))
            commitCheckpoint("BUNDLE_VERIFIED")
            val archive = bundleArchive(transactionId)
            check(!archive.exists() || archive.delete()) { "BUNDLE_CLEANUP_PENDING" }
            journal.complete()
        }
    }

    private suspend fun retireCancelledBundle(root: RootHandle, transactionId: String, videoId: String) {
        mutations.withPackageMutation(root, videoId, "SONG_BUNDLE_IMPORT_V1", transactionId,
            requireExactRoot = { roots.requireSameLibrary(root) }) { journal ->
            if (journal.wasRecoveredInState("BUNDLE_VERIFIED") || journal.wasRecoveredInState("COMPLETE")) return@withPackageMutation
            // Cancellation retires intake authority even when only the preserved Original exists.
            journal.write("BUNDLE_CANCELLED")
            val archive = bundleArchive(transactionId)
            check(!archive.exists() || archive.delete()) { "BUNDLE_CLEANUP_PENDING" }
            journal.complete()
        }
    }

    internal suspend fun cancelBundleArchiveInputs(root: RootAuthority, files: Collection<File>) {
        val cancelling = files.mapTo(hashSetOf()) { it.canonicalFile }
        for (record in app.stemwise.data.checkedAtomicJournalRecords(File(context.filesDir, "operation-journals"), "BUNDLE_RECOVERY_REQUIRED")) {
            val envelope = app.stemwise.data.requireAtomicJournalEnvelope(record, "BUNDLE_RECOVERY_REQUIRED")
            if (envelope.operation != "SONG_BUNDLE_IMPORT_V1" || envelope.libraryId != root.libraryId ||
                envelope.treeUri != root.treeUri || envelope.rootEpoch != root.epoch ||
                envelope.state !in setOf("PREPARED", "BUNDLE_PREPARED", "BUNDLE_STAGED", "BUNDLE_CANCELLED") ||
                archiveSource(envelope.details)?.let { it in cancelling } != true) continue
            val handle = roots.captureHandle()?.takeIf { it.authority() == root } ?: fail("LIBRARY_CHANGED")
            retireCancelledBundle(handle, envelope.transactionId, envelope.packageId)
        }
    }

    private suspend fun retireRejectedBundle(root: RootHandle, transactionId: String, videoId: String, originalHash: String) {
        originalPublisher().verifyPublishedSong(root, videoId, originalHash)
        mutations.withPackageMutation(root, videoId, "SONG_BUNDLE_IMPORT_V1", transactionId,
            requireExactRoot = { roots.requireSameLibrary(root) }) { journal ->
            journal.write("BUNDLE_REJECTED")
            val archive = bundleArchive(transactionId)
            check(!archive.exists() || archive.delete()) { "BUNDLE_CLEANUP_PENDING" }
            journal.complete()
        }
    }

    /** Rebind only stopped bundle-owned transactions, before ordinary private/portable recovery. */
    internal suspend fun prepareBundleRecovery(root: RootHandle) = mutations.withRootMutation {
        roots.requireSameLibrary(root)
        val records = app.stemwise.data.checkedAtomicJournalRecords(File(context.filesDir, "operation-journals"), "BUNDLE_RECOVERY_REQUIRED")
        val decoded = records.map { it to app.stemwise.data.requireAtomicJournalEnvelope(it, "BUNDLE_RECOVERY_REQUIRED") }
        val bundles = decoded.filter { (_, envelope) -> envelope.operation == "SONG_BUNDLE_IMPORT_V1" &&
            envelope.libraryId == root.libraryId && envelope.treeUri == root.treeUri.toString() && envelope.rootEpoch != root.epoch }
        for ((_, bundle) in bundles) {
            check(bundle.rootEpoch < root.epoch && SelectedLibraryContract.isVideoId(bundle.packageId)) { "BUNDLE_RECOVERY_REQUIRED" }
            val owned = decoded.filter { (_, envelope) ->
                envelope.libraryId == bundle.libraryId && envelope.treeUri == bundle.treeUri &&
                    envelope.rootEpoch == bundle.rootEpoch && envelope.packageId == bundle.packageId &&
                    (envelope.operation in setOf("SONG_BUNDLE_IMPORT_V1", "ENRICHMENT_INSTALL_V2") ||
                        envelope.operation == "FLAT_ASSET_PUBLISH" &&
                        envelope.details.optString("fileName") in SelectedLibraryContract.originalFiles)
            }.sortedBy { (_, envelope) -> envelope.operation == "SONG_BUNDLE_IMPORT_V1" }
            // The bundle receipt moves last, so an interrupted rebind remains discoverable.
            for ((record, _) in owned) {
                roots.requireSameLibrary(root)
                val bytes = JSONObject(record.value.toString()).put("rootEpoch", root.epoch).toString().toByteArray(Charsets.UTF_8)
                val atomic = android.util.AtomicFile(record.file)
                val output = atomic.startWrite()
                try {
                    output.write(bytes)
                    atomic.finishWrite(output)
                } catch (error: Throwable) {
                    atomic.failWrite(output)
                    throw error
                }
            }
        }
    }

    internal suspend fun recoverIncompleteBundles(root: RootHandle) = withContext(Dispatchers.IO) {
        prepareBundleRecovery(root)
        val directory = File(context.filesDir, "operation-journals")
        for (record in app.stemwise.data.checkedAtomicJournalRecords(directory, "BUNDLE_RECOVERY_REQUIRED")) {
            val envelope = app.stemwise.data.requireAtomicJournalEnvelope(record, "BUNDLE_RECOVERY_REQUIRED")
            if (envelope.operation != "SONG_BUNDLE_IMPORT_V1" || envelope.libraryId != root.libraryId ||
                envelope.treeUri != root.treeUri.toString() || envelope.rootEpoch != root.epoch) continue
            roots.requireSameLibrary(root)
            val archive = bundleArchive(envelope.transactionId)
            when (envelope.state) {
                "PREPARED", "BUNDLE_PREPARED", "BUNDLE_CANCELLED", "COMPLETE" -> mutations.withPackageMutation(root, envelope.packageId,
                    envelope.operation, envelope.transactionId, requireExactRoot = { roots.requireSameLibrary(root) }) { journal ->
                    check(!archive.exists() || archive.delete()) { "BUNDLE_CLEANUP_PENDING" }
                    journal.complete()
                }
                "BUNDLE_VERIFIED" -> verifyAndRetireBundle(root, envelope.transactionId, envelope.packageId,
                    envelope.details.getString("payloadSha256"), envelope.details.getString("originalSha256"))
                "BUNDLE_REJECTED" -> retireRejectedBundle(root, envelope.transactionId, envelope.packageId,
                    envelope.details.getString("originalSha256"))
                "BUNDLE_STAGED" -> {
                    val input = bundleInput(envelope.transactionId, envelope.details)
                    if (input == null) {
                        // An identical concurrent intake may already have completed and retired its ZIP.
                        verifyAndRetireBundle(root, envelope.transactionId, envelope.packageId,
                            envelope.details.getString("payloadSha256"), envelope.details.getString("originalSha256"))
                    } else {
                        val result = importPcSong(input, envelope.packageId, root.authority()).getOrThrow()
                        val item = result.items.singleOrNull()
                        if (item?.isSuccess != true) {
                            check(item?.errorCode in BUNDLE_TERMINAL_REJECTIONS) { "BUNDLE_RECOVERY_REQUIRED: ${result.items}" }
                            // A later complete revision may have superseded this accepted input.
                            retireRejectedBundle(root, envelope.transactionId, envelope.packageId,
                                envelope.details.getString("originalSha256"))
                        } else {
                            verifyAndRetireBundle(root, envelope.transactionId, envelope.packageId,
                                envelope.details.getString("payloadSha256"), envelope.details.getString("originalSha256"))
                        }
                    }
                }
                else -> fail("BUNDLE_RECOVERY_REQUIRED")
            }
        }
    }

    private suspend fun inspectRoot(archive: File, remainingChildren: Int): Set<String> {
        var childCount = 0
        val names = linkedSetOf<String>()
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                backgroundWorkCoordinator.checkpoint()
                val entry = entries.nextElement()
                if (entry.isDirectory) fail("ZIP_UNEXPECTED_ENTRY")
                val name = safeRootName(entry)
                if (name.endsWith(".zip", ignoreCase = true) && ++childCount > remainingChildren) fail("ZIP_ENTRY_LIMIT")
                val collision = names.firstOrNull { it.equals(name, ignoreCase = true) }
                if (collision != null) fail("ZIP_DUPLICATE_ENTRY")
                names += name
                if (names.size > MAX_SONG_INTAKE_ARCHIVES) fail("ZIP_ENTRY_LIMIT")
            }
        }
        return names
    }

    private fun applyBatchRevisionPlan(items: List<PreflightItem>) {
        val candidates = batchRevisions(items)
        val dispositions = planEnrichmentBatchRevisions(candidates)
        dispositions.forEach { (index, disposition) ->
            val item = items[index]
            item.disposition = disposition
            if (disposition == EnrichmentBatchRevisionDisposition.CONFLICT) {
                item.errorCode = "ENRICHMENT_REVISION_CONFLICT"
            }
        }
        candidates.forEach { duplicate ->
            if (dispositions[duplicate.index] != EnrichmentBatchRevisionDisposition.DUPLICATE) {
                return@forEach
            }
            val representative = candidates.firstOrNull { candidate ->
                candidate.index != duplicate.index &&
                    dispositions[candidate.index] == EnrichmentBatchRevisionDisposition.APPLY &&
                    candidate.videoId == duplicate.videoId &&
                    Instant.parse(candidate.generatedAt) == Instant.parse(duplicate.generatedAt) &&
                    candidate.inputSha256 == duplicate.inputSha256
            }
            items[duplicate.index].duplicateOfIndex = representative?.index
            if (representative == null) {
                items[duplicate.index].errorCode =
                    "ENRICHMENT_DUPLICATE_REPRESENTATIVE_MISSING"
            }
        }
    }

    private fun batchApplicationOrder(items: List<PreflightItem>): List<Int> {
        val candidates = batchRevisions(items)
        val dispositions = items.indices.associateWith { items[it].disposition }
        val applying = enrichmentBatchApplicationOrder(candidates, dispositions)
        val applyingSet = applying.toHashSet()
        return applying + items.indices.filterNot(applyingSet::contains)
    }

    private fun batchRevisions(items: List<PreflightItem>): List<EnrichmentBatchRevision> =
        items.mapIndexedNotNull { index, item ->
            val candidate = item.candidate ?: return@mapIndexedNotNull null
            if (item.errorCode != null) null else EnrichmentBatchRevision(index, item.sourceName,
                candidate.videoId, candidate.generatedAt, candidate.inputSha256, candidate.originalIdentity.sha256)
        }

    private suspend fun readValidatedCandidate(archive: File, directory: File, extractionBudget: ExtractionBudget): ValidatedCandidate {
        val payload = readSingleSong(archive, directory, verifyDecodedOriginal = true, extractionBudget = extractionBudget)
        val canonical = archive.canonicalFile
        return ValidatedCandidate(VerifiedStagedArchive(canonical, archiveSha256(canonical),
            canonical.length(), borrowedArchiveSource(canonical)), directory, payload.videoId, payload.original.name,
            payload.originalIdentity, payload.enrichment.getString("generatedAt"),
            enrichmentRevisionIdentity(payload.enrichment, sha256(payload.coverBytes)))
    }

    private fun readValidatedPayload(candidate: ValidatedCandidate): Payload {
        val directory = candidate.directory
        val json = parseStrictJson(File(directory, "song.json").readBytes())
        val cover = File(directory, "cover.png")
        val bytes = cover.readBytes()
        check(enrichmentRevisionIdentity(json, sha256(bytes)) == candidate.inputSha256) { "ENRICHMENT_STAGING_CHANGED" }
        return Payload(candidate.videoId, json, cover, File(directory, candidate.originalName), candidate.originalIdentity, bytes)
    }

    private suspend fun readSingleSong(archive: File, payloadDirectory: File,
        verifyDecodedOriginal: Boolean = false, extractionBudget: ExtractionBudget): Payload {
        if (archive.length() !in 1..MAX_SONG_BUNDLE_BYTES) fail("ZIP_TOO_LARGE")
        val declaredExtractionBytes = ZipFile(archive).use { zip ->
            var total = 0L
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val size = entries.nextElement().size
                if (size < 0L || size > MAX_SONG_BUNDLE_BYTES - total) fail("ZIP_TOO_LARGE")
                total += size
            }
            total
        }
        app.stemwise.data.requireImportStorageCapacity(allocatableBytes(payloadDirectory.parentFile!!),
            Math.addExact(declaredExtractionBytes, archive.length()), "STORAGE_RESERVE_LOW")
        if (!payloadDirectory.mkdirs() && !payloadDirectory.isDirectory) {
            fail("EXTERNAL_COMMIT_FAILED")
        }
        var manifestBytes: ByteArray? = null
        var coverFile: File? = null
        var originalFile: File? = null
        var stagedOriginalIdentity: InstalledMediaIdentity? = null
        val names = linkedSetOf<String>()
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                backgroundWorkCoordinator.checkpoint()
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) fail("ZIP_UNEXPECTED_ENTRY")
                val name = safeRootName(entry)
                if (names.any { it.equals(name, ignoreCase = true) }) {
                    fail("ZIP_DUPLICATE_ENTRY")
                }
                names += name
                when (name) {
                    "song.json" -> manifestBytes = readEntry(zip, MAX_MANIFEST_BYTES, extractionBudget)
                    "cover.png" -> {
                        coverFile = File(payloadDirectory, "cover.png").also {
                            copyEntry(zip, it, MAX_PNG_BYTES, extractionBudget)
                        }
                    }
                    in SelectedLibraryContract.originalFiles -> {
                        if (originalFile != null) fail("ORIGINAL_CONFLICT")
                        originalFile = File(payloadDirectory, name).also {
                            val digest = MessageDigest.getInstance("SHA-256")
                            val copied = it.outputStream().buffered().use { output ->
                                java.security.DigestOutputStream(output, digest).use { hashed ->
                                    copyBounded(zip, hashed, MAX_RECORDING_BYTES, it.parentFile, extractionBudget = extractionBudget)
                                }
                            }
                            stagedOriginalIdentity = InstalledMediaIdentity(
                                digest.digest().joinToString("") { byte -> "%02x".format(byte) }, copied)
                        }
                    }
                    else -> {
                        if (name.lowercase(Locale.ROOT).endsWith(".zip")) {
                            fail("INNER_ZIP_INVALID")
                        }
                        if (isAudio(name)) fail("AUDIO_ENTRY_FORBIDDEN")
                        fail("ZIP_UNEXPECTED_ENTRY")
                    }
                }
                zip.closeEntry()
            }
        }
        if (classifyEnrichmentArchiveRoot(names) != EnrichmentArchiveShape.SINGLE_SONG) {
            if ("song.json" !in names) fail("MANIFEST_JSON_INVALID")
            fail("ZIP_UNEXPECTED_ENTRY")
        }
        val json = parseStrictJson(manifestBytes ?: fail("MANIFEST_JSON_INVALID"))
        validateEnrichment(json, hasCover = coverFile != null)
        val coverBytes = (coverFile ?: fail("ENRICHMENT_PAIR_REQUIRED")).readBytes()
        validatePng(coverBytes)
        val originalIdentity = (originalFile ?: fail("ORIGINAL_REQUIRED")).let { original ->
            if (listOf("metadata", "artwork", "lyrics", "analysis").any { !json.has(it) }) {
                fail("COMPLETE_SONG_REQUIRED")
            }
            val inspectionContext = currentCoroutineContext()
            val facts = inspectOriginalAudio(original) {
                originalInspectionCheckpointForTest?.invoke()
                inspectionContext.ensureActive()
            }
            validateEnrichmentOriginalContainer(original.name, facts.mime)
            check(facts.channels in 1..2 && facts.audibleFrames48k in 1..app.stemwise.MAX_ENRICHMENT_PCM_FRAMES_48K) {
                "ORIGINAL_CODEC_INVALID"
            }
            val duration = (facts.audibleFrames48k * 1_000L + 24_000L) / 48_000L
            val identity = checkNotNull(stagedOriginalIdentity)
            validateEnrichmentOriginalBinding(json.getJSONObject("source"), original.name, identity, duration)
            val supplied = json.optJSONObject("analysis")?.optLong("durationMs", -1L) ?: -1L
            if (supplied > 0L && kotlin.math.abs(supplied - duration) > maxOf(1_000L, duration / 100L)) {
                fail("TIMELINE_INVALID")
            }
            json.optJSONObject("lyrics")?.let { validateLyrics(it, duration) }
            validateFiniteAndTimes(json, duration)
            if (verifyDecodedOriginal) app.stemwise.integration.verifyImportedOriginalPcm(
                context, original, facts.audibleFrames48k)
            identity
        }
        File(payloadDirectory, "song.json").writeBytes(checkNotNull(manifestBytes))
        return Payload(
            videoId = json.getJSONObject("source").getString("videoId"),
            enrichment = json,
            cover = coverFile,
            original = checkNotNull(originalFile),
            originalIdentity = originalIdentity,
            coverBytes = coverBytes
        )
    }

    private fun validateEnrichment(json: JSONObject, hasCover: Boolean) {
        validateEnrichmentContract(json, hasCover)
    }

    private fun validateLyrics(lyrics: JSONObject, durationMs: Long) {
        EnrichmentContractValidator.validateLyrics(lyrics, durationMs)
    }

    private fun validateFiniteAndTimes(value: Any?, durationMs: Long, key: String? = null) {
        EnrichmentContractValidator.validateFiniteAndTimes(value, durationMs, key)
    }

    private fun validatePng(bytes: ByteArray) {
        if (bytes.size.toLong() !in 1..MAX_PNG_BYTES) fail("PNG_TOO_LARGE")
        validateEnrichmentCoverPng(bytes)
    }

    private suspend fun commit(
        payload: Payload,
        expectedRoot: RootAuthority
    ): EnrichmentImportItem {
        val rootHandle = roots.captureHandle()
            ?.takeIf { it.authority() == expectedRoot }
            ?: fail("LIBRARY_CHANGED")
        val packageId = payload.videoId
        val transactionId = UUID.randomUUID().toString()
        return mutations.withPackageMutation(
            rootHandle = rootHandle,
            packageId = packageId,
            operation = "ENRICHMENT_INSTALL_V2",
            transactionId = transactionId,
            requireExactRoot = { roots.requireSameLibrary(rootHandle) },
            holdRootLock = false
        ) { journal ->
            roots.requireSameLibrary(rootHandle)
            var songSnapshot = roots.checkedAuthoritativeSongSnapshot(
                rootHandle,
                packageId,
                requireDurableIdentity = true
            )
                ?: fail("SONG_NOT_FOUND")
            check(songSnapshot.inspection.hasOriginal && songSnapshot.inspection.state !=
                app.stemwise.data.SelectedSongFolderState.UNSUPPORTED) { "PACKAGE_INCOMPLETE" }
            val originalSnapshot = songSnapshot.original
                ?: fail("SONG_NOT_FOUND")
            val original = originalSnapshot.document
            commitCheckpoint("ENRICHMENT_BEFORE_MEDIA_VERIFY")
            val mediaIdentities = measureInstalledMedia(songSnapshot)
            if (mediaIdentities[originalSnapshot.displayName] != payload.originalIdentity) {
                fail("SOURCE_BINDING_CONFLICT")
            }
            val originalSha256 = checkNotNull(
                mediaIdentities[originalSnapshot.displayName]
            ).sha256
            val durationMs = probeInstalledDuration(original)
            validateEnrichmentOriginalBinding(
                payload.enrichment.getJSONObject("source"), originalSnapshot.displayName,
                mediaIdentities.getValue(originalSnapshot.displayName), durationMs
            )
            val manifestFile = songSnapshot.file(SelectedLibraryContract.ENRICHMENT)?.document
            val installedCoverFile = songSnapshot.file(SelectedLibraryContract.COVER)?.document
            val observedJson = manifestFile?.let { readInstalledSidecarForRepair(it, MAX_MANIFEST_BYTES) }
            val observedCover = installedCoverFile?.let { readInstalledSidecarForRepair(it, MAX_PNG_BYTES) }
            val installed = if (observedJson != null && observedCover != null) try {
                parseStrictJson(observedJson).also {
                    validateInstalledEnrichmentDocument(it, packageId, originalSha256)
                    check(installedMediaIdentities(it) == mediaIdentities) {
                        "ASSET_HASH_MISMATCH"
                    }
                    validateInstalledCoverIdentity(
                        installed = it,
                        coverBytes = observedCover
                    )
                }
            } catch (_: Exception) { null } else null
            // A verified incoming Original permits repair; invalid sidecars are never a valid rollback pair.
            val repairing = installed == null && (manifestFile != null || installedCoverFile != null)
            val oldBytes = observedJson.takeIf { installed != null }
            val oldCoverBytes = observedCover.takeIf { installed != null }
            val coverBytes = payload.coverBytes
            val coverHash = sha256(coverBytes)
            val payloadSha256 = enrichmentRevisionIdentity(payload.enrichment, coverHash)
            val priorPayloadSha256 = installed?.optJSONObject("install")
                ?.optString("payloadSha256")
            if (installed != null) {
                when (
                    decideEnrichmentRevision(
                        installedState = "enriched",
                        installedUpdatedAt = installed.optString("generatedAt"),
                        installedInputSha256 = priorPayloadSha256,
                        incomingGeneratedAt = payload.enrichment.getString("generatedAt"),
                        incomingInputSha256 = payloadSha256
                    )
                ) {
                    EnrichmentRevisionDecision.UNCHANGED -> {
                        journal.complete(JSONObject().put("result", "UNCHANGED"))
                        return@withPackageMutation EnrichmentImportItem(
                            videoId = packageId,
                            packageId = packageId,
                            status = "unchanged"
                        )
                    }
                    EnrichmentRevisionDecision.STALE -> fail("ENRICHMENT_UPDATE_STALE")
                    EnrichmentRevisionDecision.CONFLICT -> fail("ENRICHMENT_REVISION_CONFLICT")
                    EnrichmentRevisionDecision.APPLY -> Unit
                }
            }
            payload.enrichment.optJSONObject("analysis")?.let { analysis ->
                val supplied = analysis.optLong("durationMs", -1L)
                if (
                    supplied > 0L && kotlin.math.abs(supplied - durationMs) >
                    maxOf(1_000L, durationMs / 100L)
                ) fail("TIMELINE_INVALID")
            }
            payload.enrichment.optJSONObject("lyrics")?.let { validateLyrics(it, durationMs) }
            validateFiniteAndTimes(payload.enrichment, durationMs)

            validateEnrichmentCoverPng(coverBytes)
            val nextRevision = installed?.optJSONObject("install")
                ?.optLong("contentRevision", 0L)?.plus(1L) ?: 1L
            val next = createInstalledEnrichmentDocument(
                incoming = payload.enrichment,
                originalSha256 = originalSha256,
                media = mediaIdentities,
                contentRevision = nextRevision,
                payloadSha256 = payloadSha256,
                artwork = payload.enrichment.getJSONObject("artwork"),
                artworkSha256 = coverHash,
                artworkBytes = coverBytes.size
            )
            validateInstalledEnrichmentDocument(next, packageId, originalSha256)
            val nextBytes = next.toString().toByteArray(Charsets.UTF_8)
            val recoveryDirectory = enrichmentRecoveryDirectory()
            val replacementBytes = nextBytes.size.toLong() + coverBytes.size.toLong()
            val backupBytes = (oldBytes?.size?.toLong() ?: 0L) + (oldCoverBytes?.size?.toLong() ?: 0L)
            app.stemwise.data.requireImportStorageCapacity(allocatableBytes(recoveryDirectory),
                backupBytes + replacementBytes, "STORAGE_RESERVE_LOW")
            app.stemwise.data.requireImportStorageCapacity(
                app.stemwise.data.providerImportAvailableBytes(context, rootHandle.treeUri),
                backupBytes + 2L * replacementBytes, "LIBRARY_STORAGE_LOW")
            val jsonBackup = File(recoveryDirectory, "$transactionId.json.backup")
            val jsonReplacement = File(recoveryDirectory, "$transactionId.json.replacement")
            val coverBackup = File(recoveryDirectory, "$transactionId.cover.backup")
            val coverReplacement = File(recoveryDirectory, "$transactionId.cover.replacement")
            val portableTransactionFiles = setOf(
                SelectedLibraryContract.ENRICHMENT_TRANSACTION,
                SelectedLibraryContract.ENRICHMENT_TRANSACTION_TEMP,
                SelectedLibraryContract.ENRICHMENT_JSON_BACKUP,
                SelectedLibraryContract.ENRICHMENT_JSON_REPLACEMENT,
                SelectedLibraryContract.ENRICHMENT_COVER_BACKUP,
                SelectedLibraryContract.ENRICHMENT_COVER_REPLACEMENT
            )
            val untouchedPreparation = enrichmentPreparationTargets(context, songSnapshot, originalSha256) {
                backgroundWorkCoordinator.checkpoint()
            }
            val commitDetails = JSONObject()
                        .put("libraryId", rootHandle.libraryId)
                        .put("videoId", packageId)
                        .put("packageDocumentUri", songSnapshot.directory.uri.toString())
                        .put("packageDocumentId", songSnapshot.directory.documentId)
                        .put("hadOldPair", oldBytes != null)
                        .put("repairing", repairing)
                        .put("untouchedPreparation", untouchedPreparation)
                        .put("jsonBackupName", jsonBackup.name)
                        .put("jsonReplacementName", jsonReplacement.name)
                        .put("coverBackupName", coverBackup.name)
                        .put("coverReplacementName", coverReplacement.name)
                        .put("oldJsonSha256", oldBytes?.let(::sha256) ?: "")
                        .put("oldCoverSha256", oldCoverBytes?.let(::sha256) ?: "")
                        .put("newJsonSha256", sha256(nextBytes))
                        .put("newCoverSha256", coverHash)
                        .put("newCoverBytes", coverBytes.size)
            suspend fun discardPreparedCommit(): Boolean = suspendResult {
                journal.write(EnrichmentInstallPhase.PREPARATION_ABORTED, commitDetails)
                commitCheckpoint(ENRICHMENT_PREPARATION_BEFORE_ABORT_CLEANUP)
                val untouched = roots.checkedBoundSongSnapshot(rootHandle, packageId, songSnapshot.directory.uri.toString())
                val originalNow = checkNotNull(untouched.original) { "RECOVERY_PENDING" }
                val originalIdentityNow = measureSelectedLibraryDocument(context, originalNow) {
                    backgroundWorkCoordinator.checkpoint()
                }
                requireUnchangedEnrichmentPreparation(untouchedPreparation,
                    enrichmentPreparationTargets(context, untouched, originalIdentityNow.sha256) {
                        backgroundWorkCoordinator.checkpoint()
                    })
                roots.deleteExactSongSidecars(
                    rootHandle,
                    packageId,
                    portableTransactionFiles
                )
                deleteRecoveryFile(jsonBackup)
                deleteRecoveryFile(jsonReplacement)
                deleteRecoveryFile(coverBackup)
                deleteRecoveryFile(coverReplacement)
                journal.complete(JSONObject().put("result", "PREPARATION_ABORTED"))
            }.isSuccess
            check(
                roots.readExactSongSidecars(
                    rootHandle,
                    packageId,
                    portableTransactionFiles,
                    MAX_MANIFEST_BYTES
                ).isEmpty()
            ) { "RECOVERY_PENDING" }
            try {
                if (oldBytes != null) {
                    writePrivateDurably(jsonBackup, oldBytes)
                    writePrivateDurably(coverBackup, checkNotNull(oldCoverBytes))
                }
                writePrivateDurably(jsonReplacement, nextBytes)
                writePrivateDurably(coverReplacement, coverBytes)
                journal.write(EnrichmentInstallPhase.COMMITTING, commitDetails)
                if (oldBytes != null) {
                    roots.writeExactSongSidecar(
                        rootHandle,
                        packageId,
                        SelectedLibraryContract.ENRICHMENT_JSON_BACKUP,
                        "application/json",
                        oldBytes,
                        MAX_MANIFEST_BYTES
                    )
                    roots.writeExactSongSidecar(
                        rootHandle,
                        packageId,
                        SelectedLibraryContract.ENRICHMENT_COVER_BACKUP,
                        "image/png",
                        checkNotNull(oldCoverBytes),
                        MAX_PNG_BYTES
                    )
                }
                roots.writeExactSongSidecar(
                    rootHandle,
                    packageId,
                    SelectedLibraryContract.ENRICHMENT_JSON_REPLACEMENT,
                    "application/json",
                    nextBytes,
                    MAX_MANIFEST_BYTES
                )
                commitCheckpoint(ENRICHMENT_PREPARATION_AFTER_JSON_REPLACEMENT)
                roots.writeExactSongSidecar(
                    rootHandle,
                    packageId,
                    SelectedLibraryContract.ENRICHMENT_COVER_REPLACEMENT,
                    "image/png",
                    coverBytes,
                    MAX_PNG_BYTES
                )
                val portableTransactionBytes = PortableEnrichmentTransaction(
                    libraryId = rootHandle.libraryId,
                    videoId = packageId,
                    transactionId = transactionId,
                    hadOldPair = oldBytes != null,
                    oldJsonSha256 = oldBytes?.let(::sha256).orEmpty(),
                    oldJsonBytes = oldBytes?.size?.toLong() ?: 0L,
                    oldCoverSha256 = oldCoverBytes?.let(::sha256).orEmpty(),
                    oldCoverBytes = oldCoverBytes?.size?.toLong() ?: 0L,
                    newJsonSha256 = sha256(nextBytes),
                    newJsonBytes = nextBytes.size.toLong(),
                    newCoverSha256 = coverHash,
                    newCoverBytes = coverBytes.size.toLong()
                ).toPortableJsonBytes()
                roots.writeExactSongSidecar(
                    rootHandle,
                    packageId,
                    SelectedLibraryContract.ENRICHMENT_TRANSACTION_TEMP,
                    "application/json",
                    portableTransactionBytes,
                    PORTABLE_ENRICHMENT_TRANSACTION_MAX_BYTES
                )
                roots.writeExactSongSidecar(
                    rootHandle,
                    packageId,
                    SelectedLibraryContract.ENRICHMENT_TRANSACTION,
                    "application/json",
                    portableTransactionBytes,
                    PORTABLE_ENRICHMENT_TRANSACTION_MAX_BYTES
                )
                roots.deleteExactSongSidecars(
                    rootHandle,
                    packageId,
                    setOf(SelectedLibraryContract.ENRICHMENT_TRANSACTION_TEMP)
                )
            } catch (error: Throwable) {
                val discarded = if (error is CancellationException) {
                    withContext(NonCancellable) { discardPreparedCommit() }
                } else {
                    discardPreparedCommit()
                }
                if (!discarded) {
                    throw EnrichmentImportException("RECOVERY_PENDING").also {
                        it.addSuppressed(error)
                    }
                }
                throw error
            }
            var targetCover = installedCoverFile
            var targetManifest = manifestFile
            suspend fun rollbackCommit(): Boolean = suspendResult {
                if (oldBytes != null) {
                    val priorCover = checkNotNull(oldCoverBytes)
                    mutations.withRootMutation {
                        roots.requireSameLibrary(rootHandle)
                        writeDocument(checkNotNull(targetCover), priorCover)
                        writeDocument(checkNotNull(targetManifest), oldBytes)
                    }
                    val restoredCoverBytes = readDocument(
                        checkNotNull(targetCover),
                        MAX_PNG_BYTES
                    )
                    val restoredManifestBytes = readDocument(
                        checkNotNull(targetManifest),
                        MAX_MANIFEST_BYTES
                    )
                    check(
                        restoredCoverBytes.size == priorCover.size &&
                            sha256(restoredCoverBytes) == sha256(priorCover) &&
                            restoredManifestBytes.size == oldBytes.size &&
                            sha256(restoredManifestBytes) == sha256(oldBytes)
                    ) { "RECOVERY_PENDING" }
                    validateInstalledCoverIdentity(
                        parseStrictJson(restoredManifestBytes),
                        restoredCoverBytes
                    )
                } else {
                    targetManifest?.let {
                        check(it.delete()) { "RECOVERY_PENDING" }
                    }
                    targetCover?.let {
                        check(it.delete()) { "RECOVERY_PENDING" }
                    }
                    val cleared = roots.checkedBoundSongSnapshot(
                        rootHandle,
                        packageId,
                        songSnapshot.directory.uri.toString(),
                    )
                    check(
                        cleared.file(SelectedLibraryContract.ENRICHMENT) == null &&
                            cleared.file(SelectedLibraryContract.COVER) == null
                    ) { "RECOVERY_PENDING" }
                }
                journal.write(EnrichmentInstallPhase.ROLLED_BACK, commitDetails)
                roots.deleteExactSongSidecars(
                    rootHandle,
                    packageId,
                    setOf(
                        SelectedLibraryContract.ENRICHMENT_JSON_BACKUP,
                        SelectedLibraryContract.ENRICHMENT_JSON_REPLACEMENT,
                        SelectedLibraryContract.ENRICHMENT_COVER_BACKUP,
                        SelectedLibraryContract.ENRICHMENT_COVER_REPLACEMENT
                    )
                )
                roots.deleteExactSongSidecars(
                    rootHandle,
                    packageId,
                    setOf(
                        SelectedLibraryContract.ENRICHMENT_TRANSACTION,
                        SelectedLibraryContract.ENRICHMENT_TRANSACTION_TEMP
                    )
                )
                deleteRecoveryFile(jsonBackup)
                deleteRecoveryFile(jsonReplacement)
                deleteRecoveryFile(coverBackup)
                deleteRecoveryFile(coverReplacement)
                journal.complete(JSONObject().put("result", "ROLLED_BACK"))
            }.isSuccess
            try {
                mutations.withRootMutation {
                    roots.requireSameLibrary(rootHandle)
                    targetCover = targetCover ?: roots.createExactSongChild(
                        rootHandle, songSnapshot, "image/png", SelectedLibraryContract.COVER
                    ).document
                    commitCheckpoint(ENRICHMENT_COMMIT_BEFORE_TARGET_WRITE)
                    writeDocument(checkNotNull(targetCover), coverBytes)
                }
                val reopenedCoverBytes = readDocument(checkNotNull(targetCover), MAX_PNG_BYTES)
                validateEnrichmentCoverPng(reopenedCoverBytes)
                check(
                    reopenedCoverBytes.size == coverBytes.size &&
                        sha256(reopenedCoverBytes) == coverHash
                ) { "ARTWORK_NEEDS_REIMPORT" }
                commitCheckpoint(ENRICHMENT_COMMIT_AFTER_COVER_WRITE)
                mutations.withRootMutation {
                    roots.requireSameLibrary(rootHandle)
                    targetManifest = targetManifest ?: roots.createExactSongChild(
                        rootHandle, songSnapshot, "application/json", SelectedLibraryContract.ENRICHMENT
                    ).document
                    writeDocument(checkNotNull(targetManifest), nextBytes)
                }
                commitCheckpoint(ENRICHMENT_COMMIT_AFTER_TARGET_WRITE)
                val reopened = parseStrictJson(
                    readDocument(checkNotNull(targetManifest), MAX_MANIFEST_BYTES)
                )
                validateInstalledEnrichmentDocument(reopened, packageId, originalSha256)
                validateInstalledCoverIdentity(reopened, reopenedCoverBytes)
                check(installedMediaIdentities(reopened) == mediaIdentities) {
                    "ASSET_HASH_MISMATCH"
                }
                check(sha256(reopened.toString().toByteArray(Charsets.UTF_8)) ==
                    sha256(next.toString().toByteArray(Charsets.UTF_8))) {
                    "EXTERNAL_COMMIT_FAILED"
                }
                val committedSnapshot = roots.checkedAuthoritativeSongSnapshot(
                    rootHandle,
                    packageId
                )
                    ?: fail("SONG_NOT_FOUND")
                check(measureInstalledMedia(committedSnapshot) == mediaIdentities) {
                    "ASSET_HASH_MISMATCH"
                }
                check(committedSnapshot.inspection.enriched) { "ENRICHMENT_PAIR_INVALID" }
                validateInstalledCoverIdentity(
                    reopened,
                    readDocument(
                        checkNotNull(committedSnapshot.file(SelectedLibraryContract.COVER)).document,
                        MAX_PNG_BYTES
                    )
                )
                mutations.withRootMutation {
                    roots.requireSameLibrary(rootHandle)
                    sourceBindings.activateUnderPackageLock(rootHandle, "youtube-video:$packageId", packageId, transactionId, journal)
                    journal.complete(JSONObject().put("videoId", packageId).put("contentRevision", nextRevision), retainForRecovery = true)
                }
                roots.deleteExactSongSidecars(
                    rootHandle,
                    packageId,
                    setOf(
                        SelectedLibraryContract.ENRICHMENT_JSON_BACKUP,
                        SelectedLibraryContract.ENRICHMENT_JSON_REPLACEMENT,
                        SelectedLibraryContract.ENRICHMENT_COVER_BACKUP,
                        SelectedLibraryContract.ENRICHMENT_COVER_REPLACEMENT
                    )
                )
                roots.deleteExactSongSidecars(
                    rootHandle,
                    packageId,
                    setOf(
                        SelectedLibraryContract.ENRICHMENT_TRANSACTION,
                        SelectedLibraryContract.ENRICHMENT_TRANSACTION_TEMP
                    )
                )
                deleteRecoveryFile(jsonBackup)
                deleteRecoveryFile(jsonReplacement)
                deleteRecoveryFile(coverBackup)
                deleteRecoveryFile(coverReplacement)
                journal.releaseRecovery()
                EnrichmentImportItem(
                    videoId = packageId,
                    packageId = packageId,
                    status = if (installed == null) "imported" else "updated"
                )
            } catch (error: Throwable) {
                if (error is EnrichmentCommitInterruption) throw error
                if (journal.completed) {
                    throw EnrichmentImportException("RECOVERY_PENDING").also { it.addSuppressed(error) }
                }
                if (repairing) {
                    // Both validated replacements and their marker remain available for roll-forward recovery.
                    throw EnrichmentImportException("RECOVERY_PENDING").also { it.addSuppressed(error) }
                }
                val restored = if (error is CancellationException) {
                    withContext(NonCancellable) { rollbackCommit() }
                } else {
                    rollbackCommit()
                }
                if (!restored && error is CancellationException) {
                    throw EnrichmentImportException("RECOVERY_PENDING").also {
                        it.addSuppressed(error)
                    }
                } else if (!restored) {
                    fail("RECOVERY_PENDING")
                }
                throw error
            }
        }
    }

    private suspend fun measureInstalledMedia(
        songSnapshot: app.stemwise.data.SelectedSongSnapshot
    ): Map<String, InstalledMediaIdentity> =
        listOf(songSnapshot.inspection.originalFileName ?: fail("ASSET_MISSING")).associateWith { path ->
            val snapshot = songSnapshot.file(path)
                ?: fail("ASSET_MISSING")
            val file = snapshot.document
            val measured = measureSelectedLibraryDocument(context, snapshot) {
                backgroundWorkCoordinator.checkpoint()
            }
            val flac = if (path == SelectedLibraryContract.ORIGINAL_FLAC) {
                val inspectionContext = currentCoroutineContext()
                context.contentResolver.withCancellableProviderInput(file.uri) { input, _ ->
                    runCancellableBlockingOperation(cancelExact = input::close) {
                        inspectPreparedSourceAudio(input, measured.byteLength, allowHighPrecisionFlac = true) {
                            inspectionContext.ensureActive()
                        }
                    }
                } ?: fail("ORIGINAL_CODEC_INVALID")
            } else null
            check(flac == null || flac.mime == "audio/flac" && flac.channels in 1..2 &&
                flac.sampleRate in 8_000..384_000 &&
                flac.audibleFrames <= app.stemwise.MAX_ENRICHMENT_DURATION_MS * flac.sampleRate / 1_000L) { "ORIGINAL_CODEC_INVALID" }
            val mime = probeInstalledMime(file)
            check(canonicalMediaMimeMatches(path, mime) || flac != null && mime == "audio/raw") {
                "ORIGINAL_CODEC_INVALID"
            }
            InstalledMediaIdentity(measured.sha256, measured.byteLength)
        }

    private fun validateInstalledCoverIdentity(installed: JSONObject, coverBytes: ByteArray) {
        validateEnrichmentCoverPng(coverBytes)
        val artwork = installed.optJSONObject("artwork") ?: fail("ENRICHMENT_PAIR_INVALID")
        check(
            artwork.optString("delivery") == "selected-root" &&
                artwork.optString("file") == SelectedLibraryContract.COVER &&
                artwork.optString("mimeType") == "image/png" &&
                artwork.optLong("byteLength", -1L) == coverBytes.size.toLong() &&
                artwork.optString("sha256") == sha256(coverBytes)
        ) { "ARTWORK_NEEDS_REIMPORT" }
    }

    private suspend fun probeInstalledMime(file: DocumentFile): String {
        return context.contentResolver.withCancellableProviderAssetFileDescriptor(file.uri) { descriptor ->
            val extractor = MediaExtractor()
            try {
                if (descriptor.declaredLength >= 0L) {
                    extractor.setDataSource(
                        descriptor.fileDescriptor,
                        descriptor.startOffset,
                        descriptor.declaredLength
                    )
                } else {
                    extractor.setDataSource(descriptor.fileDescriptor)
                }
                (0 until extractor.trackCount).asSequence()
                    .map { extractor.getTrackFormat(it) }
                    .mapNotNull { format ->
                        format.getString(MediaFormat.KEY_MIME)?.takeIf { it.startsWith("audio/") }
                    }
                    .firstOrNull()
                    ?: fail("ASSET_MISSING")
            } finally {
                extractor.release()
            }
        } ?: fail("ASSET_MISSING")
    }

    private suspend fun probeInstalledDuration(original: DocumentFile): Long {
        return context.contentResolver.withCancellableProviderAssetFileDescriptor(original.uri) { descriptor ->
            val retriever = MediaMetadataRetriever()
            try {
                if (descriptor.declaredLength >= 0L) {
                    retriever.setDataSource(
                        descriptor.fileDescriptor,
                        descriptor.startOffset,
                        descriptor.declaredLength
                    )
                } else {
                    retriever.setDataSource(descriptor.fileDescriptor)
                }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.takeIf { it > 0L }
                    ?: fail("TIMELINE_INVALID")
            } finally {
                retriever.release()
            }
        } ?: fail("ASSET_MISSING")
    }

    private fun enrichmentRecoveryDirectory(): File =
        File(context.filesDir, "enrichment-recovery").apply {
            check(isDirectory || mkdirs()) { "JOURNAL_STORAGE_UNAVAILABLE" }
        }.canonicalFile

    private fun writePrivateDurably(target: File, bytes: ByteArray) {
        target.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
        java.io.FileOutputStream(target, false).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    private fun deleteRecoveryFile(target: File) {
        check(!target.exists() || target.delete()) { "RECOVERY_PENDING" }
    }

    private fun parseStrictJson(bytes: ByteArray): JSONObject = parseStrictManifestJson(bytes)

    private suspend fun copyEntry(zip: ZipInputStream, target: File, maximum: Long, extractionBudget: ExtractionBudget): Long =
        target.outputStream().buffered().use { output ->
            copyBounded(zip, output, maximum, target.parentFile, extractionBudget = extractionBudget)
        }

    @Volatile internal var extractionProgressForTest: (suspend () -> Unit)? = null
    @Volatile internal var originalInspectionCheckpointForTest: (() -> Unit)? = null

    private suspend fun copyBounded(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        maximum: Long,
        reserveDirectory: File? = null,
        readCancellable: suspend (ByteArray) -> Int = input::read,
        extractionBudget: ExtractionBudget? = null
    ): Long {
        val buffer = ByteArray(128 * 1024)
        var copied = 0L
        var nextSpaceCheck = SPACE_RECHECK_BYTES
        while (true) {
            backgroundWorkCoordinator.checkpoint()
            val read = readCancellable(buffer)
            if (read < 0) break
            copied += read
            if (copied > maximum) fail("ZIP_TOO_LARGE")
            if (
                reserveDirectory != null &&
                copied >= nextSpaceCheck &&
                allocatableBytes(reserveDirectory) < SONG_INTAKE_RESERVE_BYTES
            ) {
                fail("STORAGE_RESERVE_LOW")
            }
            if (copied >= nextSpaceCheck) nextSpaceCheck += SPACE_RECHECK_BYTES
            extractionBudget?.add(read.toLong())
            output.write(buffer, 0, read)
            if (extractionBudget != null) extractionProgressForTest?.invoke()
        }
        return copied
    }

    private suspend fun readEntry(zip: ZipInputStream, maximum: Long, extractionBudget: ExtractionBudget): ByteArray {
        val output = ByteArrayOutputStream()
        copyBounded(zip, output, maximum, extractionBudget = extractionBudget)
        return output.toByteArray()
    }

    private fun allocatableBytes(directory: File): Long = privateStorageAvailableForTest?.invoke(directory) ?: runCatching {
        val storageManager = context.getSystemService(StorageManager::class.java)
        storageManager.getAllocatableBytes(storageManager.getUuidForPath(directory))
    }.getOrDefault(directory.freeSpace)

    private fun safeRootName(entry: ZipEntry): String {
        val name = entry.name
        val segments = LibraryRootManager.safeRelativeSegments(name) ?: fail("ZIP_TRAVERSAL")
        if (segments.size != 1 || name.startsWith('.')) fail("ZIP_UNEXPECTED_ENTRY")
        return segments.single()
    }

    private fun isAudio(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in AUDIO_EXTENSIONS

    private suspend fun readInstalledSidecarForRepair(file: DocumentFile, maximum: Long): ByteArray? = try {
        // Actual empty/oversized bytes are invalid rollback inputs; provider I/O failures still reject.
        readBoundedProviderDocument(context, file.uri, null, maximum, requireNonEmpty = false) {
            backgroundWorkCoordinator.checkpoint()
        }
    } catch (error: IllegalStateException) {
        if (error.message != "MANIFEST_TOO_LARGE") throw error
        null
    }

    private suspend fun readDocument(file: DocumentFile, maximum: Long): ByteArray {
        return readBoundedProviderDocument(
            context,
            file.uri,
            file.length().takeIf { it > 0L },
            maximum
        ) {
            backgroundWorkCoordinator.checkpoint()
        }
    }

    private suspend fun writeDocument(file: DocumentFile, bytes: ByteArray) {
        context.contentResolver.withCancellableProviderOutput(file.uri) { _, write, flush ->
            write(bytes, 0, bytes.size)
            flush()
        } ?: fail("EXTERNAL_COMMIT_FAILED")
    }



    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .toHexString()

    private fun errorCode(error: Throwable): String =
        (error as? EnrichmentImportException)?.code
            ?: error.message?.takeIf { ERROR_CODE.matches(it) }
            ?: if (app.stemwise.data.isImportStorageFailure(error)) "STORAGE_RESERVE_LOW" else "INNER_ZIP_INVALID"

    private fun commitErrorCode(error: Throwable): String =
        (error as? EnrichmentImportException)?.code
            ?: (error as? app.stemwise.data.LibraryRootException)?.code
            ?: error.message?.takeIf { ERROR_CODE.matches(it) }
            ?: if (app.stemwise.data.isImportStorageFailure(error)) "STORAGE_RESERVE_LOW" else "EXTERNAL_COMMIT_FAILED"

    private data class Payload(
        val videoId: String,
        val enrichment: JSONObject,
        val cover: File,
        val original: File,
        val originalIdentity: InstalledMediaIdentity,
        val coverBytes: ByteArray
    )

    private data class SelectedArchive(
        val file: File,
        val sourceName: String
    )

    private data class ArchiveItem(
        val archive: File,
        val entryName: String?,
        val sourceName: String,
        val rootFailure: Exception? = null
    )

    private data class PreflightItem(
        val sourceName: String,
        val candidate: ValidatedCandidate?,
        var errorCode: String?,
        val validationFailure: Throwable? = null,
        var disposition: EnrichmentBatchRevisionDisposition =
            EnrichmentBatchRevisionDisposition.APPLY,
        var duplicateOfIndex: Int? = null
    ) {
        val videoId get() = candidate?.videoId
        val generatedAt get() = candidate?.generatedAt
        val inputSha256 get() = candidate?.inputSha256
        val originalSha256 get() = candidate?.originalIdentity?.sha256

        fun invalidResult() = EnrichmentImportItem(
            videoId = videoId,
            packageId = null,
            status = "invalid",
            errorCode = errorCode ?: "INNER_ZIP_INVALID",
            sourceName = sourceName
        )

        fun duplicateResult(representative: EnrichmentImportItem?) =
            duplicateEnrichmentImportItem(videoId, sourceName, representative)
    }

    private data class ValidatedCandidate(val archive: VerifiedStagedArchive, val directory: File,
        val videoId: String, val originalName: String, val originalIdentity: InstalledMediaIdentity,
        val generatedAt: String, val inputSha256: String)

    companion object {
        private val IMPORT_TRANSACTION_ID = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
        )
        // A complete 5 ms waveform for the supported two-hour duration is ~13 MiB
        // after Base64 encoding; keep import and installed-manifest bounds aligned.
        private const val MAX_MANIFEST_BYTES = ENRICHMENT_ARTIFACT_MAX_BYTES
        private const val SPACE_RECHECK_BYTES = 8L * 1024L * 1024L
        private val BUNDLE_TERMINAL_REJECTIONS = setOf("ENRICHMENT_UPDATE_STALE", "ENRICHMENT_REVISION_CONFLICT", "SOURCE_BINDING_CONFLICT")
        private val ERROR_CODE = Regex("^[A-Z][A-Z0-9_]+$")
        private val AUDIO_EXTENSIONS = setOf(
            "opus", "ogg", "webm", "mp3", "wav", "flac", "aac", "m4a", "mp4", "mkv"
        )

        private fun fail(code: String): Nothing = throw EnrichmentImportException(code)
    }
}

internal fun requireRolledBackEnrichmentPair(
    hadOldPair: Boolean,
    oldJsonSha256: String,
    oldCoverSha256: String,
    json: ByteArray?,
    cover: ByteArray?,
    jsonPresent: Boolean,
    coverPresent: Boolean
) {
    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
    if (hadOldPair) {
        check(jsonPresent && coverPresent && json != null && cover != null &&
            hash(json) == oldJsonSha256 && hash(cover) == oldCoverSha256) { "RECOVERY_PENDING" }
    } else {
        check(!jsonPresent && !coverPresent && json == null && cover == null &&
            oldJsonSha256.isEmpty() && oldCoverSha256.isEmpty()) { "RECOVERY_PENDING" }
    }
}

internal const val ENRICHMENT_PREPARATION_AFTER_JSON_REPLACEMENT = "PREPARATION_AFTER_JSON_REPLACEMENT"
internal const val ENRICHMENT_PREPARATION_BEFORE_ABORT_CLEANUP = "PREPARATION_BEFORE_ABORT_CLEANUP"
internal const val ENRICHMENT_COMMIT_BEFORE_TARGET_WRITE = "COMMITTING_BEFORE_TARGET_WRITE"
internal const val ENRICHMENT_COMMIT_AFTER_COVER_WRITE = "COMMITTING_AFTER_COVER_WRITE"
internal const val ENRICHMENT_COMMIT_AFTER_TARGET_WRITE = "COMMITTING_AFTER_TARGET_WRITE"
internal class EnrichmentCommitInterruption(phase: String) :
    RuntimeException("SIMULATED_PROCESS_DEATH:$phase")

/** Counts streamed materialization bytes, including nested ZIPs, throughout one admission. */
internal class ExtractionBudget(private val maximumBytes: Long) {
    var expandedBytes = 0L
        private set
    fun add(bytes: Long) {
        require(bytes >= 0L)
        if (bytes > maximumBytes - expandedBytes) throw ExtractionBudgetExceeded()
        expandedBytes += bytes
    }
}
internal class ExtractionBudgetExceeded : IllegalStateException("ZIP_EXPANSION_BUDGET_EXCEEDED")
