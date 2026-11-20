package app.stemwise.data

import android.content.Context
import android.util.AtomicFile
import app.stemwise.KeyedOperationMutex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

/**
 * Owns each canonical package and fences root replacement against long media writers.
 * The atomic private journal survives process death; external operation markers
 * are written by the operation itself before canonical bytes are changed.
 */
class PackageMutationCoordinator(context: Context) {
    @Volatile internal var journalWriteForTest: ((String, String) -> Unit)? = null
    @Volatile internal var rootReplacementWaitingForTest: (() -> Unit)? = null
    private val applicationContext = context.applicationContext
    private val rootLock = Mutex()
    private val replacementGate = Mutex()
    private val packages = KeyedOperationMutex()
    private val writers = mutableSetOf<CompletableDeferred<Unit>>()

    internal suspend fun <T> withRootReplacement(block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            replacementGate.withLock {
                val active = rootLock.withLock { writers.toList() }
                rootReplacementWaitingForTest?.invoke()
                active.forEach { it.await() }
                rootLock.withLock { block() }
            }
        }
    private val journalDirectory: File by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        File(applicationContext.filesDir, "operation-journals")
    }

    suspend fun <T> withRootMutation(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { rootLock.withLock { block() } }

    /** Long I/O pins the admitted root while unrelated package admission keeps using its mutex. */
    internal suspend fun <T> withRootLease(requireExactRoot: () -> Unit, block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            val retirement = CompletableDeferred<Unit>()
            replacementGate.withLock {
                rootLock.withLock {
                    requireExactRoot()
                    writers += retirement
                }
            }
            try { block() } finally {
                withContext(NonCancellable) {
                    rootLock.withLock { writers -= retirement; retirement.complete(Unit) }
                }
            }
        }

    /**
     * Admits one exact-root package mutation; long media I/O retains a root lease instead of its mutex.
     * [requireExactRoot] must be side-effect free: it runs before token validation,
     * journal-directory access, journal re-entry, or PREPARED publication.
     */
    internal suspend fun <T> withPackageMutation(
        rootHandle: RootHandle,
        packageId: String,
        operation: String,
        transactionId: String,
        requireExactRoot: () -> Unit,
        beforeJournal: suspend () -> Unit = {},
        holdRootLock: Boolean = true,
        block: suspend (Journal) -> T
    ): T = withContext(Dispatchers.IO) {
        suspend fun mutate(): T {
            requireExactRoot()
            require(rootHandle.epoch > 0L) { "ROOT_HANDLE_INVALID" }
            require(SelectedLibraryContract.isVideoId(packageId)) { "PACKAGE_ID_INVALID" }
            require(SAFE_TOKEN.matches(transactionId)) { "TRANSACTION_ID_INVALID" }
            require(SAFE_TOKEN.matches(operation)) { "OPERATION_INVALID" }
            // Recovery admission runs under exact-root/package ownership before a
            // fresh PREPARED record exists. A blocking prior mutation can therefore
            // remain the one durable authority across repeated worker/manual wakes.
            beforeJournal()
            val journal = Journal(
                rootHandle,
                packageId,
                operation,
                transactionId,
                requireJournalDirectory()
            )
            journal.prepare()
            return withCheckedPrivateCleanup(
                cleanup = {
                    if (journal.completed && !journal.retainedForRecovery) {
                        deleteAtomicJournal(journal.file, "JOURNAL_CLEANUP_PENDING")
                    }
                }
            ) {
                block(journal)
            }
        }
        val key = "${rootHandle.treeUri}/$packageId"
        if (holdRootLock) packages.withKey(key) { rootLock.withLock { mutate() } }
        else withRootLease(requireExactRoot) {
            packages.withKey(key) {
                // Recovery holds root ownership and observes this package lock before touching journals.
                rootLock.withLock { requireExactRoot() }
                mutate()
            }
        }
    }

    internal suspend fun <T> withPackageRead(root: RootHandle, packageId: String, block: suspend () -> T): T =
        withPackageRead(root.authority(), packageId, block)

    internal suspend fun <T> withPackageRead(root: RootAuthority, packageId: String, block: suspend () -> T): T =
        packages.withKey("${root.treeUri}/$packageId") {
            // Recovery must observe package ownership before any package I/O begins.
            rootLock.withLock { }
            block()
        }

    internal fun packageIsOwnedUnderRootLock(root: RootHandle, packageId: String): Boolean =
        packages.isLocked("${root.treeUri}/$packageId")

    private fun requireJournalDirectory(): File {
        if (!journalDirectory.mkdirs() && !journalDirectory.isDirectory) {
            error("JOURNAL_STORAGE_UNAVAILABLE")
        }
        return journalDirectory.canonicalFile
    }

    inner class Journal internal constructor(
        private val rootHandle: RootHandle,
        private val packageId: String,
        private val operation: String,
        private val transactionId: String,
        journalDirectory: File
    ) {
        internal val file = File(journalDirectory, "$transactionId.json").canonicalFile.also {
            check(it.parentFile == journalDirectory) { "TRANSACTION_ID_INVALID" }
        }
        init {
            requireAtomicJournalAdmission(
                journalDirectory,
                file,
                "JOURNAL_RECOVERY_BOUNDED"
            )
        }
        internal var completed = false
            private set
        internal var retainedForRecovery = false
            private set

        private val recovered = readAtomicJournal(file, "JOURNAL_CORRUPT")
        private val recoveredEnvelope = recovered?.let { record ->
            val envelope = requireAtomicJournalEnvelope(record, "JOURNAL_CORRUPT")
            check(
                envelope.transactionId == transactionId &&
                    envelope.packageId == packageId &&
                    envelope.operation == operation &&
                    envelope.libraryId == rootHandle.libraryId &&
                    envelope.treeUri == rootHandle.treeUri.toString() &&
                    envelope.rootEpoch == rootHandle.epoch
            ) { "JOURNAL_ROOT_MISMATCH" }
            envelope
        }
        private val existing = recovered?.value
        private var sequence = recoveredEnvelope?.sequence ?: 0L
        private val durableDetails = existing?.let { value ->
            JSONObject(value.getJSONObject("details").toString())
        } ?: JSONObject()

        internal fun prepare() {
            if (existing == null) write("PREPARED")
        }

        internal fun recoveredString(state: String, key: String): String? =
            recoveredEnvelope?.takeIf { it.state == state }
                ?.let { durableDetails.opt(key) as? String }

        internal fun recoveredLong(state: String, key: String): Long? =
            recoveredEnvelope?.takeIf { it.state == state }
                ?.let { exactAtomicJournalLong(durableDetails.opt(key)) }

        internal fun wasRecoveredInState(state: String): Boolean =
            recoveredEnvelope?.state == state

        fun write(state: String, details: JSONObject = JSONObject()) {
            journalWriteForTest?.invoke(operation, state)
            sequence += 1L
            details.keys().forEach { key -> durableDetails.put(key, details.get(key)) }
            val value = JSONObject()
                .put("schemaVersion", JOURNAL_SCHEMA_VERSION)
                .put("transactionId", transactionId)
                .put("packageId", packageId)
                .put("operation", operation)
                .put("libraryId", rootHandle.libraryId)
                .put("treeUri", rootHandle.treeUri.toString())
                .put("rootEpoch", rootHandle.epoch)
                .put("sequence", sequence)
                .put("state", state)
                .put("updatedAt", java.time.Instant.now().toString())
                // Re-entry advances the durable phase without forgetting identity/hash/path facts
                // already written by an earlier phase of this exact transaction.
                .put("details", JSONObject(durableDetails.toString()))
            val bytes = value.toString().toByteArray(Charsets.UTF_8)
            check(bytes.size.toLong() <= MAX_ATOMIC_JOURNAL_BYTES) { "JOURNAL_TOO_LARGE" }
            synchronized(atomicJournalIoLock(file)) {
                val atomic = AtomicFile(file)
                val stream = atomic.startWrite()
                try {
                    stream.write(bytes)
                    stream.flush()
                    atomic.finishWrite(stream)
                } catch (error: Throwable) {
                    atomic.failWrite(stream)
                    throw error
                }
            }
        }

        fun releaseRecovery() {
            check(completed)
            retainedForRecovery = false
        }

        fun complete(
            details: JSONObject = JSONObject(),
            retainForRecovery: Boolean = false
        ) {
            write("COMPLETE", details)
            retainedForRecovery = retainForRecovery
            completed = true
        }
    }

    companion object {
        private const val JOURNAL_SCHEMA_VERSION = 2
        private val SAFE_TOKEN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    }
}
