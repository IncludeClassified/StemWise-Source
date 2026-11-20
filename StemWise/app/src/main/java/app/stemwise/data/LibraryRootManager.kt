package app.stemwise.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import app.stemwise.BuildConfig
import app.stemwise.integration.PlaylistLink
import app.stemwise.integration.PlaylistLinks
import app.stemwise.integration.queryCancellable
import app.stemwise.integration.withCancellableProviderOutput
import app.stemwise.enrichment.parseStrictManifestJson
import app.stemwise.suspendResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

private const val ROOT_MANIFEST_SCHEMA_VERSION = 1L
private const val ROOT_MANIFEST_KIND = "stemwise.library-root"
private const val MAX_ROOT_MANIFEST_BYTES = 16L * 1024L
private val ROOT_MANIFEST_KEYS = setOf("schemaVersion", "kind", "libraryId")
private val ROOT_MANIFEST_FILES = setOf(
    SelectedLibraryContract.ROOT_MANIFEST,
    SelectedLibraryContract.ROOT_MANIFEST_BACKUP,
    SelectedLibraryContract.ROOT_MANIFEST_TEMP
)

internal fun isCanonicalLibraryId(value: String): Boolean = runCatching {
    UUID.fromString(value).toString() == value
}.getOrDefault(false)

internal fun persistedSafGrantIsUsable(read: Boolean, write: Boolean): Boolean = read && write

internal fun parsePortableLibraryId(bytes: ByteArray): String {
    val value = parseStrictManifestJson(bytes)
    check(value.keys().asSequence().toSet() == ROOT_MANIFEST_KEYS) {
        "ROOT_MANIFEST_INVALID"
    }
    check(
        value.opt("schemaVersion") == ROOT_MANIFEST_SCHEMA_VERSION &&
            value.opt("kind") == ROOT_MANIFEST_KIND
    ) { "ROOT_MANIFEST_INVALID" }
    return (value.opt("libraryId") as? String)
        ?.takeIf(::isCanonicalLibraryId)
        ?: error("ROOT_MANIFEST_INVALID")
}

internal fun selectPortableLibraryIdCopies(
    copies: Map<String, ByteArray>,
    parse: (ByteArray) -> String,
    authenticatedGeneration: Pair<ByteArray, String>? = null
): String? {
    if (copies.isEmpty()) return null
    check(copies.keys.all { it in ROOT_MANIFEST_FILES }) {
        "ROOT_MANIFEST_INVALID"
    }
    val valid = mutableMapOf<String, Pair<ByteArray, String>>()
    val invalid = mutableMapOf<String, ByteArray>()
    copies.forEach { (name, bytes) ->
        runCatching { parse(bytes) }
            .onSuccess { valid[name] = bytes to it }
            .onFailure { invalid[name] = bytes }
    }
    val libraryIds = valid.values.map { it.second }.distinct()
    check(libraryIds.size <= 1) { "ROOT_MANIFEST_INVALID" }
    val authenticatedLibraryId = authenticatedGeneration?.second
    val authorityAuthenticated = authenticatedGeneration != null &&
        (libraryIds.isEmpty() || libraryIds.single() == authenticatedLibraryId)
    val authenticatedBytes = buildList {
        if (authorityAuthenticated) add(checkNotNull(authenticatedGeneration).first)
        if (authorityAuthenticated && libraryIds.singleOrNull() == authenticatedLibraryId) {
            addAll(valid.values.map { it.first })
        }
    }
    invalid.forEach { (_, bytes) ->
        check(
            interruptedReservedAuthorityWriteIsRecoverable(
                bytes,
                authenticatedBytes,
                authorityAuthenticated
            )
        ) { "ROOT_MANIFEST_INVALID" }
    }
    if (valid.isEmpty()) return authenticatedGeneration
        ?.takeIf { (_, libraryId) ->
            invalid.values.all { bytes ->
                interruptedReservedAuthorityWriteIsRecoverable(
                    bytes,
                    authenticatedBytes,
                    authorityAuthenticated = true
                )
            } && libraryId == authenticatedLibraryId
        }
        ?.second
        ?: error("ROOT_MANIFEST_INVALID")
    return libraryIds.single()
}

private fun portableLibraryManifest(libraryId: String): ByteArray {
    check(isCanonicalLibraryId(libraryId)) { "ROOT_MANIFEST_INVALID" }
    return JSONObject()
        .put("schemaVersion", ROOT_MANIFEST_SCHEMA_VERSION)
        .put("kind", ROOT_MANIFEST_KIND)
        .put("libraryId", libraryId)
        .toString()
        .toByteArray(Charsets.UTF_8)
}

data class LibraryRootState(
    val treeUri: Uri? = null,
    val libraryId: String? = null,
    val displayName: String? = null,
    val writable: Boolean = false,
    val errorCode: String? = null,
    val epoch: Long = 0L
) {
    val ready: Boolean
        get() = treeUri != null && libraryId != null && writable && errorCode == null && epoch > 0L
}

private fun LibraryRootState.failClosedForRecovery(): LibraryRootState = copy(
    writable = false,
    errorCode = if (treeUri != null) "ROOT_RESTORING" else "ROOT_CHECKING"
)

data class RootHandle(
    val libraryId: String,
    val treeUri: Uri,
    val rootDocument: DocumentFile,
    val epoch: Long
)

/** The durable part of a [RootHandle], safe to retain across an asynchronous boundary. */
data class RootAuthority(
    val libraryId: String,
    val treeUri: String,
    val epoch: Long
)



fun RootHandle.authority(): RootAuthority = RootAuthority(
    libraryId = libraryId,
    treeUri = treeUri.toString(),
    epoch = epoch
)

internal fun rootAuthorityMatches(
    currentReady: Boolean,
    currentLibraryId: String?,
    currentTreeUri: String?,
    currentEpoch: Long,
    expectedLibraryId: String,
    expectedTreeUri: String,
    expectedEpoch: Long
): Boolean = currentReady &&
    currentLibraryId == expectedLibraryId &&
    currentTreeUri == expectedTreeUri &&
    currentEpoch == expectedEpoch

internal fun requireRootAuthority(
    currentReady: Boolean,
    currentLibraryId: String?,
    currentTreeUri: String?,
    currentEpoch: Long,
    expectedLibraryId: String,
    expectedTreeUri: String,
    expectedEpoch: Long
) {
    check(
        rootAuthorityMatches(
            currentReady,
            currentLibraryId,
            currentTreeUri,
            currentEpoch,
            expectedLibraryId,
            expectedTreeUri,
            expectedEpoch
        )
    ) { "LIBRARY_CHANGED" }
}

class ValidatedRootCandidate internal constructor(
    val treeUri: Uri,
    val rootDocument: DocumentFile,
    val libraryId: String,
    val existingEntryCount: Int,
    val existingSongCount: Int
) {
    val requiresNonEmptyFolderConfirmation: Boolean
        get() = existingEntryCount > 0
}

internal fun selectsDifferentLibraryIdentity(
    rememberedLibraryId: String?,
    candidateLibraryId: String
): Boolean = !rememberedLibraryId.isNullOrBlank() && rememberedLibraryId != candidateLibraryId

internal fun reconnectsExactTreeUri(
    persistedTreeUri: String?,
    candidateTreeUri: String
): Boolean = persistedTreeUri != null && persistedTreeUri == candidateTreeUri

internal fun shouldReleaseFailedCandidatePermission(
    previousTreeUri: String?,
    persistedTreeUri: String?,
    persistedLibraryId: String?,
    candidateTreeUri: String,
    candidateLibraryId: String
): Boolean = candidateTreeUri != previousTreeUri &&
    !(persistedTreeUri == candidateTreeUri && persistedLibraryId == candidateLibraryId)

internal fun releaseObsoletePersistedRootPermissions(
    activeTreeUri: String?,
    persistedTreeUris: Collection<String>,
    release: (String) -> Result<Unit>
): List<String> = persistedTreeUris
    .distinct()
    .filter { it != activeTreeUri }
    .mapNotNull { treeUri -> treeUri.takeIf { release(it).isFailure } }

internal fun requiresExplicitLibrarySelectionConfirmation(
    rememberedLibraryId: String?,
    candidateLibraryId: String,
    candidateRequiresNonEmptyFolderConfirmation: Boolean
): Boolean = candidateRequiresNonEmptyFolderConfirmation ||
    selectsDifferentLibraryIdentity(rememberedLibraryId, candidateLibraryId)

internal fun isAppOwnedUiDemoTreeUri(packageName: String, treeUri: Uri): Boolean =
    BuildConfig.BUILD_TYPE == "development" && treeUri.authority == "$packageName.ui.demo.library" &&
        runCatching { DocumentsContract.getTreeDocumentId(treeUri) == "root" }.getOrDefault(false)

internal fun developmentRootActivationFailure(
    packageName: String,
    treeAuthority: String?,
    alreadyActive: Boolean,
    hasReadWriteGrant: Boolean
): String? = when {
    packageName != DEVELOPMENT_APPLICATION_ID -> "DEVELOPMENT_PACKAGE_REJECTED"
    treeAuthority != "$DEVELOPMENT_APPLICATION_ID.golden.qa.documents" ->
        "DEVELOPMENT_PROVIDER_REJECTED"
    alreadyActive -> "DEVELOPMENT_DUPLICATE_ACTIVATION"
    !hasReadWriteGrant -> "DEVELOPMENT_PERMISSION_MISSING"
    else -> null
}

/**
 * Owns the private locator and the selected-root stable identity for one user-selected Library.
 */
class LibraryRootManager(
    private val context: Context,
    private val mutations: PackageMutationCoordinator,
    private val dao: LibraryDao
) {
    private val selectedLibrarySnapshots = SelectedLibrarySnapshotReader(context)
    private val mutableState = MutableStateFlow(LibraryRootState(errorCode = "ROOT_CHECKING"))
    val state: StateFlow<LibraryRootState> = mutableState.asStateFlow()

    /** Development test seam; production also refreshes the Room-backed playlist UI projection. */
    internal suspend fun initialize(): LibraryRootState = reconnect()

    suspend fun initialize(preferences: StemwisePreferences): LibraryRootState =
        reconnect(preferences)

    suspend fun validateCandidate(treeUri: Uri): Result<ValidatedRootCandidate> =
        withContext(Dispatchers.IO) { suspendResult { validateCandidateInternal(treeUri) } }

    internal suspend fun commitValidatedCandidate(
        candidate: ValidatedRootCandidate
    ): Result<LibraryRootState> = withContext(Dispatchers.IO) {
        val previousState = state.value
        var candidatePermissionTaken = false
        suspendResult {
            mutations.withRootReplacement {
                takePersistentPermission(candidate.treeUri)
                candidatePermissionTaken = true
                mutableState.value = previousState.failClosedForRecovery()
                selectedIdentityWithRetainedOperationGuard()
                commitCandidate(candidate, publishVerified = false)
            }
        }.onFailure { error ->
            restoreRootAfterFailedCommit(previousState, candidate, error, candidatePermissionTaken)
        }
    }

    /**
     * Explicit test-only activation seam for the disposable standard-test application. It never
     * bypasses validation and cannot run in a production package or against a non-QA provider.
     */
    internal suspend fun commitValidatedCandidateForDevelopment(
        candidate: ValidatedRootCandidate
    ): Result<LibraryRootState> = withContext(Dispatchers.IO) {
        val previousState = state.value
        suspendResult {
            check(BuildConfig.BUILD_TYPE == "development") { "DEVELOPMENT_PACKAGE_REJECTED" }
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            developmentRootActivationFailure(
                packageName = context.packageName,
                treeAuthority = candidate.treeUri.authority,
                alreadyActive = state.value.ready,
                hasReadWriteGrant = context.checkUriPermission(
                    candidate.treeUri,
                    Process.myPid(),
                    Process.myUid(),
                    flags
                ) == PackageManager.PERMISSION_GRANTED
            )?.let(::error)
            mutations.withRootReplacement {
                mutableState.value = previousState.failClosedForRecovery()
                selectedLibrarySnapshots.read(candidate.rootDocument)
                // Development test fixture has no LibrarySessionCoordinator. Preserve its direct
                // activation seam while retaining the same RESTORING-before-VERIFIED ordering.
                commitCandidate(candidate, publishVerified = true)
            }
        }.onFailure { error ->
            restoreRootAfterFailedCommit(previousState, candidate, error, releaseCandidatePermission = false)
        }
    }

    fun releasePersistentPermission(treeUri: Uri): Result<Unit> {
        if (isAppOwnedUiDemoTreeUri(context.packageName, treeUri)) return Result.success(Unit)
        return runCatching {
            context.contentResolver.releasePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.onFailure { error ->
            android.util.Log.w(
                "StemwiseLibrary",
                "Could not release an obsolete persisted Library permission; reconnect will retry",
                error
            )
        }
    }

    private fun reconcilePersistedPermissions(activeTreeUri: Uri?) {
        val persistedTreeUris = runCatching {
            context.contentResolver.persistedUriPermissions.filter { DocumentsContract.isTreeUri(it.uri) }.map { it.uri.toString() }
        }.onFailure { error ->
            android.util.Log.w(
                "StemwiseLibrary",
                "Could not enumerate persisted Library permissions; reconnect will retry cleanup",
                error
            )
        }.getOrNull() ?: return
        releaseObsoletePersistedRootPermissions(
            activeTreeUri = activeTreeUri?.toString(),
            persistedTreeUris = persistedTreeUris
        ) { treeUri -> releasePersistentPermission(Uri.parse(treeUri)) }
    }

    /** Development test seam; production reconnect also refreshes the playlist UI projection. */
    internal suspend fun reconnect(): LibraryRootState = reconnectInternal(null)

    suspend fun reconnect(stemwisePreferences: StemwisePreferences): LibraryRootState =
        reconnectInternal(stemwisePreferences)

    private suspend fun reconnectInternal(
        stemwisePreferences: StemwisePreferences?
    ): LibraryRootState =
        withContext(Dispatchers.IO) {
            mutations.withRootReplacement {
                // Room may already name another selection after interruption; close old handles
                // before reading that authority or attempting its external recovery.
                mutableState.value = state.value.failClosedForRecovery()
                var persisted: PersistedRootSnapshot? = null
                try {
                    persisted = readPersistedSnapshot()
                    reconcilePersistedPermissions(persisted.state.treeUri)
                    if (persisted.eligibleForRestoration) {
                        val restoring = persisted.state.copy(
                            writable = false,
                            errorCode = "ROOT_RESTORING"
                        )
                        mutableState.value = restoring
                        persistActiveIdentity(restoring)
                        val root = checkNotNull(persisted.rootDocument)
                        val selectedSnapshot = selectedLibrarySnapshots.read(root)
                        ensurePortableLibraryManifest(
                            ValidatedRootCandidate(
                                treeUri = checkNotNull(restoring.treeUri),
                                rootDocument = root,
                                libraryId = checkNotNull(restoring.libraryId),
                                existingEntryCount = selectedSnapshot.totalEntryCount,
                                existingSongCount = selectedSnapshot.songDirectories.size
                            ),
                            selectedSnapshot
                        )
                        val writable = root.canWrite()
                        val verified = persisted.state.copy(
                            writable = writable,
                            errorCode = if (writable) null else "ROOT_UNAVAILABLE"
                        )
                        check(verified.ready) { verified.errorCode ?: "ROOT_UNAVAILABLE" }
                        stemwisePreferences?.activateLibraryFromRoot(
                            checkNotNull(verified.libraryId),
                            configuredPlaylistIdentity(verified.libraryId)
                        )
                        check(
                            stemwisePreferences == null ||
                                stemwisePreferences.activeLibraryIdentity() == verified.libraryId
                        ) { "ACTIVE_LIBRARY_CONVERGENCE_FAILED" }
                        persistActiveIdentity(verified)
                        mutableState.value = verified
                        return@withRootReplacement verified
                    }
                    mutableState.value = persisted.state
                    persisted.state
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    val base = persisted?.state ?: state.value
                    val stableCode = (error as? LibraryRootException)?.code
                        ?: error.message?.takeIf { it.matches(Regex("^[A-Z][A-Z0-9_]+$")) }
                        ?: "ROOT_UNAVAILABLE"
                    val failed = base.copy(
                        writable = false,
                        errorCode = stableCode
                    )
                    mutableState.value = failed
                    suspendResult { persistActiveIdentity(failed) }
                        .exceptionOrNull()?.takeIf { it !== error }?.let(error::addSuppressed)
                    failed
                }
            }
        }

    /** Identity-only ownership checks never construct or classify a provider document. */
    fun captureAuthority(): RootAuthority? {
        val snapshot = state.value
        if (!snapshot.ready) return null
        val libraryId = snapshot.libraryId ?: return null
        val treeUri = snapshot.treeUri?.toString() ?: return null
        return RootAuthority(libraryId, treeUri, snapshot.epoch)
    }

    fun captureHandle(): RootHandle? = captureHandle(state.value)

    /** Derives a handle from the caller's one captured state; later exact-root checks close races. */
    internal fun captureHandle(snapshot: LibraryRootState): RootHandle? {
        if (!snapshot.ready) return null
        val uri = snapshot.treeUri ?: return null
        val libraryId = snapshot.libraryId ?: return null
        val root = DocumentFile.fromTreeUri(context, uri) ?: return null
        return RootHandle(libraryId, uri, root, snapshot.epoch)
    }

    suspend fun reportRuntimeAccessFailure(expected: RootHandle, error: Throwable): LibraryRootState {
        val errorCode = runtimeLibraryRootErrorCode(error)
        mutableState.update { current ->
            if (
                current.libraryId == expected.libraryId &&
                current.treeUri == expected.treeUri &&
                current.epoch == expected.epoch
            ) current.copy(writable = false, errorCode = errorCode) else current
        }
        suspendResult {
            dao.markExactLibraryRootUnavailable(
                libraryId = expected.libraryId,
                treeUri = expected.treeUri.toString(),
                epoch = expected.epoch,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        }.exceptionOrNull()
            ?.takeIf { it !== error }
            ?.let(error::addSuppressed)
        return mutableState.value
    }

    fun requireSameLibrary(handle: RootHandle) {
        requireSameLibrary(handle.authority())
    }

    fun requireSameLibrary(expected: RootAuthority) {
        val snapshot = state.value
        requireRootAuthority(
            currentReady = snapshot.ready,
            currentLibraryId = snapshot.libraryId,
            currentTreeUri = snapshot.treeUri?.toString(),
            currentEpoch = snapshot.epoch,
            expectedLibraryId = expected.libraryId,
            expectedTreeUri = expected.treeUri,
            expectedEpoch = expected.epoch
        )
    }

    internal fun requireSameIdentity(expected: LibraryRootState) {
        val current = state.value
        check(
            current.libraryId == expected.libraryId &&
                current.treeUri == expected.treeUri &&
                current.epoch == expected.epoch
        ) { "LIBRARY_CHANGED" }
    }

    internal suspend fun <T> withExactRootMutation(
        expected: RootAuthority,
        block: suspend () -> T
    ): T = mutations.withRootMutation {
        requireSameLibrary(expected)
        val result = block()
        requireSameLibrary(expected)
        result
    }

    /**
     * Playlist commit admission requires a live exact root. The root lock then prevents a root
     * replacement until the block ends; a concurrent runtime-access report may only demote this
     * same identity and must not turn an already committed transaction into a reported failure.
     */
    internal suspend fun <T> withExactPlaylistCommitMutation(
        expected: RootAuthority,
        block: suspend () -> T
    ): T = mutations.withRootMutation {
        requireSameLibrary(expected)
        block()
    }

    internal suspend fun checkedSnapshot(handle: RootHandle): SelectedLibrarySnapshot {
        requireSameLibrary(handle)
        val snapshot = selectedLibrarySnapshots.read(handle.rootDocument)
        requireSameLibrary(handle)
        return snapshot
    }

    internal suspend fun checkedRootDirectories(
        handle: RootHandle
    ): List<SelectedDocumentSnapshot> {
        requireSameLibrary(handle)
        val directories = selectedLibrarySnapshots.readRootDirectories(handle.rootDocument)
        requireSameLibrary(handle)
        return directories
    }

    internal suspend fun checkedSongSnapshot(
        handle: RootHandle,
        videoId: String,
    ): SelectedSongSnapshot? {
        requireSameLibrary(handle)
        val snapshot = selectedLibrarySnapshots.readSong(
            handle.rootDocument,
            videoId,
        )
        requireSameLibrary(handle)
        return snapshot
    }

    /** Reopens one durable package directory without depending on a parent/root child listing. */
    internal suspend fun checkedBoundSongSnapshot(
        handle: RootHandle,
        videoId: String,
        boundDirectoryUri: String,
    ): SelectedSongSnapshot {
        requireSameLibrary(handle)
        val uri = runCatching { Uri.parse(boundDirectoryUri) }
            .getOrElse {
                throw LibraryRootException(
                    "PACKAGE_DIRECTORY_IDENTITY_CHANGED",
                    "The durable song directory URI is malformed"
                )
            }
        val snapshot = selectedLibrarySnapshots.readBoundSong(
            handle.rootDocument,
            videoId,
            uri,
        )
        requireSameLibrary(handle)
        return snapshot
    }

    /** Prefer the durable Room directory identity; parent enumeration is recovery-only fallback. */
    internal suspend fun checkedAuthoritativeSongSnapshot(
        handle: RootHandle,
        videoId: String,
        requireDurableIdentity: Boolean = false
    ): SelectedSongSnapshot? {
        requireSameLibrary(handle)
        val durableUri = dao.findSongById(videoId)?.packageDocumentUri?.takeIf(String::isNotBlank)
        val snapshot = if (durableUri != null) {
            checkedBoundSongSnapshot(
                handle,
                videoId,
                durableUri,
            )
        } else if (!requireDurableIdentity) {
            checkedSongSnapshot(handle, videoId)
        } else {
            null
        }
        requireSameLibrary(handle)
        return snapshot
    }

    /**
     * Requests exactly one new direct directory after a checked root snapshot proves absence.
     * The returned URI is intentionally not trusted as exact: collision-renaming providers may
     * return a sibling name, which the caller must journal and clean by this exact identity.
     */
    internal suspend fun createSongDirectoryDocument(handle: RootHandle, videoId: String): Uri {
        requireSameLibrary(handle)
        check(SelectedLibraryContract.isVideoId(videoId)) { "PACKAGE_ID_INVALID" }
        val before = checkedRootDirectories(handle)
        check(before.none { it.displayName == videoId }) { "PACKAGE_DIRECTORY_ALREADY_EXISTS" }
        requireSameLibrary(handle)
        val created = try {
            DocumentsContract.createDocument(
                context.contentResolver,
                handle.rootDocument.uri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                videoId
            ) ?: throw LibraryRootException(
                "PACKAGE_DIRECTORY_UNAVAILABLE",
                "The selected folder provider refused directory creation"
            )
        } catch (error: SecurityException) {
            throw LibraryRootException(
                "ROOT_PERMISSION_LOST",
                "StemWise no longer has permission to write the Library"
            ).also { it.initCause(error) }
        } catch (error: LibraryRootException) {
            throw error
        } catch (error: Throwable) {
            throw LibraryRootException(
                "PACKAGE_DIRECTORY_UNAVAILABLE",
                "The selected folder provider could not create the song directory"
            ).also { it.initCause(error) }
        }
        requireSameLibrary(handle)
        return created
    }

    internal suspend fun checkedBoundDirectory(
        handle: RootHandle,
        directoryUri: Uri
    ): SelectedDocumentSnapshot {
        requireSameLibrary(handle)
        val directory = selectedLibrarySnapshots.readBoundDirectory(
            handle.rootDocument,
            directoryUri
        )
        requireSameLibrary(handle)
        return directory
    }

    internal suspend fun rootContainsBoundDirectoryIdentity(
        handle: RootHandle,
        directoryUri: Uri
    ): Boolean {
        requireSameLibrary(handle)
        val present = selectedLibrarySnapshots.rootContainsBoundDirectoryIdentity(
            handle.rootDocument,
            directoryUri
        )
        requireSameLibrary(handle)
        return present
    }

    /** Deletes only the exact returned provider directory, and only after proving it is empty. */
    internal suspend fun deleteExactEmptyBoundDirectory(handle: RootHandle, directoryUri: Uri) {
        requireSameLibrary(handle)
        val directory = selectedLibrarySnapshots.readBoundDirectory(
            handle.rootDocument,
            directoryUri
        )
        check(
            selectedLibrarySnapshots.boundDirectoryIsEmpty(handle.rootDocument, directoryUri)
        ) { "PACKAGE_DIRECTORY_CLEANUP_REFUSED" }
        requireSameLibrary(handle)
        val deleted = try {
            DocumentsContract.deleteDocument(context.contentResolver, directory.uri)
        } catch (error: SecurityException) {
            throw LibraryRootException(
                "ROOT_PERMISSION_LOST",
                "StemWise no longer has permission to clean the returned directory"
            ).also { it.initCause(error) }
        } catch (error: Throwable) {
            throw LibraryRootException(
                "PACKAGE_DIRECTORY_CLEANUP_PENDING",
                "The exact returned empty directory could not be removed"
            ).also { it.initCause(error) }
        }
        check(deleted) { "PACKAGE_DIRECTORY_CLEANUP_PENDING" }
        requireSameLibrary(handle)
        val reopened = try {
            Result.success(
                selectedLibrarySnapshots.readBoundDirectory(handle.rootDocument, directoryUri)
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
        if (reopened.isSuccess) error("PACKAGE_DIRECTORY_CLEANUP_PENDING")
        val code = (reopened.exceptionOrNull() as? LibraryRootException)?.code
        if (code == "ROOT_PERMISSION_LOST") {
            // Android may report a successfully deleted descendant as SecurityException while
            // enforcing the stale tree URI. A still-readable exact root, after delete=true and a
            // failed exact reopen, distinguishes that from loss of the persisted root grant.
            // Keep this proof shallow: unrelated song evidence must not block exact cleanup.
            check(
                !selectedLibrarySnapshots.rootContainsBoundDirectoryIdentity(
                    handle.rootDocument,
                    directoryUri
                )
            ) { "PACKAGE_DIRECTORY_CLEANUP_PENDING" }
        } else {
            check(code == "PACKAGE_DIRECTORY_UNAVAILABLE") {
                "PACKAGE_DIRECTORY_CLEANUP_PENDING"
            }
        }
        requireSameLibrary(handle)
    }

    /**
     * Creates one direct child through the exact checked parent document URI. Snapshot documents
     * are SingleDocumentFile instances and are read identities only; child creation must use the
     * provider-supported DocumentsContract operation and then prove its returned identity.
     */
    internal suspend fun createExactSongChild(
        handle: RootHandle,
        parent: SelectedSongSnapshot,
        mimeType: String,
        displayName: String,
    ): SelectedDocumentSnapshot {
        requireSameLibrary(handle)
        check((displayName in SelectedLibraryContract.packageFiles ||
            SelectedLibraryContract.isPublicationStagingName(displayName)) && mimeType.isNotBlank()) {
            "ASSET_PATH_INVALID"
        }
        val before = checkedBoundSongSnapshot(
            handle,
            parent.videoId,
            parent.directory.uri.toString(),
        )
        check(
            before.directory.documentId == parent.directory.documentId &&
                before.directory.uri == parent.directory.uri &&
                before.file(displayName) == null
        ) { "ROOT_LAYOUT_UNSUPPORTED" }
        requireSameLibrary(handle)
        val createdUri = try {
            DocumentsContract.createDocument(
                context.contentResolver,
                before.directory.uri,
                providerDocumentCreationMime(displayName, mimeType, documentExtensionMime(displayName)),
                displayName
            ) ?: throw LibraryRootException(
                "ROOT_UNAVAILABLE",
                "The selected folder provider refused child creation"
            )
        } catch (error: SecurityException) {
            throw LibraryRootException(
                "ROOT_PERMISSION_LOST",
                "StemWise no longer has permission to write the Library"
            ).also { it.initCause(error) }
        } catch (error: LibraryRootException) {
            throw error
        } catch (error: Throwable) {
            throw LibraryRootException(
                "ROOT_UNAVAILABLE",
                "The selected folder provider could not create the requested child"
            ).also { it.initCause(error) }
        }
        return try {
            requireSameLibrary(handle)
            val after = checkedBoundSongSnapshot(
                handle,
                parent.videoId,
                parent.directory.uri.toString(),
            )
            check(
                after.directory.documentId == before.directory.documentId &&
                    after.directory.uri == before.directory.uri
            ) { "ROOT_LAYOUT_UNSUPPORTED" }
            val created = after.file(displayName)
                ?: throw LibraryRootException(
                    "ROOT_UNAVAILABLE",
                    "The created selected-folder child disappeared before verification"
                )
            check(
                created.displayName == displayName &&
                    providerCreatedDocumentMimeMatches(displayName, mimeType, created.mimeType, documentExtensionMime(displayName)) &&
                    created.uri == createdUri
            ) { "ROOT_LAYOUT_UNSUPPORTED" }
            requireSameLibrary(handle)
            created
        } catch (original: Throwable) {
            val cleanupFailure = runCatching {
                withContext(NonCancellable) {
                    deleteExactCreatedSongChild(handle, before.directory.uri, createdUri)
                }
            }.exceptionOrNull()
            if (cleanupFailure != null) {
                throw LibraryRootException(
                    "PACKAGE_DIRECTORY_CLEANUP_PENDING",
                    "The exact newly created Library child could not be removed"
                ).also {
                    it.initCause(cleanupFailure)
                    it.addSuppressed(original)
                }
            }
            throw original
        }
    }

    internal suspend fun promotePublicationStagingChild(
        handle: RootHandle,
        parent: SelectedSongSnapshot,
        stagingName: String,
        stagingUri: Uri,
        finalName: String
    ): DocumentFile {
        requireSameLibrary(handle)
        check(SelectedLibraryContract.isPublicationStagingName(stagingName) &&
            finalName in SelectedLibraryContract.packageFiles) { "ASSET_PATH_INVALID" }
        val before = checkedBoundSongSnapshot(handle, parent.videoId, parent.directory.uri.toString())
        check(before.file(stagingName)?.uri == stagingUri && before.file(finalName) == null) { "CANONICAL_ASSET_CONFLICT" }
        val promotedUri = DocumentsContract.renameDocument(context.contentResolver, stagingUri, finalName)
            ?: error("EXTERNAL_COMMIT_FAILED")
        requireSameLibrary(handle)
        val after = checkedBoundSongSnapshot(handle, parent.videoId, parent.directory.uri.toString())
        val promoted = checkNotNull(after.file(finalName)) { "EXTERNAL_COMMIT_FAILED" }
        check(promoted.uri == promotedUri && after.file(stagingName) == null) { "CANONICAL_ASSET_CONFLICT" }
        return promoted.document
    }

    internal suspend fun readExactSongSidecars(
        handle: RootHandle,
        packageId: String,
        names: Set<String>,
        maximumBytes: Long
    ): Map<String, ByteArray> {
        requireSameLibrary(handle)
        check(
            names.isNotEmpty() && names.all { it in SelectedLibraryContract.internalPackageFiles || it == SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST }
        ) { "ASSET_PATH_INVALID" }
        val snapshot = checkedAuthoritativeSongSnapshot(
            handle,
            packageId,
            requireDurableIdentity = false
        ) ?: error("SONG_NOT_FOUND")
        val result = names.mapNotNull { name ->
            snapshot.file(name)?.let { file ->
                name to readBoundedProviderDocument(
                    context,
                    file.uri,
                    file.sizeBytes,
                    maximumBytes,
                    requireNonEmpty = false
                )
            }
        }.toMap()
        requireSameLibrary(handle)
        return result
    }

    internal suspend fun writeExactSongSidecar(
        handle: RootHandle,
        packageId: String,
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
        maximumBytes: Long
    ) {
        requireSameLibrary(handle)
        check(
            (displayName in SelectedLibraryContract.internalPackageFiles || displayName == SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST) &&
                mimeType.isNotBlank() && bytes.isNotEmpty() && bytes.size.toLong() <= maximumBytes
        ) { "ASSET_PATH_INVALID" }
        var snapshot = checkedAuthoritativeSongSnapshot(
            handle,
            packageId,
            requireDurableIdentity = false
        ) ?: error("SONG_NOT_FOUND")
        val target = snapshot.file(displayName) ?: createExactSongChild(
            handle,
            snapshot,
            mimeType,
            displayName,
        )
        requireSameLibrary(handle)
        context.contentResolver.withCancellableProviderOutput(target.uri) { _, write, flush ->
            write(bytes, 0, bytes.size)
            flush()
        } ?: error("EXTERNAL_COMMIT_FAILED")
        snapshot = checkedBoundSongSnapshot(
            handle,
            packageId,
            snapshot.directory.uri.toString(),
        )
        val reopened = snapshot.file(displayName)
            ?.takeIf {
                it.uri == target.uri && providerCreatedDocumentMimeMatches(displayName, mimeType, it.mimeType, documentExtensionMime(displayName))
            }
            ?: error("EXTERNAL_COMMIT_FAILED")
        check(
            readBoundedProviderDocument(
                context,
                reopened.uri,
                reopened.sizeBytes,
                maximumBytes
            ).contentEquals(bytes)
        ) { "EXTERNAL_COMMIT_FAILED" }
        requireSameLibrary(handle)
    }

    internal suspend fun deleteExactSongSidecars(
        handle: RootHandle,
        packageId: String,
        names: Set<String>
    ) {
        requireSameLibrary(handle)
        check(names.all { it in SelectedLibraryContract.internalPackageFiles }) {
            "ASSET_PATH_INVALID"
        }
        var snapshot = checkedAuthoritativeSongSnapshot(
            handle,
            packageId,
            requireDurableIdentity = false
        ) ?: return
        names.forEach { name ->
            val target = snapshot.file(name) ?: return@forEach
            check(DocumentsContract.deleteDocument(context.contentResolver, target.uri)) {
                "EXTERNAL_COMMIT_FAILED"
            }
            snapshot = checkedBoundSongSnapshot(
                handle,
                packageId,
                snapshot.directory.uri.toString(),
            )
            check(snapshot.file(name) == null) { "EXTERNAL_COMMIT_FAILED" }
        }
        requireSameLibrary(handle)
    }

    internal suspend fun readExactRootSidecars(
        handle: RootHandle,
        names: Set<String>,
        maximumBytes: Long,
        snapshot: SelectedLibrarySnapshot? = null
    ): Map<String, ByteArray> {
        requireSameLibrary(handle)
        check(names.isNotEmpty() && names.all { it in SelectedLibraryContract.rootAuthorityFiles }) {
            "ASSET_PATH_INVALID"
        }
        val entries = snapshot?.also {
            check(it.rootUri == handle.rootDocument.uri) { "LIBRARY_CHANGED" }
        }?.rootFiles ?: selectedLibrarySnapshots.readRootEntries(handle.rootDocument)
            .associateBy(SelectedDocumentSnapshot::displayName)
        val result = names.mapNotNull { name ->
            entries[name]?.let { file ->
                name to readBoundedProviderDocument(
                    context,
                    file.uri,
                    file.sizeBytes,
                    maximumBytes,
                    requireNonEmpty = false
                )
            }
        }.toMap()
        requireSameLibrary(handle)
        return result
    }

    internal suspend fun writeExactRootSidecar(
        handle: RootHandle,
        canonicalName: String,
        temporaryName: String,
        bytes: ByteArray,
        maximumBytes: Long
    ) {
        requireSameLibrary(handle)
        check(
            canonicalName in SelectedLibraryContract.rootAuthorityFiles &&
                temporaryName in SelectedLibraryContract.rootAuthorityFiles &&
                canonicalName != temporaryName && bytes.isNotEmpty() &&
                bytes.size.toLong() <= maximumBytes
        ) { "ASSET_PATH_INVALID" }
        writeCandidateRootFile(handle.rootDocument, temporaryName, bytes, maximumBytes)
        requireSameLibrary(handle)
        writeCandidateRootFile(handle.rootDocument, canonicalName, bytes, maximumBytes)
        requireSameLibrary(handle)
        val verified = readExactRootSidecars(handle, setOf(canonicalName), maximumBytes)
        check(verified[canonicalName]?.contentEquals(bytes) == true) { "ROOT_SIDECAR_COMMIT_FAILED" }
        selectedLibrarySnapshots.readRootEntries(handle.rootDocument)
            .singleOrNull { it.displayName == temporaryName }
            ?.let { deleteCandidateRootFile(handle.rootDocument, it) }
        requireSameLibrary(handle)
    }

    /** Durable canonical/backup authority replacement, admitted only under the exact-root lock. */
    internal suspend fun writeExactRootAuthorityCopies(
        handle: RootHandle,
        canonicalName: String,
        backupName: String,
        temporaryName: String,
        bytes: ByteArray,
        maximumBytes: Long
    ) {
        requireSameLibrary(handle)
        val names = setOf(canonicalName, backupName, temporaryName)
        check(
            names.size == 3 && names.all { it in SelectedLibraryContract.rootAuthorityFiles } &&
                bytes.isNotEmpty() && bytes.size.toLong() <= maximumBytes
        ) { "ASSET_PATH_INVALID" }
        writeCandidateRootFile(handle.rootDocument, temporaryName, bytes, maximumBytes)
        requireSameLibrary(handle)
        writeCandidateRootFile(handle.rootDocument, canonicalName, bytes, maximumBytes)
        requireSameLibrary(handle)
        writeCandidateRootFile(handle.rootDocument, backupName, bytes, maximumBytes)
        requireSameLibrary(handle)
        val committed = readExactRootSidecars(handle, names, maximumBytes)
        check(
            committed[canonicalName]?.contentEquals(bytes) == true &&
                committed[backupName]?.contentEquals(bytes) == true
        ) { "ROOT_MANIFEST_COMMIT_FAILED" }
        selectedLibrarySnapshots.readRootEntries(handle.rootDocument)
            .singleOrNull { it.displayName == temporaryName }
            ?.let { deleteCandidateRootFile(handle.rootDocument, it) }
        requireSameLibrary(handle)
    }

    private suspend fun deleteExactCreatedSongChild(
        handle: RootHandle,
        parentUri: Uri,
        createdUri: Uri
    ) {
        check(selectedBoundUriMatchesRoot(handle.rootDocument.uri, createdUri)) {
            "PACKAGE_DIRECTORY_CLEANUP_PENDING"
        }
        var deletionReportedSuccess = false
        val deletionFailure = try {
            deletionReportedSuccess = DocumentsContract.deleteDocument(
                context.contentResolver,
                createdUri
            )
            null
        } catch (error: Throwable) {
            error
        }
        val absenceFailure = runCatching {
            check(
                exactDocumentIsAbsent(
                    handle,
                    parentUri,
                    createdUri,
                    deletionReportedSuccess
                )
            ) { "PACKAGE_DIRECTORY_CLEANUP_PENDING" }
        }.exceptionOrNull()
        if (absenceFailure != null) {
            throw IllegalStateException(
                "PACKAGE_DIRECTORY_CLEANUP_PENDING",
                deletionFailure ?: absenceFailure
            ).also {
                if (deletionFailure != null) it.addSuppressed(absenceFailure)
            }
        }
    }

    private suspend fun exactDocumentIsAbsent(
        handle: RootHandle,
        parentUri: Uri,
        exactUri: Uri,
        deletionReportedSuccess: Boolean
    ): Boolean {
        val expectedDocumentId = DocumentsContract.getDocumentId(exactUri)
        return try {
            context.contentResolver.queryCancellable(
                exactUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            ) { cursor ->
                cursor.requireSuccessfulSelectedLibraryQuery()
                val idIndex = cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID
                )
                check(idIndex >= 0) { "PACKAGE_DIRECTORY_CLEANUP_PENDING" }
                if (!cursor.moveToNext()) {
                    true
                } else {
                    check(
                        cursor.getString(idIndex) == expectedDocumentId && !cursor.moveToNext()
                    ) { "PACKAGE_DIRECTORY_CLEANUP_PENDING" }
                    false
                }
            } ?: deletionReportedSuccess && exactParentRemainsReadable(handle, parentUri)
        } catch (_: FileNotFoundException) {
            true
        } catch (_: SecurityException) {
            deletionReportedSuccess && exactParentRemainsReadable(handle, parentUri)
        }
    }

    private suspend fun exactParentRemainsReadable(handle: RootHandle, parentUri: Uri): Boolean =
        runCatching {
            selectedLibrarySnapshots.readBoundDirectory(handle.rootDocument, parentUri)
        }.isSuccess

    /**
     * Room selection may already be committed when external work or session activation fails.
     * Keep that exact selection non-ready until reconnect completes recovery.
     */
    suspend fun retainCommittedRootForRecovery(expected: LibraryRootState): LibraryRootState =
        withContext(Dispatchers.IO) {
            mutations.withRootMutation {
                val current = state.value
                check(
                    current.libraryId == expected.libraryId &&
                        current.treeUri == expected.treeUri &&
                        current.epoch == expected.epoch
                ) { "LIBRARY_CHANGED" }
                val restoring = current.copy(
                    writable = false,
                    errorCode = "ROOT_RESTORING"
                )
                mutableState.value = restoring
                persistActiveIdentity(restoring)
                restoring
            }
        }

    /** Publishes a staged candidate after its Room-backed playlist/session projection is ready. */
    suspend fun publishCommittedRoot(expected: LibraryRootState): LibraryRootState =
        withContext(Dispatchers.IO) {
            mutations.withRootMutation {
                val current = state.value
                check(
                    current.libraryId == expected.libraryId &&
                        current.treeUri == expected.treeUri &&
                        current.epoch == expected.epoch &&
                        !current.ready &&
                        current.errorCode == "ROOT_RESTORING" &&
                        expected.ready
                ) { "LIBRARY_CHANGED" }
                check(dao.libraryRootIdentity(ACTIVE_ROOT_SLOT)?.matches(expected) == true) {
                    "ROOT_IDENTITY_CHANGED"
                }
                persistActiveIdentity(expected)
                mutableState.value = expected
                expected
            }
        }

    internal suspend fun resolveAuthoritativeSongAsset(
        handle: RootHandle,
        videoId: String,
        fileName: String
    ): Uri? {
        requireSameLibrary(handle)
        if (
            !SelectedLibraryContract.isVideoId(videoId) ||
            fileName !in SelectedLibraryContract.allMediaFiles
        ) return null
        val snapshot = checkedAuthoritativeSongSnapshot(handle, videoId) ?: return null
        return (if (fileName == SelectedLibraryContract.ORIGINAL) snapshot.original
                else snapshot.file(fileName))?.uri
    }

    suspend fun configuredPlaylistIdentity(): PlaylistLink? = withContext(Dispatchers.IO) {
        state.value.libraryId?.let { configuredPlaylistIdentity(it) }
    }

    private suspend fun configuredPlaylistIdentity(libraryId: String): PlaylistLink? =
        dao.selectedPlaylist(libraryId)?.let { row ->
            PlaylistLinks.parse(row.playlistUrl)?.also {
                check(it.id == row.playlistId) { "PLAYLIST_AUTHORITY_INVALID" }
            } ?: error("PLAYLIST_AUTHORITY_INVALID")
        }

    private suspend fun readPersistedSnapshot(): PersistedRootSnapshot {
        val identity = selectedIdentityWithRetainedOperationGuard()
        if (identity == null) return PersistedRootSnapshot(LibraryRootState())
        val base = identity.toRootState(errorCode = "ROOT_RESTORING")
        if (!identity.hasSelectedRootIdentity()) return PersistedRootSnapshot(
            base.copy(errorCode = "ROOT_IDENTITY_UNVERIFIED")
        )
        val uri = checkNotNull(base.treeUri)
        val persistedPermission = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && persistedSafGrantIsUsable(it.isReadPermission, it.isWritePermission)
        }
        val developmentPermission = BuildConfig.BUILD_TYPE == "development" &&
            context.packageName == DEVELOPMENT_APPLICATION_ID &&
            uri.authority == "$DEVELOPMENT_APPLICATION_ID.golden.qa.documents" &&
            context.checkUriPermission(uri, Process.myPid(), Process.myUid(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
        val appOwnedPermission = isAppOwnedUiDemoTreeUri(context.packageName, uri)
        if (!persistedPermission && !developmentPermission && !appOwnedPermission) {
            val unavailable = base.copy(errorCode = "ROOT_PERMISSION_LOST")
            persistActiveIdentity(unavailable)
            return PersistedRootSnapshot(unavailable)
        }
        return try {
            val root = DocumentFile.fromTreeUri(context, uri)
                ?: throw IOException("The library provider is unavailable")
            check(root.isDirectory && root.canRead()) { "ROOT_UNAVAILABLE" }
            PersistedRootSnapshot(base.copy(displayName = root.name ?: "StemWiseLibrary"), root)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val unavailable = base.copy(errorCode = (error as? LibraryRootException)?.code
                ?: runtimeLibraryRootErrorCode(error))
            persistActiveIdentity(unavailable)
            PersistedRootSnapshot(unavailable)
        }
    }

    private fun LibraryRootIdentityEntity.hasSelectedRootIdentity(): Boolean =
        isCanonicalLibraryId(libraryId) && treeUri.isNotBlank() && epoch > 0L &&
            state in setOf("VERIFIED", "RESTORING", "UNAVAILABLE")

    private fun LibraryRootIdentityEntity.matches(value: LibraryRootState): Boolean =
        libraryId == value.libraryId && treeUri == value.treeUri?.toString() && epoch == value.epoch

    private fun LibraryRootIdentityEntity.toRootState(errorCode: String): LibraryRootState =
        LibraryRootState(treeUri = treeUri.takeIf(String::isNotBlank)?.toUri(),
            libraryId = libraryId, epoch = epoch, writable = false, errorCode = errorCode)

    private suspend fun selectedIdentityWithRetainedOperationGuard(): LibraryRootIdentityEntity? {
        val identity = dao.libraryRootIdentity(ACTIVE_ROOT_SLOT)
        check(!dao.hasRootSwitchBlockingSeparationSafetyFacts() || identity?.hasSelectedRootIdentity() == true) {
            "ROOT_IDENTITY_UNVERIFIED"
        }
        return identity
    }

    private data class PersistedRootSnapshot(
        val state: LibraryRootState,
        val rootDocument: DocumentFile? = null
    ) {
        val eligibleForRestoration: Boolean get() = rootDocument != null
    }

    private suspend fun validateCandidateInternal(treeUri: Uri): ValidatedRootCandidate {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw LibraryRootException("ROOT_UNAVAILABLE", "The selected folder cannot be opened")
        if (!root.isDirectory || !root.canRead() || !root.canWrite()) {
            throw LibraryRootException(
                "ROOT_UNAVAILABLE",
                "The selected library folder must be readable and writable"
            )
        }
        val persistedIdentity = selectedIdentityWithRetainedOperationGuard()
        val persistedUri = persistedIdentity?.treeUri
        val persistedId = persistedIdentity?.libraryId
        val persistedEpoch = persistedIdentity?.epoch ?: 0L
        val authenticatedLibraryId = persistedId?.takeIf {
            persistedEpoch > 0L && isCanonicalLibraryId(it) &&
                reconnectsExactTreeUri(persistedUri, treeUri.toString())
        }
        val selectedSnapshot = selectedLibrarySnapshots.read(root)
        val inspections = selectedSnapshot.songDirectories
        val portableId = portableLibraryId(selectedSnapshot.rootFiles, authenticatedLibraryId)
        val candidateId = if (portableId != null) {
            check(
                !reconnectsExactTreeUri(persistedUri, treeUri.toString()) ||
                    persistedId.isNullOrBlank() || persistedId == portableId
            ) { "ROOT_MANIFEST_INVALID" }
            portableId
        } else if (
            reconnectsExactTreeUri(persistedUri, treeUri.toString()) &&
            !persistedId.isNullOrBlank()
        ) {
            persistedId
        } else {
            UUID.randomUUID().toString()
        }
        return ValidatedRootCandidate(
            treeUri,
            root,
            candidateId,
            existingEntryCount = selectedSnapshot.totalEntryCount,
            existingSongCount = inspections.size
        )
    }

    private fun takePersistentPermission(treeUri: Uri) {
        if (isAppOwnedUiDemoTreeUri(context.packageName, treeUri)) return
        try {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (error: SecurityException) {
            throw LibraryRootException(
                "ROOT_PERMISSION_LOST",
                "StemWise could not retain access to the selected folder"
            ).also { it.addSuppressed(error) }
        }
    }

    private suspend fun commitCandidate(
        candidate: ValidatedRootCandidate,
        publishVerified: Boolean
    ): LibraryRootState {
        check(candidate.rootDocument.isDirectory && candidate.rootDocument.canRead() && candidate.rootDocument.canWrite()) {
            "ROOT_UNAVAILABLE"
        }
        val selectedSnapshot = selectedLibrarySnapshots.read(candidate.rootDocument)
        check(portableLibraryId(selectedSnapshot.rootFiles, candidate.libraryId)?.let { it == candidate.libraryId } != false) {
            "ROOT_MANIFEST_INVALID"
        }
        val previous = dao.libraryRootIdentity(ACTIVE_ROOT_SLOT)
        val epoch = nextEpoch(candidate.treeUri, previous)
        val committed = LibraryRootState(
            treeUri = candidate.treeUri,
            libraryId = candidate.libraryId,
            displayName = candidate.rootDocument.name ?: "StemWiseLibrary",
            writable = true,
            epoch = epoch
        )
        val selected = LibraryRootIdentityEntity(ACTIVE_ROOT_SLOT, candidate.libraryId,
            candidate.treeUri.toString(), epoch, "RESTORING", System.currentTimeMillis())
        dao.commitRootSelection(previous, selected)
        mutableState.value = committed.copy(writable = false, errorCode = "ROOT_RESTORING")
        ensurePortableLibraryManifest(candidate, selectedSnapshot)
        if (publishVerified) {
            persistActiveIdentity(committed)
            mutableState.value = committed
        }
        return committed
    }

    private suspend fun restoreRootAfterFailedCommit(
        previous: LibraryRootState,
        candidate: ValidatedRootCandidate,
        failure: Throwable,
        releaseCandidatePermission: Boolean
    ) {
        val selected = try { dao.libraryRootIdentity(ACTIVE_ROOT_SLOT) }
        catch (readFailure: Throwable) {
            if (readFailure !== failure) failure.addSuppressed(readFailure)
            mutableState.value = state.value.copy(writable = false, errorCode = "ROOT_UNAVAILABLE")
            return
        }
        mutableState.value = when {
            selected == null -> LibraryRootState()
            selected.matches(previous) && selected.state == "VERIFIED" && previous.ready -> previous
            else -> selected.toRootState("ROOT_RESTORING")
        }
        if (releaseCandidatePermission && shouldReleaseFailedCandidatePermission(
                previous.treeUri?.toString(), selected?.treeUri, selected?.libraryId,
                candidate.treeUri.toString(), candidate.libraryId)) {
            releasePersistentPermission(candidate.treeUri)
        }
    }

    private suspend fun persistActiveIdentity(value: LibraryRootState) {
        val libraryId = value.libraryId ?: return
        val treeUri = value.treeUri ?: return
        val status = when {
            value.ready -> "VERIFIED"
            value.errorCode == "ROOT_RESTORING" || value.errorCode == "ROOT_CHECKING" -> "RESTORING"
            else -> "UNAVAILABLE"
        }
        check(dao.markExactLibraryRootState(libraryId, treeUri.toString(), value.epoch,
            status, System.currentTimeMillis()) == 1) { "ROOT_IDENTITY_CHANGED" }
    }

    private fun nextEpoch(treeUri: Uri, previous: LibraryRootIdentityEntity?): Long =
        if (previous?.treeUri == treeUri.toString()) previous.epoch.coerceAtLeast(1L)
        else (previous?.epoch ?: 0L).coerceAtLeast(0L) + 1L

    private suspend fun portableLibraryId(
        rootFiles: Map<String, SelectedDocumentSnapshot>,
        authenticatedLibraryId: String? = null
    ): String? {
        val manifestFiles = ROOT_MANIFEST_FILES.mapNotNull(rootFiles::get)
        val copies = manifestFiles.associate { file ->
            file.displayName to readBoundedProviderDocument(
                context,
                file.uri,
                file.sizeBytes,
                MAX_ROOT_MANIFEST_BYTES,
                requireNonEmpty = false
            )
        }
        return selectPortableLibraryIdCopies(
            copies,
            ::parsePortableLibraryId,
            authenticatedLibraryId?.let { portableLibraryManifest(it) to it }
        )
    }

    private suspend fun ensurePortableLibraryManifest(
        candidate: ValidatedRootCandidate,
        initialSnapshot: SelectedLibrarySnapshot? = null
    ) {
        check(dao.libraryRootIdentity(ACTIVE_ROOT_SLOT)?.let {
            it.libraryId == candidate.libraryId && it.treeUri == candidate.treeUri.toString() && it.epoch > 0L
        } == true) { "ROOT_MANIFEST_INVALID" }
        suspend fun rootFiles() = selectedLibrarySnapshots.readRootEntries(candidate.rootDocument)
            .filterNot(SelectedDocumentSnapshot::directory).associateBy(SelectedDocumentSnapshot::displayName)
        var files = initialSnapshot?.rootFiles ?: rootFiles()
        check(
            portableLibraryId(files, candidate.libraryId)
                ?.let { it == candidate.libraryId } != false
        ) {
            "ROOT_MANIFEST_INVALID"
        }
        val bytes = portableLibraryManifest(candidate.libraryId)
        val canonical = files[SelectedLibraryContract.ROOT_MANIFEST]
        val backup = files[SelectedLibraryContract.ROOT_MANIFEST_BACKUP]
        val canonicalValid = canonical?.let { file ->
            runCatching {
                readBoundedProviderDocument(
                    context,
                    file.uri,
                    file.sizeBytes,
                    MAX_ROOT_MANIFEST_BYTES
                ).contentEquals(bytes)
            }.getOrDefault(false)
        } == true
        val backupValid = backup?.let { file ->
            runCatching {
                readBoundedProviderDocument(
                    context,
                    file.uri,
                    file.sizeBytes,
                    MAX_ROOT_MANIFEST_BYTES
                ).contentEquals(bytes)
            }.getOrDefault(false)
        } == true
        if (
            canonicalValid && backupValid &&
            files[SelectedLibraryContract.ROOT_MANIFEST_TEMP] == null
        ) return
        if (!canonicalValid || !backupValid) {
            writeCandidateRootFile(
                candidate.rootDocument,
                SelectedLibraryContract.ROOT_MANIFEST_TEMP,
                bytes,
                MAX_ROOT_MANIFEST_BYTES
            )
            writeCandidateRootFile(
                candidate.rootDocument,
                SelectedLibraryContract.ROOT_MANIFEST,
                bytes,
                MAX_ROOT_MANIFEST_BYTES
            )
            writeCandidateRootFile(
                candidate.rootDocument,
                SelectedLibraryContract.ROOT_MANIFEST_BACKUP,
                bytes,
                MAX_ROOT_MANIFEST_BYTES
            )
        }
        files = rootFiles()
        check(
            portableLibraryId(files, candidate.libraryId) == candidate.libraryId &&
                files[SelectedLibraryContract.ROOT_MANIFEST] != null &&
                files[SelectedLibraryContract.ROOT_MANIFEST_BACKUP] != null
        ) { "ROOT_MANIFEST_INVALID" }
        files[SelectedLibraryContract.ROOT_MANIFEST_TEMP]
            ?.let { deleteCandidateRootFile(candidate.rootDocument, it) }
        check(
            portableLibraryId(
                rootFiles(),
                candidate.libraryId
            ) == candidate.libraryId
        ) { "ROOT_MANIFEST_INVALID" }
    }

    private suspend fun writeCandidateRootFile(
        root: DocumentFile,
        displayName: String,
        bytes: ByteArray,
        maximumBytes: Long
    ) {
        check(
            displayName in SelectedLibraryContract.rootAuthorityFiles &&
                bytes.isNotEmpty() && bytes.size.toLong() <= maximumBytes
        ) { "ROOT_MANIFEST_INVALID" }
        var entries = selectedLibrarySnapshots.readRootEntries(root)
        var target = entries.singleOrNull { it.displayName == displayName }
        if (target == null) {
            val createdUri = DocumentsContract.createDocument(
                context.contentResolver,
                root.uri,
                providerDocumentCreationMime(displayName, "application/json", documentExtensionMime(displayName)),
                displayName
            ) ?: error("ROOT_MANIFEST_COMMIT_FAILED")
            try {
                entries = selectedLibrarySnapshots.readRootEntries(root)
                target = entries.singleOrNull { it.displayName == displayName }
                check(
                    target != null && target.uri == createdUri && !target.directory &&
                        providerCreatedDocumentMimeMatches(displayName, "application/json", target.mimeType, documentExtensionMime(displayName))
                ) { "ROOT_MANIFEST_COMMIT_FAILED" }
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    runCatching {
                        DocumentsContract.deleteDocument(context.contentResolver, createdUri)
                    }
                }
                throw error
            }
        }
        val exactTarget = checkNotNull(target)
        context.contentResolver.withCancellableProviderOutput(exactTarget.uri) { _, write, flush ->
            write(bytes, 0, bytes.size)
            flush()
        } ?: error("ROOT_MANIFEST_COMMIT_FAILED")
        val reopened = selectedLibrarySnapshots.readRootEntries(root)
            .singleOrNull { it.displayName == displayName && it.uri == exactTarget.uri }
            ?: error("ROOT_MANIFEST_COMMIT_FAILED")
        check(
            readBoundedProviderDocument(
                context,
                reopened.uri,
                reopened.sizeBytes,
                maximumBytes
            ).contentEquals(bytes)
        ) { "ROOT_MANIFEST_COMMIT_FAILED" }
    }

    private suspend fun deleteCandidateRootFile(
        root: DocumentFile,
        file: SelectedDocumentSnapshot
    ) {
        check(file.displayName in SelectedLibraryContract.rootAuthorityFiles && !file.directory) {
            "ROOT_MANIFEST_INVALID"
        }
        check(DocumentsContract.deleteDocument(context.contentResolver, file.uri)) {
            "ROOT_MANIFEST_COMMIT_FAILED"
        }
        check(selectedLibrarySnapshots.readRootEntries(root).none { it.uri == file.uri }) {
            "ROOT_MANIFEST_COMMIT_FAILED"
        }
    }

    companion object {
        private const val ACTIVE_ROOT_SLOT = "active"

        fun safeRelativeSegments(path: String): List<String>? {
            if (
                path.isBlank() || path.startsWith('/') || path.startsWith('\\') ||
                path.contains('\\') || path.contains('\u0000') ||
                Regex("^[A-Za-z]:").containsMatchIn(path)
            ) return null
            val segments = path.split('/')
            if (segments.any { it.isBlank() || it == "." || it == ".." }) return null
            return segments
        }
    }
}

private const val DEVELOPMENT_APPLICATION_ID = "app.stemwise.development"

class LibraryRootException(val code: String, message: String) : IOException(message)

internal fun runtimeLibraryRootErrorCode(error: Throwable): String {
    val chain = generateSequence(error) { it.cause }
    if (chain.any { it is SecurityException }) return "ROOT_PERMISSION_LOST"
    return generateSequence(error) { it.cause }
        .filterIsInstance<LibraryRootException>()
        .map(LibraryRootException::code)
        .firstOrNull {
            it == "ROOT_PERMISSION_LOST" ||
                it == "ROOT_UNAVAILABLE" ||
                it == "ROOT_LAYOUT_UNSUPPORTED"
        }
        ?: "ROOT_UNAVAILABLE"
}

private fun documentExtensionMime(name: String): String? =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', ""))
