package app.stemwise.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import app.stemwise.enrichment.enrichmentPreparationTargets
import app.stemwise.enrichment.requireUnchangedEnrichmentPreparation
import app.stemwise.enrichment.EnrichmentInstallPhase
import app.stemwise.enrichment.requireEnrichmentRecoveryState
import app.stemwise.enrichment.installedMediaIdentities
import app.stemwise.enrichment.parseStrictManifestJson
import app.stemwise.enrichment.PortableEnrichmentTransaction
import app.stemwise.enrichment.selectPortableEnrichmentTransaction
import app.stemwise.enrichment.validateInstalledEnrichmentDocument
import app.stemwise.enrichment.validateEnrichmentCoverPng
import app.stemwise.playback.BackgroundWorkCoordinator
import app.stemwise.integration.withCancellableProviderOutput
import kotlinx.coroutines.CancellationException
import java.io.File
import java.security.MessageDigest

private data class PortableEnrichmentRecoveryPair(
    val jsonBytes: ByteArray,
    val coverBytes: ByteArray,
    val jsonSha256: String,
    val coverSha256: String
)

/** Replays stopped packages under root ownership; live publishers keep their package journals. */
internal class EnrichmentTransactionRecovery(
    private val context: Context,
    private val roots: LibraryRootManager,
    private val backgroundWorkCoordinator: BackgroundWorkCoordinator,
    private val mutations: PackageMutationCoordinator
) {
    suspend fun recover(
        rootHandle: RootHandle
    ): SelectedLibrarySnapshot? {
        val journalDirectory = File(context.filesDir, "operation-journals")
        val recoveryDirectory = File(context.filesDir, "enrichment-recovery").canonicalFile
        checkedAtomicJournalRecords(journalDirectory, "RECOVERY_PENDING")
            .forEach { record ->
                val journalFile = record.file
                val envelope = requireAtomicJournalEnvelope(record, "RECOVERY_PENDING")
                if (envelope.operation != "ENRICHMENT_INSTALL_V2") return@forEach
                requireEnrichmentRecoveryState(envelope.state)
                if (
                    envelope.libraryId != rootHandle.libraryId ||
                    envelope.treeUri != rootHandle.treeUri.toString() ||
                    envelope.rootEpoch != rootHandle.epoch
                ) return@forEach
                val transactionId = envelope.transactionId
                val packageId = envelope.packageId
                check(SelectedLibraryContract.isVideoId(packageId)) { "RECOVERY_PENDING" }
                if (mutations.packageIsOwnedUnderRootLock(rootHandle, packageId)) return@forEach
                val jsonBackup = File(
                    recoveryDirectory,
                    "$transactionId.json.backup"
                ).canonicalFile
                val jsonReplacement = File(
                    recoveryDirectory,
                    "$transactionId.json.replacement"
                ).canonicalFile
                val coverBackup = File(
                    recoveryDirectory,
                    "$transactionId.cover.backup"
                ).canonicalFile
                val coverReplacement = File(
                    recoveryDirectory,
                    "$transactionId.cover.replacement"
                ).canonicalFile
                check(
                    listOf(jsonBackup, jsonReplacement, coverBackup, coverReplacement)
                        .all { it.parentFile == recoveryDirectory }
                ) { "RECOVERY_PENDING" }
                if (envelope.state in setOf("PREPARED", "COMPLETE")) {
                    listOf(jsonBackup, jsonReplacement, coverBackup, coverReplacement)
                        .forEach { recoveryFile ->
                            check(!recoveryFile.exists() || recoveryFile.delete()) {
                                "RECOVERY_PENDING"
                            }
                    }
                    deleteAtomicJournal(journalFile, "RECOVERY_PENDING")
                    return@forEach
                }
                check(envelope.state in setOf("COMMITTING", "SOURCE_BINDING_COMMIT", "ROLLED_BACK", EnrichmentInstallPhase.PREPARATION_ABORTED)) {
                    "RECOVERY_PENDING"
                }
                val details = envelope.details
                check((details.opt("libraryId") as? String) == rootHandle.libraryId) {
                    "LIBRARY_CHANGED"
                }
                val videoId = details.opt("videoId") as? String
                    ?: error("RECOVERY_PENDING")
                check(videoId == packageId) { "RECOVERY_PENDING" }
                val recordedJsonReplacement = File(
                    recoveryDirectory,
                    details.opt("jsonReplacementName") as? String
                        ?: error("RECOVERY_PENDING")
                ).canonicalFile
                val recordedJsonBackup = File(
                    recoveryDirectory,
                    details.opt("jsonBackupName") as? String
                        ?: error("RECOVERY_PENDING")
                ).canonicalFile
                val recordedCoverReplacement = File(
                    recoveryDirectory,
                    details.opt("coverReplacementName") as? String
                        ?: error("RECOVERY_PENDING")
                ).canonicalFile
                val recordedCoverBackup = File(
                    recoveryDirectory,
                    details.opt("coverBackupName") as? String
                        ?: error("RECOVERY_PENDING")
                ).canonicalFile
                check(
                    recordedJsonReplacement == jsonReplacement &&
                        recordedJsonBackup == jsonBackup &&
                        recordedCoverReplacement == coverReplacement &&
                        recordedCoverBackup == coverBackup
                ) { "RECOVERY_PENDING" }
                val packageUri = (details.opt("packageDocumentUri") as? String)
                    ?.takeIf(String::isNotBlank)
                    ?: error("RECOVERY_PENDING")
                val parsedPackageUri = Uri.parse(packageUri)
                val packageDocumentId = (details.opt("packageDocumentId") as? String)
                    ?.takeIf(String::isNotBlank)
                    ?: error("RECOVERY_PENDING")
                check(
                    selectedBoundUriMatchesRoot(rootHandle.treeUri, parsedPackageUri) &&
                        runCatching { DocumentsContract.getDocumentId(parsedPackageUri) }
                            .getOrNull() == packageDocumentId
                ) { "RECOVERY_PENDING" }
                var songSnapshot = roots.checkedBoundSongSnapshot(
                    rootHandle,
                    videoId,
                    packageUri,
                )
                val original = songSnapshot.original
                    ?: error("RECOVERY_PENDING")
                val originalHash = hashFlatDocument(original)
                var jsonTarget = songSnapshot.file(SelectedLibraryContract.ENRICHMENT)
                var coverTarget = songSnapshot.file(SelectedLibraryContract.COVER)
                val currentJsonBytes = jsonTarget?.let {
                    readEnrichmentRecoveryCandidate(context, it, MAX_MANIFEST_BYTES)
                }
                val currentCoverBytes = coverTarget?.let {
                    readEnrichmentRecoveryCandidate(context, it, MAX_PNG_BYTES)
                }
                if (envelope.state == EnrichmentInstallPhase.PREPARATION_ABORTED ||
                    envelope.state == EnrichmentInstallPhase.ROLLED_BACK) {
                    if (envelope.state == EnrichmentInstallPhase.PREPARATION_ABORTED) {
                        val expected = details.optJSONObject("untouchedPreparation") ?: error("RECOVERY_PENDING")
                        requireUnchangedEnrichmentPreparation(expected,
                            enrichmentPreparationTargets(context, songSnapshot, originalHash) {
                                backgroundWorkCoordinator.checkpoint()
                            })
                    } else {
                        val hadOldPair = details.opt("hadOldPair") as? Boolean ?: error("RECOVERY_PENDING")
                        app.stemwise.enrichment.requireRolledBackEnrichmentPair(
                            hadOldPair,
                            details.optString("oldJsonSha256"), details.optString("oldCoverSha256"),
                            currentJsonBytes, currentCoverBytes,
                            jsonPresent = jsonTarget != null, coverPresent = coverTarget != null
                        )
                        if (hadOldPair) validateMarkerlessEnrichmentPair(rootHandle, songSnapshot)
                    }
                    roots.deleteExactSongSidecars(rootHandle, videoId, setOf(
                        SelectedLibraryContract.ENRICHMENT_TRANSACTION,
                        SelectedLibraryContract.ENRICHMENT_TRANSACTION_TEMP,
                        SelectedLibraryContract.ENRICHMENT_JSON_BACKUP,
                        SelectedLibraryContract.ENRICHMENT_JSON_REPLACEMENT,
                        SelectedLibraryContract.ENRICHMENT_COVER_BACKUP,
                        SelectedLibraryContract.ENRICHMENT_COVER_REPLACEMENT
                    ))
                    listOf(jsonBackup, jsonReplacement, coverBackup, coverReplacement).forEach {
                        check(!it.exists() || it.delete()) { "RECOVERY_PENDING" }
                    }
                    deleteAtomicJournal(journalFile, "RECOVERY_PENDING")
                    return@forEach
                }
                val newJsonSha256 = details.opt("newJsonSha256") as? String
                    ?: error("RECOVERY_PENDING")
                val newCoverSha256 = details.opt("newCoverSha256") as? String
                    ?: error("RECOVERY_PENDING")
                requireNoUnownedValidPair(songSnapshot, videoId, originalHash, currentJsonBytes,
                    currentCoverBytes, buildSet {
                        add(newJsonSha256 to newCoverSha256)
                        if (details.optBoolean("hadOldPair")) {
                            add(details.optString("oldJsonSha256") to details.optString("oldCoverSha256"))
                        }
                    })
                val replacementJsonBytes = if (
                    currentJsonBytes != null && sha256(currentJsonBytes) == newJsonSha256
                ) {
                    currentJsonBytes
                } else {
                    readBoundedPrivateFile(
                        jsonReplacement,
                        MAX_MANIFEST_BYTES,
                        "RECOVERY_PENDING"
                    ).also {
                        check(sha256(it) == newJsonSha256) { "RECOVERY_PENDING" }
                    }
                }
                val replacementCoverBytes = if (
                    currentCoverBytes != null && sha256(currentCoverBytes) == newCoverSha256
                ) {
                    currentCoverBytes
                } else {
                    readBoundedPrivateFile(
                        coverReplacement,
                        MAX_PNG_BYTES,
                        "RECOVERY_PENDING"
                    ).also {
                        check(sha256(it) == newCoverSha256) { "RECOVERY_PENDING" }
                    }
                }
                val replacementJson = parseStrictManifestJson(replacementJsonBytes)
                validateInstalledEnrichmentDocument(replacementJson, videoId, originalHash)
                val artwork = replacementJson.getJSONObject("artwork")
                check(
                    artwork.optString("file") == SelectedLibraryContract.COVER &&
                        artwork.optLong("byteLength", -1L) == replacementCoverBytes.size.toLong() &&
                        artwork.optString("sha256") == sha256(replacementCoverBytes)
                ) { "RECOVERY_PENDING" }
                installedMediaIdentities(replacementJson).forEach { (path, identity) ->
                    val media = songSnapshot.file(path)
                        ?: error("RECOVERY_PENDING")
                    val measured = measureFlatDocument(media)
                    check(
                        measured.byteLength == identity.byteLength &&
                            measured.sha256 == identity.sha256
                    ) { "RECOVERY_PENDING" }
                }
                coverTarget = coverTarget ?: roots.createExactSongChild(
                    rootHandle,
                    songSnapshot,
                    "image/png",
                    SelectedLibraryContract.COVER,
                )
                if (currentCoverBytes == null || sha256(currentCoverBytes) != newCoverSha256) {
                    write(coverTarget.document, replacementCoverBytes)
                    check(
                        sha256(readBounded(coverTarget.document, MAX_PNG_BYTES)) == newCoverSha256
                    ) { "RECOVERY_PENDING" }
                }
                songSnapshot = roots.checkedBoundSongSnapshot(
                    rootHandle,
                    videoId,
                    packageUri,
                )
                jsonTarget = jsonTarget ?: roots.createExactSongChild(
                    rootHandle,
                    songSnapshot,
                    "application/json",
                    SelectedLibraryContract.ENRICHMENT,
                )
                if (currentJsonBytes == null || sha256(currentJsonBytes) != newJsonSha256) {
                    write(jsonTarget.document, replacementJsonBytes)
                    check(
                        sha256(readBounded(jsonTarget.document, MAX_MANIFEST_BYTES)) ==
                            newJsonSha256
                    ) {
                        "RECOVERY_PENDING"
                    }
                }
                val converged = roots.checkedBoundSongSnapshot(rootHandle, videoId, packageUri)
                check(converged.inspection.enriched) { "RECOVERY_PENDING" }
                validateMarkerlessEnrichmentPair(rootHandle, converged)
                roots.deleteExactSongSidecars(
                    rootHandle,
                    videoId,
                    setOf(
                        SelectedLibraryContract.ENRICHMENT_TRANSACTION,
                        SelectedLibraryContract.ENRICHMENT_TRANSACTION_TEMP,
                        SelectedLibraryContract.ENRICHMENT_JSON_BACKUP,
                        SelectedLibraryContract.ENRICHMENT_JSON_REPLACEMENT,
                        SelectedLibraryContract.ENRICHMENT_COVER_BACKUP,
                        SelectedLibraryContract.ENRICHMENT_COVER_REPLACEMENT
                    )
                )
                listOf(jsonBackup, jsonReplacement, coverBackup, coverReplacement)
                    .forEach { recoveryFile ->
                        check(!recoveryFile.exists() || recoveryFile.delete()) {
                            "RECOVERY_PENDING"
                        }
                    }
                deleteAtomicJournal(journalFile, "RECOVERY_PENDING")
            }
        return try { recoverPortableEnrichmentTransactions(rootHandle) }
        catch (unavailable: java.io.IOException) {
            throw LibraryRootException("RECOVERY_PENDING", "Enrichment recovery could not read the Library; its files were preserved.")
                .also { it.addSuppressed(unavailable) }
        }
    }

    private suspend fun recoverPortableEnrichmentTransactions(
        rootHandle: RootHandle
    ): SelectedLibrarySnapshot? {
        val markerFiles = setOf(
            SelectedLibraryContract.ENRICHMENT_TRANSACTION,
            SelectedLibraryContract.ENRICHMENT_TRANSACTION_TEMP
        )
        val payloadFiles = setOf(
            SelectedLibraryContract.ENRICHMENT_JSON_BACKUP,
            SelectedLibraryContract.ENRICHMENT_JSON_REPLACEMENT,
            SelectedLibraryContract.ENRICHMENT_COVER_BACKUP,
            SelectedLibraryContract.ENRICHMENT_COVER_REPLACEMENT
        )
        val transactionFiles = markerFiles + payloadFiles
        val snapshot = roots.checkedSnapshot(rootHandle)
        var changed = false
        snapshot.songDirectories.forEach { directory ->
                if (mutations.packageIsOwnedUnderRootLock(rootHandle, directory.displayName)) return@forEach
                val discovered = roots.checkedBoundSongSnapshot(rootHandle,
                    directory.displayName, directory.uri.toString())
                if (discovered.files.keys.none { it in transactionFiles }) return@forEach
                changed = true
                roots.requireSameLibrary(rootHandle)
                val videoId = discovered.videoId
                val sidecars = roots.readExactSongSidecars(
                    rootHandle,
                    videoId,
                    transactionFiles,
                    MAX_MANIFEST_BYTES
                )
                val transaction = selectPortableEnrichmentTransaction(
                    sidecars.filterKeys(markerFiles::contains)
                )
                if (transaction == null) {
                    // The committed marker is verified before either canonical file is touched.
                    // With no committed marker, payload/temp remnants are preparation or cleanup.
                    validateMarkerlessEnrichmentPair(rootHandle, discovered)
                    roots.deleteExactSongSidecars(rootHandle, videoId, payloadFiles)
                    roots.deleteExactSongSidecars(rootHandle, videoId, markerFiles)
                    return@forEach
                }
                check(
                    transaction.libraryId == rootHandle.libraryId &&
                        transaction.videoId == videoId
                ) { "LIBRARY_CHANGED" }
                recoverPortableEnrichmentTransaction(
                    rootHandle,
                    discovered,
                    transaction,
                    sidecars
                )
                roots.deleteExactSongSidecars(rootHandle, videoId, payloadFiles)
                roots.deleteExactSongSidecars(rootHandle, videoId, markerFiles)
            }
        return if (changed) null else snapshot
    }

    private suspend fun validateMarkerlessEnrichmentPair(
        rootHandle: RootHandle,
        discovered: SelectedSongSnapshot
    ) {
        val snapshot = roots.checkedBoundSongSnapshot(
            rootHandle,
            discovered.videoId,
            discovered.directory.uri.toString(),
        )
        val manifest = snapshot.file(SelectedLibraryContract.ENRICHMENT)
        val cover = snapshot.file(SelectedLibraryContract.COVER)
        check((manifest == null) == (cover == null)) { "RECOVERY_PENDING" }
        if (manifest == null) return
        val original = snapshot.original
            ?: error("RECOVERY_PENDING")
        val manifestBytes = readBounded(manifest, MAX_MANIFEST_BYTES)
        val coverBytes = readBounded(checkNotNull(cover), MAX_PNG_BYTES)
        val installed = parseStrictManifestJson(manifestBytes)
        validateInstalledEnrichmentDocument(installed, discovered.videoId, hashFlatDocument(original))
        val artwork = installed.getJSONObject("artwork")
        check(
            artwork.optString("file") == SelectedLibraryContract.COVER &&
                artwork.optLong("byteLength", -1L) == coverBytes.size.toLong() &&
                artwork.optString("sha256") == sha256(coverBytes)
        ) { "RECOVERY_PENDING" }
        installedMediaIdentities(installed).forEach { (path, identity) ->
            val measured = measureFlatDocument(snapshot.file(path) ?: error("RECOVERY_PENDING"))
            check(
                measured.byteLength == identity.byteLength && measured.sha256 == identity.sha256
            ) { "RECOVERY_PENDING" }
        }
    }

    private suspend fun recoverPortableEnrichmentTransaction(
        rootHandle: RootHandle,
        discovered: SelectedSongSnapshot,
        transaction: PortableEnrichmentTransaction,
        sidecars: Map<String, ByteArray>
    ) {
        val videoId = transaction.videoId
        var songSnapshot = roots.checkedBoundSongSnapshot(
            rootHandle,
            videoId,
            discovered.directory.uri.toString(),
        )
        val original = songSnapshot.original
            ?: error("RECOVERY_PENDING")
        val originalHash = hashFlatDocument(original)
        var jsonTarget = songSnapshot.file(SelectedLibraryContract.ENRICHMENT)
        var coverTarget = songSnapshot.file(SelectedLibraryContract.COVER)
        val currentJsonBytes = jsonTarget?.let {
            readEnrichmentRecoveryCandidate(context, it, MAX_MANIFEST_BYTES)
        }
        val currentCoverBytes = coverTarget?.let {
            readEnrichmentRecoveryCandidate(context, it, MAX_PNG_BYTES)
        }
        requireNoUnownedValidPair(songSnapshot, videoId, originalHash, currentJsonBytes,
            currentCoverBytes, buildSet {
                add(transaction.newJsonSha256 to transaction.newCoverSha256)
                if (transaction.hadOldPair) add(transaction.oldJsonSha256 to transaction.oldCoverSha256)
            })
        fun matchingBytes(
            candidates: List<ByteArray?>,
            expectedBytes: Long,
            expectedSha256: String
        ): ByteArray? = candidates.filterNotNull().firstOrNull {
            it.size.toLong() == expectedBytes && sha256(it) == expectedSha256
        }
        val newPair = matchingBytes(
            listOf(currentJsonBytes, sidecars[SelectedLibraryContract.ENRICHMENT_JSON_REPLACEMENT]),
            transaction.newJsonBytes,
            transaction.newJsonSha256
        )?.let { jsonBytes ->
            matchingBytes(
                listOf(
                    currentCoverBytes,
                    sidecars[SelectedLibraryContract.ENRICHMENT_COVER_REPLACEMENT]
                ),
                transaction.newCoverBytes,
                transaction.newCoverSha256
            )?.let { coverBytes ->
                validatedPortableEnrichmentPair(
                    songSnapshot,
                    videoId,
                    originalHash,
                    jsonBytes,
                    coverBytes,
                    transaction.newJsonSha256,
                    transaction.newCoverSha256
                )
            }
        }
        val oldPair = if (newPair == null && transaction.hadOldPair) {
            matchingBytes(
                listOf(currentJsonBytes, sidecars[SelectedLibraryContract.ENRICHMENT_JSON_BACKUP]),
                transaction.oldJsonBytes,
                transaction.oldJsonSha256
            )?.let { jsonBytes ->
                matchingBytes(
                    listOf(
                        currentCoverBytes,
                        sidecars[SelectedLibraryContract.ENRICHMENT_COVER_BACKUP]
                    ),
                    transaction.oldCoverBytes,
                    transaction.oldCoverSha256
                )?.let { coverBytes ->
                    validatedPortableEnrichmentPair(
                        songSnapshot,
                        videoId,
                        originalHash,
                        jsonBytes,
                        coverBytes,
                        transaction.oldJsonSha256,
                        transaction.oldCoverSha256
                    )
                }
            }
        } else null
        if (newPair == null && oldPair == null && !transaction.hadOldPair) {
            jsonTarget?.document?.takeIf(DocumentFile::exists)?.let {
                check(it.delete()) { "RECOVERY_PENDING" }
            }
            coverTarget?.document?.takeIf(DocumentFile::exists)?.let {
                check(it.delete()) { "RECOVERY_PENDING" }
            }
            val cleared = roots.checkedBoundSongSnapshot(
                rootHandle,
                videoId,
                discovered.directory.uri.toString(),
            )
            check(
                cleared.file(SelectedLibraryContract.ENRICHMENT) == null &&
                    cleared.file(SelectedLibraryContract.COVER) == null
            ) { "RECOVERY_PENDING" }
            return
        }
        val desired = newPair ?: oldPair ?: error("RECOVERY_PENDING")
        coverTarget = coverTarget ?: roots.createExactSongChild(
            rootHandle,
            songSnapshot,
            "image/png",
            SelectedLibraryContract.COVER,
        )
        if (
            currentCoverBytes == null ||
            currentCoverBytes.size != desired.coverBytes.size ||
            sha256(currentCoverBytes) != desired.coverSha256
        ) {
            write(coverTarget.document, desired.coverBytes)
        }
        check(
            readBounded(coverTarget.document, MAX_PNG_BYTES).let {
                it.size == desired.coverBytes.size && sha256(it) == desired.coverSha256
            }
        ) { "RECOVERY_PENDING" }
        songSnapshot = roots.checkedBoundSongSnapshot(
            rootHandle,
            videoId,
            discovered.directory.uri.toString(),
        )
        jsonTarget = jsonTarget ?: roots.createExactSongChild(
            rootHandle,
            songSnapshot,
            "application/json",
            SelectedLibraryContract.ENRICHMENT,
        )
        if (
            currentJsonBytes == null ||
            currentJsonBytes.size != desired.jsonBytes.size ||
            sha256(currentJsonBytes) != desired.jsonSha256
        ) {
            write(jsonTarget.document, desired.jsonBytes)
        }
        val convergedJsonBytes = readBounded(jsonTarget.document, MAX_MANIFEST_BYTES)
        check(
            convergedJsonBytes.size == desired.jsonBytes.size &&
                sha256(convergedJsonBytes) == desired.jsonSha256
        ) { "RECOVERY_PENDING" }
        val convergedJson = parseStrictManifestJson(convergedJsonBytes)
        validateInstalledEnrichmentDocument(convergedJson, videoId, originalHash)
        val converged = roots.checkedBoundSongSnapshot(
            rootHandle,
            videoId,
            discovered.directory.uri.toString()
        )
        check(converged.inspection.enriched) { "RECOVERY_PENDING" }
        validateMarkerlessEnrichmentPair(rootHandle, converged)
    }

    private suspend fun requireNoUnownedValidPair(
        songSnapshot: SelectedSongSnapshot,
        videoId: String,
        originalHash: String,
        jsonBytes: ByteArray?,
        coverBytes: ByteArray?,
        ownedPairs: Set<Pair<String, String>>
    ) {
        if (jsonBytes == null || coverBytes == null) return
        val jsonHash = sha256(jsonBytes)
        val coverHash = sha256(coverBytes)
        if (jsonHash to coverHash in ownedPairs) return
        check(validatedPortableEnrichmentPair(songSnapshot, videoId, originalHash,
            jsonBytes, coverBytes, jsonHash, coverHash) == null) { "RECOVERY_PENDING" }
    }

    private suspend fun validatedPortableEnrichmentPair(
        songSnapshot: SelectedSongSnapshot,
        videoId: String,
        originalHash: String,
        jsonBytes: ByteArray,
        coverBytes: ByteArray,
        jsonSha256: String,
        coverSha256: String
    ): PortableEnrichmentRecoveryPair? {
        val installed = try {
            validateEnrichmentCoverPng(coverBytes)
            parseStrictManifestJson(jsonBytes).also { value ->
                validateInstalledEnrichmentDocument(value, videoId, originalHash)
                val artwork = value.getJSONObject("artwork")
                check(artwork.optString("file") == SelectedLibraryContract.COVER &&
                    artwork.optLong("byteLength", -1L) == coverBytes.size.toLong() &&
                    artwork.optString("sha256") == coverSha256) { "RECOVERY_PENDING" }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            canonicalProjectionRootAccessFailure(error)?.let { throw it }
            return null
        }
        // Content rejection may select another pair; an unavailable provider must preserve every pair.
        installedMediaIdentities(installed).forEach { (path, identity) ->
            val file = songSnapshot.file(path) ?: return null
            val measured = measureFlatDocument(file)
            if (measured.byteLength != identity.byteLength || measured.sha256 != identity.sha256) return null
        }
        return PortableEnrichmentRecoveryPair(jsonBytes, coverBytes, jsonSha256, coverSha256)
    }


    private suspend fun readBounded(file: DocumentFile, maximum: Long): ByteArray =
        readBoundedProviderDocument(context, file.uri, file.length().takeIf { it > 0L }, maximum)
    private suspend fun readBounded(file: SelectedDocumentSnapshot, maximum: Long): ByteArray =
        readBoundedProviderDocument(context, file.uri, file.sizeBytes, maximum)
    private suspend fun measureFlatDocument(file: SelectedDocumentSnapshot): ProviderDocumentIdentity =
        measureSelectedLibraryDocument(context, file) { backgroundWorkCoordinator.checkpoint() }
    private suspend fun hashFlatDocument(file: SelectedDocumentSnapshot): String =
        measureFlatDocument(file).sha256
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).toHexString()
    private suspend fun write(file: DocumentFile, bytes: ByteArray) {
        context.contentResolver.withCancellableProviderOutput(file.uri) { _, write, flush ->
            write(bytes, 0, bytes.size)
            flush()
        } ?: error("EXTERNAL_COMMIT_FAILED")
    }



    private companion object {
        const val MAX_MANIFEST_BYTES = app.stemwise.enrichment.ENRICHMENT_ARTIFACT_MAX_BYTES
        const val MAX_PNG_BYTES = 16L * 1024L * 1024L
    }
}
