package app.stemwise.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.stemwise.enrichment.parseStrictManifestJson
import app.stemwise.integration.MediaIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal const val MAX_PORTABLE_SOURCE_BINDING_BYTES = 4 * 1024 * 1024



private val SOURCE_BINDING_STATES =
    setOf("ACTIVE", "DELETED")
internal val PORTABLE_SOURCE_BINDING_STATES = setOf("DELETED")
private val SAFE_SOURCE_BINDING_TRANSACTION = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
private val SOURCE_BINDING_DOMAIN_ERRORS = setOf(
    "SOURCE_BINDING_AUTHORITY_INVALID",
    "SOURCE_BINDING_CONFLICT",
    "SOURCE_RESERVATION_MISSING"
)
internal fun sourceBindingTransitionAllowed(
    existingState: String,
    targetState: String,
    samePackage: Boolean
): Boolean {
    return samePackage && (existingState == targetState ||
        existingState == "ACTIVE" && targetState == "DELETED")
}

internal fun strongestSourceBinding(candidates: List<SourceBindingEntity>): SourceBindingEntity {
    require(candidates.isNotEmpty())
    val first = candidates.first()
    candidates.forEach { validateSourceBindingEntity(it, first.libraryId) }
    check(candidates.all { it.sourceId == first.sourceId && it.packageId == first.packageId }) {
        "SOURCE_BINDING_AUTHORITY_INVALID"
    }
    return candidates.filter { it.state in PORTABLE_SOURCE_BINDING_STATES }
        .maxWithOrNull(compareBy<SourceBindingEntity>(
            { if (it.state == "DELETED") 1 else 0 }, SourceBindingEntity::updatedAtEpochMs
        )) ?: candidates.single()
}

internal fun mergePortableBlockedBindings(
    libraryId: String,
    roomBindings: List<SourceBindingEntity>,
    portableBindings: List<SourceBindingEntity>
): List<SourceBindingEntity> {
    (roomBindings + portableBindings).forEach { validateSourceBindingEntity(it, libraryId) }
    check(portableBindings.all { it.state in PORTABLE_SOURCE_BINDING_STATES }) {
        "SOURCE_BINDING_AUTHORITY_INVALID"
    }
    val roomBySource = roomBindings.associateBy(SourceBindingEntity::sourceId)
    portableBindings.forEach { portable ->
        roomBySource[portable.sourceId]?.let { room ->
            check(room.packageId == portable.packageId) { "SOURCE_BINDING_AUTHORITY_INVALID" }
        }
    }
    return (roomBindings.filter { it.state in PORTABLE_SOURCE_BINDING_STATES } + portableBindings)
        .groupBy(SourceBindingEntity::sourceId)
        .map { (_, candidates) ->
            check(candidates.map(SourceBindingEntity::packageId).distinct().size == 1) {
                "SOURCE_BINDING_AUTHORITY_INVALID"
            }
            strongestSourceBinding(candidates)
        }
        .sortedBy(SourceBindingEntity::sourceId)
}

internal fun validateSourceBindingEntity(value: SourceBindingEntity, expectedLibraryId: String) {
    check(
        value.libraryId == expectedLibraryId &&
            value.state in SOURCE_BINDING_STATES &&
            SAFE_SOURCE_BINDING_TRANSACTION.matches(value.transactionId) &&
            value.updatedAtEpochMs > 0L
    ) { "SOURCE_BINDING_AUTHORITY_INVALID" }
    runCatching { requireSourceAndPackage(value.sourceId, value.packageId) }
        .getOrElse { error("SOURCE_BINDING_AUTHORITY_INVALID") }
}

internal data class SourceBindingScanAuthority(
    val root: RootAuthority,
    val generation: Long,
    val blockedSourceIds: Set<String>
)

/** Room source projection plus portable selected-root deletion authority. */
class SourceBindingRegistry(
    private val context: Context,
    private val roots: LibraryRootManager,
    private val mutations: PackageMutationCoordinator,
    private val dao: LibraryDao
) {
    private val tombstoneMutex = Mutex()
    private val scanGeneration = java.util.concurrent.atomic.AtomicLong()

    internal fun invalidateScanAuthority() { scanGeneration.incrementAndGet() }

    internal suspend fun scanAuthority(
        root: RootHandle,
        previous: SourceBindingScanAuthority? = null,
        snapshot: SelectedLibrarySnapshot? = null
    ): SourceBindingScanAuthority {
        roots.requireSameLibrary(root)
        if (previous?.root == root.authority() && previous.generation == scanGeneration.get()) return previous
        repeat(3) { attempt ->
            val generation = scanGeneration.get()
            val blocked = blockedSourceIds(root, snapshot.takeIf { previous == null && attempt == 0 })
            roots.requireSameLibrary(root)
            if (generation == scanGeneration.get()) return SourceBindingScanAuthority(root.authority(), generation, blocked)
        }
        error("SOURCE_BINDING_SCAN_CHANGED")
    }

    private fun requireScanAuthority(root: RootHandle, authority: SourceBindingScanAuthority) {
        roots.requireSameLibrary(root)
        check(authority.root == root.authority() && authority.generation == scanGeneration.get()) {
            "SOURCE_BINDING_SCAN_CHANGED"
        }
    }

    suspend fun activateUnderPackageLock(
        expectedRoot: RootHandle,
        sourceId: String,
        packageId: String,
        transactionId: String,
        journal: PackageMutationCoordinator.Journal
    ) = update(expectedRoot, sourceId, packageId, transactionId, "ACTIVE", journal)

    suspend fun markDeletedUnderPackageLock(
        expectedRoot: RootHandle,
        sourceId: String,
        packageId: String,
        transactionId: String,
        journal: PackageMutationCoordinator.Journal
    ) = update(expectedRoot, sourceId, packageId, transactionId, "DELETED", journal)

    /** Caller holds root mutation ownership and has settled every older deletion of this package. */
    internal suspend fun retireDeletedSourceUnderRootLock(expectedRoot: RootHandle, packageId: String) {
        roots.requireSameLibrary(expectedRoot)
        val sourceId = "youtube-video:$packageId"
        requireSourceAndPackage(sourceId, packageId)
        ensureRoomAuthority(expectedRoot)
        val current = dao.sourceBinding(expectedRoot.libraryId, sourceId) ?: return
        validateSourceBindingEntity(current, expectedRoot.libraryId)
        check(current.packageId == packageId && current.state == "DELETED") { "SOURCE_BINDING_CONFLICT" }
        val retained = dao.blockedSourceBindings(expectedRoot.libraryId).filterNot { it.sourceId == sourceId }
        persistPortableBlockedAuthority(expectedRoot, retained)
        checkedRoomMutation { dao.deleteSourceBindings(expectedRoot.libraryId, listOf(sourceId)) }
        check(sourceId !in blockedSourceIds(expectedRoot)) { "SOURCE_BINDING_RECOVERY_PENDING" }
        roots.requireSameLibrary(expectedRoot)
    }

    internal suspend fun activateVerifiedPackageUnderRootLock(expectedRoot: RootHandle, packageId: String,
        scanAuthority: SourceBindingScanAuthority? = null) =
        update(expectedRoot, "youtube-video:$packageId", packageId, "verified-rebuild", "ACTIVE", null, scanAuthority)

    suspend fun activePackageIdsBySource(expectedRoot: RootHandle): Map<String, String> {
        roots.requireSameLibrary(expectedRoot)
        ensureRoomAuthority(expectedRoot)
        val rows = dao.activeSourceBindings(expectedRoot.libraryId)
        rows.forEach { validateSourceBindingEntity(it, expectedRoot.libraryId) }
        roots.requireSameLibrary(expectedRoot)
        return rows.associate { it.sourceId to it.packageId }
    }

    suspend fun blockedSourceIds(expectedRoot: RootHandle): Set<String> =
        blockedSourceIds(expectedRoot, null)

    internal suspend fun blockedSourceIds(
        expectedRoot: RootHandle,
        snapshot: SelectedLibrarySnapshot?
    ): Set<String> {
        roots.requireSameLibrary(expectedRoot)
        ensureRoomAuthority(expectedRoot, snapshot)
        val rows = dao.blockedSourceBindings(expectedRoot.libraryId)
        rows.forEach { validateSourceBindingEntity(it, expectedRoot.libraryId) }
        roots.requireSameLibrary(expectedRoot)
        return rows.mapTo(linkedSetOf(), SourceBindingEntity::sourceId)
    }

    internal suspend fun confirmedDeletedPackageIds(expectedRoot: RootHandle): Set<String> {
        roots.requireSameLibrary(expectedRoot)
        ensureRoomAuthority(expectedRoot)
        return dao.blockedSourceBindings(expectedRoot.libraryId)
            .onEach { validateSourceBindingEntity(it, expectedRoot.libraryId) }
            .filter { it.state == "DELETED" }
            .mapTo(linkedSetOf(), SourceBindingEntity::packageId)
            .also { roots.requireSameLibrary(expectedRoot) }
    }

    internal suspend fun rebuildFromVerifiedBindingsUnderRootLock(
        expectedRoot: RootHandle,
        verifiedBindings: Map<String, String>,
        snapshot: SelectedLibrarySnapshot? = null
    ) {
        roots.requireSameLibrary(expectedRoot)
        ensureRoomAuthority(expectedRoot, snapshot)
        val updatedAt = System.currentTimeMillis().coerceAtLeast(1L)
        val desired = verifiedBindings.toSortedMap().map { (sourceId, packageId) ->
            requireSourceAndPackage(sourceId, packageId)
            SourceBindingEntity(
                libraryId = expectedRoot.libraryId,
                sourceId = sourceId,
                packageId = packageId,
                state = "ACTIVE",
                transactionId = "verified-rebuild",
                updatedAtEpochMs = updatedAt
            )
        }
        checkedRoomMutation {
            dao.rebuildVerifiedSourceBindings(expectedRoot.libraryId, desired)
        }
        roots.requireSameLibrary(expectedRoot)
    }

    private suspend fun update(
        expectedRoot: RootHandle,
        sourceId: String,
        packageId: String,
        transactionId: String,
        targetState: String,
        journal: PackageMutationCoordinator.Journal?,
        scanAuthority: SourceBindingScanAuthority? = null
    ) {
        roots.requireSameLibrary(expectedRoot)
        requireSourceAndPackage(sourceId, packageId)
        require(targetState in SOURCE_BINDING_STATES) { "SOURCE_STATE_INVALID" }
        require(SAFE_SOURCE_BINDING_TRANSACTION.matches(transactionId)) { "TRANSACTION_ID_INVALID" }
        if (scanAuthority != null) requireScanAuthority(expectedRoot, scanAuthority)
        else ensureRoomAuthority(expectedRoot)
        val existing = dao.sourceBinding(expectedRoot.libraryId, sourceId)?.also {
            validateSourceBindingEntity(it, expectedRoot.libraryId)
        }
        if (existing != null) {
            val samePackage = existing.packageId == packageId
            check(samePackage) { "SOURCE_BINDING_CONFLICT" }
            check(sourceBindingTransitionAllowed(existing.state, targetState, samePackage)) {
                "SOURCE_BINDING_CONFLICT"
            }
            if (existing.state == targetState) {
                if (existing.state in PORTABLE_SOURCE_BINDING_STATES) {
                    persistPortableBlockedAuthority(expectedRoot)
                }
                return
            }
        } else {
            check(targetState == "ACTIVE") { "SOURCE_RESERVATION_MISSING" }
        }
        roots.requireSameLibrary(expectedRoot)
        journal?.write(
            "SOURCE_BINDING_COMMIT",
            JSONObject().put("sourceId", sourceId).put("targetState", targetState)
        )
        val nextBinding = sourceBindingEntity(
            expectedRoot.libraryId,
            sourceId,
            packageId,
            targetState,
            transactionId
        )
        if (targetState in PORTABLE_SOURCE_BINDING_STATES) {
            val portableBindings = dao.blockedSourceBindings(expectedRoot.libraryId)
                .filterNot { it.sourceId == sourceId } + nextBinding
            persistPortableBlockedAuthority(expectedRoot, portableBindings)
        }
        checkedRoomMutation {
            dao.upsertSourceBinding(nextBinding)
        }
        roots.requireSameLibrary(expectedRoot)
    }

    private suspend fun ensureRoomAuthority(
        expectedRoot: RootHandle,
        snapshot: SelectedLibrarySnapshot? = null
    ) {
        roots.requireSameLibrary(expectedRoot)
        val libraryId = expectedRoot.libraryId
        val roomBindings = dao.sourceBindingsForLibrary(libraryId)
        val portable = portableBlockedAuthority(expectedRoot, snapshot)?.bindings.orEmpty()
        val merged = mergePortableBlockedBindings(libraryId, roomBindings, portable)
        fun List<SourceBindingEntity>.facts() = associate { it.sourceId to
            Triple(it.packageId, it.state, it.updatedAtEpochMs) }
        if (portable.facts() != merged.facts()) persistPortableBlockedAuthority(expectedRoot, merged)
        if (roomBindings.filter { it.state in PORTABLE_SOURCE_BINDING_STATES }.facts() != merged.facts()) {
            roots.requireSameLibrary(expectedRoot)
            checkedRoomMutation { dao.upsertSourceBindings(merged) }
        }
        roots.requireSameLibrary(expectedRoot)
    }

    private fun tombstoneIntentFile(root: RootHandle): File {
        val identity = "${root.libraryId}\n${root.treeUri}"
        val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8)).toHexString()
        return File(context.filesDir, "source-tombstones-$key.json")
    }

    private fun retainTombstoneIntent(root: RootHandle, bytes: ByteArray) {
        val authority = decodePortableBlockedAuthority(root.libraryId, parseStrictManifestJson(bytes))
        val record = JSONObject().put("schemaVersion", 1).put("libraryId", root.libraryId)
            .put("treeUri", root.treeUri.toString()).put("rootEpoch", root.epoch)
            .put("revision", authority.revision).put("authority", bytes.toString(Charsets.UTF_8))
        val target = AtomicFile(tombstoneIntentFile(root))
        val output = target.startWrite()
        try {
            output.write(record.toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(output)
        } catch (failure: Throwable) {
            target.failWrite(output)
            throw failure
        }
    }

    private suspend fun readBlockedAuthority(
        root: RootHandle,
        snapshot: SelectedLibrarySnapshot? = null
    ): PortableBlockedAuthority? {
        roots.requireSameLibrary(root)
        val file = tombstoneIntentFile(root)
        val intent = readAtomicJournal(file, "SOURCE_BINDING_AUTHORITY_INVALID")?.value
        val intended = intent?.let { value ->
            check(value.keys().asSequence().toSet() == setOf("schemaVersion", "libraryId", "treeUri",
                "rootEpoch", "revision", "authority") && exactAtomicJournalLong(value.opt("schemaVersion")) == 1L &&
                value.opt("libraryId") == root.libraryId && value.opt("treeUri") == root.treeUri.toString() &&
                (exactAtomicJournalLong(value.opt("rootEpoch")) ?: 0L) > 0L) { "SOURCE_BINDING_AUTHORITY_INVALID" }
            val bytes = (value.get("authority") as String).toByteArray(Charsets.UTF_8)
            val decoded = decodePortableBlockedAuthority(root.libraryId, parseStrictManifestJson(bytes))
            check(exactAtomicJournalLong(value.opt("revision")) == decoded.revision) { "SOURCE_BINDING_AUTHORITY_INVALID" }
            bytes
        }
        val copies = roots.readExactRootSidecars(root, setOf(SelectedLibraryContract.DELETION_TOMBSTONES,
            SelectedLibraryContract.DELETION_TOMBSTONES_TEMP), MAX_PORTABLE_SOURCE_BINDING_BYTES.toLong(),
            snapshot.takeIf { intended == null })
        val selected = selectPortableBlockedAuthority(root.libraryId, copies.values.toList(), intended)
        if (intended != null) {
            invalidateScanAuthority()
            val winner = checkNotNull(selected)
            val bytes = if (winner.revision == checkNotNull(intent).getLong("revision")) intended else
                encodePortableBlockedAuthority(root.libraryId, winner.revision, winner.bindings)
            roots.writeExactRootSidecar(root, SelectedLibraryContract.DELETION_TOMBSTONES,
                SelectedLibraryContract.DELETION_TOMBSTONES_TEMP, bytes, MAX_PORTABLE_SOURCE_BINDING_BYTES.toLong())
            deleteAtomicJournal(file, "SOURCE_BINDING_RECOVERY_PENDING")
        }
        roots.requireSameLibrary(root)
        return selected
    }

    /** Recovery belongs to the durable library/tree; the current handle still fences every write. */
    internal suspend fun recoverRetainedTombstoneWrites(expectedRoot: RootHandle) = mutations.withRootMutation {
        roots.requireSameLibrary(expectedRoot)
        portableBlockedAuthority(expectedRoot)
        Unit
    }

    private suspend fun portableBlockedAuthority(
        expectedRoot: RootHandle,
        snapshot: SelectedLibrarySnapshot? = null
    ): PortableBlockedAuthority? = tombstoneMutex.withLock { readBlockedAuthority(expectedRoot, snapshot) }

    private suspend fun persistPortableBlockedAuthority(
        expectedRoot: RootHandle,
        desiredBindings: List<SourceBindingEntity>? = null
    ) = tombstoneMutex.withLock {
        roots.requireSameLibrary(expectedRoot)
        val before = readBlockedAuthority(expectedRoot)
        val bindings = (desiredBindings ?: dao.blockedSourceBindings(expectedRoot.libraryId))
            .onEach { validateSourceBindingEntity(it, expectedRoot.libraryId) }
            .also { values ->
                check(values.all { it.state in PORTABLE_SOURCE_BINDING_STATES } &&
                    values.map(SourceBindingEntity::sourceId).distinct().size == values.size) {
                    "SOURCE_BINDING_AUTHORITY_INVALID"
                }
            }.sortedBy(SourceBindingEntity::sourceId)
        invalidateScanAuthority()
        val revision = Math.addExact(before?.revision ?: 0L, 1L)
        val bytes = encodePortableBlockedAuthority(expectedRoot.libraryId, revision, bindings)
        retainTombstoneIntent(expectedRoot, bytes)
        roots.writeExactRootSidecar(expectedRoot, SelectedLibraryContract.DELETION_TOMBSTONES,
            SelectedLibraryContract.DELETION_TOMBSTONES_TEMP, bytes, MAX_PORTABLE_SOURCE_BINDING_BYTES.toLong())
        deleteAtomicJournal(tombstoneIntentFile(expectedRoot), "SOURCE_BINDING_RECOVERY_PENDING")
    }

    private suspend fun <T> checkedRoomMutation(block: suspend () -> T): T = try {
        block()
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: IllegalStateException) {
        if (failure.message in SOURCE_BINDING_DOMAIN_ERRORS) throw failure
        throw IllegalStateException("SOURCE_BINDING_COMMIT_FAILED", failure)
    } catch (failure: Throwable) {
        throw IllegalStateException("SOURCE_BINDING_COMMIT_FAILED", failure)
    }
}

private fun sourceBindingEntity(
    libraryId: String,
    sourceId: String,
    packageId: String,
    state: String,
    transactionId: String
) = SourceBindingEntity(
    libraryId = libraryId,
    sourceId = sourceId,
    packageId = packageId,
    state = state,
    transactionId = transactionId,
    updatedAtEpochMs = System.currentTimeMillis().coerceAtLeast(1L)
)

private fun requireSourceAndPackage(sourceId: String, packageId: String) {
    require(MediaIdentity.isYoutubeSourceId(sourceId)) { "SOURCE_ID_INVALID" }
    require(SelectedLibraryContract.isVideoId(packageId)) { "PACKAGE_ID_INVALID" }
    require(sourceId.removePrefix("youtube-video:") == packageId) {
        "SOURCE_BINDING_CONFLICT"
    }
}
