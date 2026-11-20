package app.stemwise.integration

import android.content.Context
import org.json.JSONObject
import android.system.Os
import android.system.OsConstants
import app.stemwise.data.LibraryRootManager
import app.stemwise.network.AppNetworkAccess
import app.stemwise.network.whileUnmetered
import app.stemwise.network.wifiProcessEnvironment
import com.yausername.youtubedl_android.YoutubeDL
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import app.stemwise.network.UnmeteredNetworkRequiredException
import kotlinx.coroutines.CancellationException

private const val MAX_RECEIPT_BYTES = 128 * 1024
private const val MAX_VERSION_BYTES = 64
private const val MAX_REDIRECTS = 3
private const val PUBLIC_KEY_SHA256 =
    "45d6b415928b5f3e228b461fa9e6d7eb56a824931c785ece00279a06c7a6d6e5"

internal fun parsePrivateYtDlpHighWater(bytes: ByteArray): YtDlpDateVersion =
    runCatching { YtDlpDateVersion.parse(bytes.toString(Charsets.UTF_8)) }
        .getOrElse { error("YT_DLP_PRIVATE_STATE_INVALID") }

private fun exactParent(file: File): File = checkNotNull(file.parentFile) {
    "YT_DLP_PRIVATE_PATH_INVALID"
}

internal data class VerifiedYtDlpBinary(
    val version: YtDlpDateVersion,
    val sha256: String,
    val receiptBytes: ByteArray? = null
)

/** Disposable for one admission: replaced files and later admissions always get fresh checks. */
internal class YtDlpBinaryInspection(
    private val hashBinary: (File) -> String = YtDlpReleaseSecurity::sha256,
    private val readVersion: (File) -> YtDlpDateVersion = YtDlpReleaseSecurity::versionFromBinary
) {
    private data class Stamp(val key: Any?, val size: Long, val modified: java.nio.file.attribute.FileTime)
    private inner class Entry(val stamp: Stamp, file: File) {
        val hash by lazy { hashBinary(file) }
        val version by lazy { readVersion(file) }
    }
    private val entries = mutableMapOf<File, Entry>()
    private fun entry(file: File): Entry {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        check(attributes.isRegularFile) { "YT_DLP_BINARY_INVALID" }
        val stamp = Stamp(attributes.fileKey(), attributes.size(), attributes.lastModifiedTime())
        return entries[file]?.takeIf { it.stamp == stamp }
            ?: Entry(stamp, file).also { entries[file] = it }
    }
    fun hash(file: File): String = entry(file).hash
    fun version(file: File): YtDlpDateVersion = entry(file).version
    fun invalidate(file: File) { entries.remove(file) }
}

internal data class YtDlpVerifiedReceipt(
    val version: YtDlpDateVersion,
    val sha256: String,
    val checksums: ByteArray,
    val signature: ByteArray
)

private sealed interface TaggedFetchStep<out T> {
    data class Value<T>(val value: T) : TaggedFetchStep<T>
    data class Redirect(val location: String) : TaggedFetchStep<Nothing>
}

internal class YtDlpExecutionAdmission(
    val binary: VerifiedYtDlpBinary,
    private val rootCheck: () -> Unit
) {
    fun requireCurrentRoot() = rootCheck.invoke()
}

internal data class YtDlpInitializationPreservation(
    val sha256: String
)

internal fun encodeYtDlpReceipt(receipt: YtDlpVerifiedReceipt): ByteArray = buildString {
    appendLine("stemwise.verified-ytdlp.v1")
    appendLine("version=${receipt.version}")
    appendLine("sha256=${receipt.sha256}")
    appendLine("checksums=${Base64.getEncoder().encodeToString(receipt.checksums)}")
    append("signature=${Base64.getEncoder().encodeToString(receipt.signature)}")
}.toByteArray(Charsets.UTF_8).also {
    check(it.size in 1..MAX_RECEIPT_BYTES) { "YT_DLP_RECEIPT_INVALID" }
}

internal fun decodeYtDlpReceipt(bytes: ByteArray): YtDlpVerifiedReceipt {
    check(bytes.size in 1..MAX_RECEIPT_BYTES) { "YT_DLP_RECEIPT_INVALID" }
    val lines = bytes.toString(Charsets.UTF_8).split('\n')
    check(lines.size == 5 && lines[0] == "stemwise.verified-ytdlp.v1") {
        "YT_DLP_RECEIPT_INVALID"
    }
    fun exact(index: Int, prefix: String): String = lines[index].also {
        check(it.startsWith(prefix) && it.length > prefix.length) { "YT_DLP_RECEIPT_INVALID" }
    }.removePrefix(prefix)
    val version = YtDlpDateVersion.parse(exact(1, "version="))
    val sha256 = exact(2, "sha256=")
    check(Regex("^[0-9a-f]{64}$").matches(sha256)) { "YT_DLP_RECEIPT_INVALID" }
    val checksums = runCatching {
        Base64.getDecoder().decode(exact(3, "checksums="))
    }.getOrElse { error("YT_DLP_RECEIPT_INVALID") }
    val signature = runCatching {
        Base64.getDecoder().decode(exact(4, "signature="))
    }.getOrElse { error("YT_DLP_RECEIPT_INVALID") }
    check(checksums.size in 1..MAX_YT_DLP_CHECKSUMS_BYTES &&
        signature.size in 1..MAX_YT_DLP_SIGNATURE_BYTES) { "YT_DLP_RECEIPT_INVALID" }
    return YtDlpVerifiedReceipt(version, sha256, checksums, signature)
}

internal fun validateYtDlpReceipt(receipt: YtDlpVerifiedReceipt, publicKey: ByteArray) {
    YtDlpReleaseSecurity.verifyChecksumsSignature(
        receipt.checksums,
        receipt.signature,
        publicKey
    )
    check(YtDlpReleaseSecurity.exactAssetSha256(receipt.checksums) == receipt.sha256) {
        "YT_DLP_RECEIPT_INVALID"
    }
}

internal class VerifiedYtDlpUpdater(
    context: Context,
    private val client: OkHttpClient = verifiedYtDlpHttpClient(),
    private val networkAccess: AppNetworkAccess,
    private val portableAuthority: SelectedRootYtDlpAuthority?,
    private val inspectionFactory: () -> YtDlpBinaryInspection = ::YtDlpBinaryInspection,
    private val smokeCandidate: suspend (File, String, String?) -> Unit
) {
    constructor(
        context: Context,
        networkAccess: AppNetworkAccess,
        roots: LibraryRootManager?
    ) : this(
        context,
        verifiedYtDlpHttpClient(),
        networkAccess,
        roots?.let(::SelectedRootYtDlpAuthority),
        smokeCandidate = { candidate, version, probeUrl ->
            smokeYtDlpCandidate(context.applicationContext, networkAccess, candidate, version, probeUrl)
        }
    )


    private val appContext = context.applicationContext
    private val publicKey by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        appContext.resources.openRawResource(app.stemwise.R.raw.yt_dlp_release_signing_public_key)
            .use { YtDlpReleaseSecurity.readBounded(it, MAX_YT_DLP_PUBLIC_KEY_BYTES,
                "YT_DLP_PUBLIC_KEY_INVALID") }
            .also {
                check(YtDlpReleaseSecurity.sha256(ByteArrayInputStream(it)) == PUBLIC_KEY_SHA256) {
                    "YT_DLP_PUBLIC_KEY_INVALID"
                }
            }
    }

    @Volatile private var verified: VerifiedYtDlpBinary? = null

    /** Keeps pre-existing owner bytes away from dependency initialization and restores on crash. */
    suspend fun preserveForPackageInitialization(): YtDlpInitializationPreservation? {
        val paths = paths()
        val preserved = paths.initializationPreserved
        if (Files.exists(preserved.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            check(Files.isRegularFile(preserved.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                preserved.length() in 1..MAX_YT_DLP_BINARY_BYTES) {
                "YT_DLP_PRIVATE_STATE_INVALID"
            }
            val preservedBinary = validateBundled(preserved)
                ?: validateReceiptFile(preserved, paths.activeReceipt)
                ?: recognizePortableForInitialization(paths, preserved)
                ?: error("YT_DLP_PRIVATE_STATE_INVALID")
            if (Files.exists(paths.executable.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                deleteExactLeaf(paths.executable)
            }
            atomicReplace(preserved, paths.executable, preservedBinary.sha256)
        }
        val quarantined = paths.quarantinedLegacy
        if (Files.exists(quarantined.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            check(Files.isRegularFile(quarantined.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                quarantined.length() in 1..MAX_YT_DLP_BINARY_BYTES) {
                "YT_DLP_PRIVATE_STATE_INVALID"
            }
            val existingRecognized = if (
                Files.exists(paths.executable.toPath(), LinkOption.NOFOLLOW_LINKS)
            ) {
                validateBundled(paths.executable)
                    ?: validateReceiptFile(paths.executable, paths.activeReceipt)
                    ?: recognizePortableForInitialization(paths, paths.executable)
            } else {
                null
            }
            if (Files.exists(paths.executable.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                existingRecognized == null) {
                // The quarantine proves the prior target was moved away before dependency init;
                // any non-bundle target is an interrupted/partial init output, never owner bytes.
                deleteExactLeaf(paths.executable)
            }
            val portableQuarantine = if (
                existingRecognized == null || existingRecognized.receiptBytes == null
            ) recognizePortableForInitialization(paths, quarantined) else null
            if (portableQuarantine != null) {
                deleteExactLeaf(paths.executable)
                atomicReplace(quarantined, paths.executable, portableQuarantine.sha256)
            }
        }
        if (!Files.exists(paths.executable.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        check(Files.isRegularFile(paths.executable.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            paths.executable.length() in 1..MAX_YT_DLP_BINARY_BYTES) {
            "YT_DLP_PRIVATE_PATH_INVALID"
        }
        val recognized = validateBundled(paths.executable)
            ?: validateReceiptFile(paths.executable, paths.activeReceipt)
            ?: recognizePortableForInitialization(paths, paths.executable)
        if (recognized == null) {
            check(!Files.exists(quarantined.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "YT_DLP_PRIVATE_STATE_INVALID"
            }
            val legacySha = YtDlpReleaseSecurity.sha256(paths.executable)
            atomicReplace(paths.executable, quarantined, legacySha)
            verified = null
            return null
        }
        atomicReplace(paths.executable, preserved, recognized.sha256)
        verified = null
        return YtDlpInitializationPreservation(recognized.sha256)
    }

    private suspend fun recognizePortableForInitialization(
        paths: Paths,
        binary: File
    ): VerifiedYtDlpBinary? {
        val authority = portableAuthority ?: return null
        val portable = authority.captureSession().read(
            publicKey,
            recoverablePortableReceipt(paths)
        ) ?: return null
        if (!exactFileMatches(binary, portable.sha256)) return null
        val embedded = runCatching {
            YtDlpReleaseSecurity.versionFromBinary(binary)
        }.getOrNull()
        if (embedded != portable.version) return null
        writeAtomic(paths.activeReceipt, portable.receiptBytes, MAX_RECEIPT_BYTES)
        return validateReceiptFile(binary, paths.activeReceipt)
    }

    @Synchronized
    fun restoreAfterPackageInitialization(preservation: YtDlpInitializationPreservation?) {
        val paths = paths()
        if (preservation == null) {
            check(!Files.exists(paths.initializationPreserved.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "YT_DLP_PRIVATE_STATE_INVALID"
            }
            return
        }
        check(exactFileMatches(paths.initializationPreserved, preservation.sha256)) {
            "YT_DLP_INITIALIZATION_INTEGRITY_FAILED"
        }
        deleteExactLeaf(paths.executable)
        atomicReplace(paths.initializationPreserved, paths.executable, preservation.sha256)
        check(exactFileMatches(paths.executable, preservation.sha256)) {
            "YT_DLP_INITIALIZATION_INTEGRITY_FAILED"
        }
        verified = null
    }

    @Synchronized
    fun ensureExecutableVerified(inspection: YtDlpBinaryInspection = YtDlpBinaryInspection()): VerifiedYtDlpBinary {
        val paths = paths()
        verified?.takeIf { exactFileMatches(paths.executable, it.sha256, inspection) }?.let { return it }
        validateBundled(paths.executable, inspection)?.let { return remember(it) }
        validateReceiptFile(paths.executable, paths.activeReceipt, inspection)?.let { return remember(it) }
        restoreLastKnownGood(paths, inspection)?.let { return remember(it) }
        return remember(restoreBundled(paths, inspection))
    }

    suspend fun requireExecutableEligible(): VerifiedYtDlpBinary {
        val authority = requireNotNull(portableAuthority) { PORTABLE_YT_DLP_AUTHORITY_REQUIRED }
        val session = authority.captureSession()
        return session.requireExecutableEligibleForSession()
    }

    suspend fun admitExecutable(): YtDlpExecutionAdmission {
        val authority = requireNotNull(portableAuthority) { PORTABLE_YT_DLP_AUTHORITY_REQUIRED }
        val session = authority.captureSession()
        val binary = session.requireExecutableEligibleForSession()
        return YtDlpExecutionAdmission(binary, session::requireCurrentRoot)
    }

    private suspend fun SelectedRootYtDlpAuthority.Session.requireExecutableEligibleForSession():
        VerifiedYtDlpBinary {
        val paths = paths()
        val inspection = inspectionFactory()
        var portable = read(publicKey, recoverablePortableReceipt(paths, inspection))
        val current = reconcilePortableCandidate(portable, inspection)
        val hardFloor = YtDlpDateVersion.parse(YT_DLP_MINIMUM_SAFE_VERSION)
        check(current.version >= hardFloor && current.receiptBytes != null) {
            "YT_DLP_SECURITY_UPDATE_REQUIRED"
        }
        if (portable == null || current.version > portable.version) {
            stagePortableReceipt(paths, checkNotNull(current.receiptBytes), inspection)
            portable = advance(checkNotNull(current.receiptBytes), publicKey)
            deleteExactLeaf(paths.pendingPortableReceipt)
        } else if (current.version == portable.version) {
            check(current.sha256 == portable.sha256) {
                PORTABLE_YT_DLP_AUTHORITY_RECOVERY_REQUIRED
            }
        }
        val floor = admissionFloor(paths, checkNotNull(portable))
        check(current.version >= floor) { "YT_DLP_SECURITY_UPDATE_REQUIRED" }
        // The legacy bytes become useless attack residue only after signed current bytes are
        // bound to this exact portable owner and clear every rollback floor.
        deleteExactLeaf(paths.quarantinedLegacy)
        writeHighWater(paths, floor)
        writeAtomic(paths.activeReceipt, checkNotNull(current.receiptBytes), MAX_RECEIPT_BYTES)
        return current
    }

    suspend fun checkForUpdate(
        probeUrl: String? = null
    ): VerifiedYtDlpBinary {
        val authority = requireNotNull(portableAuthority) { PORTABLE_YT_DLP_AUTHORITY_REQUIRED }
        val session = authority.captureSession()
        val paths = paths()
        val inspection = inspectionFactory()
        var portable = session.read(publicKey, recoverablePortableReceipt(paths, inspection))
        val current = reconcilePortableCandidate(portable, inspection)
        val hardFloor = YtDlpDateVersion.parse(YT_DLP_MINIMUM_SAFE_VERSION)
        if (current.version >= hardFloor && current.receiptBytes != null &&
            (portable == null || current.version > portable.version)) {
            stagePortableReceipt(paths, checkNotNull(current.receiptBytes), inspection)
            portable = session.advance(checkNotNull(current.receiptBytes), publicKey)
            deleteExactLeaf(paths.pendingPortableReceipt)
        }
        val floor = maxOf(
            hardFloor,
            readHighWater(paths) ?: YtDlpDateVersion.parse(YT_DLP_BUNDLED_VERSION),
            portable?.version ?: YtDlpDateVersion.parse(YT_DLP_BUNDLED_VERSION)
        )
        return networkAccess.whileUnmetered {
            installLatest(current, floor, session, probeUrl, inspection)
        }
    }

    private suspend fun installLatest(
        current: VerifiedYtDlpBinary,
        floor: YtDlpDateVersion,
        authority: SelectedRootYtDlpAuthority.Session,
        probeUrl: String?,
        inspection: YtDlpBinaryInspection
    ): VerifiedYtDlpBinary {
        val paths = paths()
        val latest = resolveLatestVersion()
        val required = maxOf(floor, current.version)
        check(latest >= required) { "YT_DLP_ROLLBACK_REJECTED" }
        check(latest >= YtDlpDateVersion.parse(YT_DLP_MINIMUM_SAFE_VERSION)) {
            "YT_DLP_SECURITY_UPDATE_REQUIRED"
        }
        if (latest == current.version && current.version >= floor && current.receiptBytes != null) {
            stagePortableReceipt(paths, checkNotNull(current.receiptBytes), inspection)
            val committed = authority.advance(checkNotNull(current.receiptBytes), publicKey)
            authority.withCommittedAuthority(committed, publicKey) {
                writeHighWater(paths, current.version)
            }
            deleteExactLeaf(paths.pendingPortableReceipt)
            return current
        }

        val checksums = downloadBytes(taggedAssetUrl(latest, YT_DLP_CHECKSUMS_NAME),
            MAX_YT_DLP_CHECKSUMS_BYTES, "YT_DLP_CHECKSUMS_INVALID")
        val signature = downloadBytes(taggedAssetUrl(latest, YT_DLP_SIGNATURE_NAME),
            MAX_YT_DLP_SIGNATURE_BYTES, "YT_DLP_SIGNATURE_INVALID")
        YtDlpReleaseSecurity.verifyChecksumsSignature(checksums, signature, publicKey)
        val expectedSha256 = YtDlpReleaseSecurity.exactAssetSha256(checksums)
        val candidate = paths.executableDirectory.resolve(VERIFIED_DOWNLOAD_NAME)
        deleteExactLeaf(candidate)
        var retainVerifiedCandidate = false
        try {
            downloadFile(taggedAssetUrl(latest, YT_DLP_ASSET_NAME), candidate,
                MAX_YT_DLP_BINARY_BYTES)
            check(inspection.hash(candidate) == expectedSha256) {
                "YT_DLP_BINARY_HASH_INVALID"
            }
            val embeddedVersion = inspection.version(candidate)
            check(embeddedVersion == latest && embeddedVersion.toString() == latest.toString()) {
                "YT_DLP_RELEASE_VERSION_MISMATCH"
            }
            smokeCandidate(candidate, latest.toString(), probeUrl)

            val receiptBytes = encodeYtDlpReceipt(
                YtDlpVerifiedReceipt(latest, expectedSha256, checksums, signature)
            )
            if (current.receiptBytes != null) writeLastKnownGood(paths, current)
            stagePortableReceipt(paths, receiptBytes, inspection)
            // advance() may durably commit portable authority and then fail while rereading it.
            // Keep these already verified bytes so the next cold open can reconcile offline.
            retainVerifiedCandidate = true
            val committedPortable = authority.advance(receiptBytes, publicKey)
            check(committedPortable.version == latest && committedPortable.sha256 == expectedSha256) {
                PORTABLE_YT_DLP_AUTHORITY_RECOVERY_REQUIRED
            }
            return authority.withCommittedAuthority(committedPortable, publicKey) {
                writeHighWater(paths, latest)
                atomicReplace(candidate, paths.executable, expectedSha256, inspection)
                try {
                    writeAtomic(paths.activeReceipt, receiptBytes, MAX_RECEIPT_BYTES)
                    val installed = checkNotNull(validateReceiptFile(
                        paths.executable,
                        paths.activeReceipt, inspection
                    )) { "YT_DLP_INSTALL_VERIFY_FAILED" }
                    deleteExactLeaf(paths.pendingPortableReceipt)
                    remember(installed)
                } catch (error: Throwable) {
                    verified = null
                    // Portable authority was durably committed first. Repair only its matching
                    // candidate; never overwrite those bytes with a below-floor LKG/bundle.
                    runCatching { reconcilePortableCandidate(committedPortable) }
                    throw error
                }
            }
        } finally {
            if (!retainVerifiedCandidate && app.stemwise.integration.WorkerRetirementQuarantine.failureFor(candidate) == null &&
                Files.exists(candidate.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                deleteExactLeaf(candidate)
            }
        }
    }

    private fun validateReceiptFile(binary: File, receiptFile: File,
        inspection: YtDlpBinaryInspection = YtDlpBinaryInspection()): VerifiedYtDlpBinary? {
        val receiptBytes = readPrivateFile(receiptFile, MAX_RECEIPT_BYTES) ?: return null
        return runCatching {
            val receipt = decodeYtDlpReceipt(receiptBytes)
            validateYtDlpReceipt(receipt, publicKey)
            check(exactFileMatches(binary, receipt.sha256, inspection)) { "YT_DLP_BINARY_HASH_INVALID" }
            check(inspection.version(binary) == receipt.version) {
                "YT_DLP_RELEASE_VERSION_MISMATCH"
            }
            VerifiedYtDlpBinary(receipt.version, receipt.sha256, receiptBytes)
        }.getOrNull()
    }

    /** Repairs the private active receipt after a crash that committed portable authority first. */
    private fun reconcilePortableCandidate(
        portable: PortableYtDlpAuthority?,
        inspection: YtDlpBinaryInspection = YtDlpBinaryInspection()
    ): VerifiedYtDlpBinary {
        val paths = paths()
        val candidate = paths.executableDirectory.resolve(VERIFIED_DOWNLOAD_NAME)
        if (portable == null && Files.exists(paths.pendingPortableReceipt.toPath())) {
            deleteExactLeaf(paths.pendingPortableReceipt)
            deleteExactLeaf(candidate)
        }
        fun recognizes(binary: File): Boolean = portable != null &&
            exactFileMatches(binary, portable.sha256, inspection) &&
            runCatching { inspection.version(binary) }.getOrNull() ==
            portable.version

        if (portable != null && recognizes(paths.executable)) {
            writeAtomic(paths.activeReceipt, portable.receiptBytes, MAX_RECEIPT_BYTES)
            val recovered = remember(checkNotNull(validateReceiptFile(
                paths.executable,
                paths.activeReceipt, inspection
            )) { "YT_DLP_INSTALL_VERIFY_FAILED" })
            if (Files.exists(paths.pendingPortableReceipt.toPath())) {
                deleteExactLeaf(paths.pendingPortableReceipt)
                deleteExactLeaf(candidate)
            }
            return recovered
        }
        if (portable != null && recognizes(candidate)) {
            atomicReplace(candidate, paths.executable, portable.sha256, inspection)
            writeAtomic(paths.activeReceipt, portable.receiptBytes, MAX_RECEIPT_BYTES)
            val recovered = remember(checkNotNull(validateReceiptFile(
                paths.executable,
                paths.activeReceipt, inspection
            )) { "YT_DLP_INSTALL_VERIFY_FAILED" })
            deleteExactLeaf(paths.pendingPortableReceipt)
            return recovered
        }
        return ensureExecutableVerified(inspection)
    }

    private fun recoverablePortableReceipt(paths: Paths,
        inspection: YtDlpBinaryInspection = YtDlpBinaryInspection()): ByteArray? {
        val bytes = readPrivateFile(paths.pendingPortableReceipt, MAX_RECEIPT_BYTES) ?: return null
        val receipt = decodeYtDlpReceipt(bytes)
        validateYtDlpReceipt(receipt, publicKey)
        val candidate = paths.executableDirectory.resolve(VERIFIED_DOWNLOAD_NAME)
        check(listOf(paths.executable, candidate).any { binary ->
            exactFileMatches(binary, receipt.sha256, inspection) &&
                runCatching { inspection.version(binary) }.getOrNull() ==
                receipt.version
        }) { "YT_DLP_PRIVATE_STATE_INVALID" }
        return bytes
    }

    private fun stagePortableReceipt(paths: Paths, receiptBytes: ByteArray,
        inspection: YtDlpBinaryInspection = YtDlpBinaryInspection()) {
        val receipt = decodeYtDlpReceipt(receiptBytes)
        validateYtDlpReceipt(receipt, publicKey)
        val candidate = paths.executableDirectory.resolve(VERIFIED_DOWNLOAD_NAME)
        check(listOf(paths.executable, candidate).any { binary ->
            exactFileMatches(binary, receipt.sha256, inspection) &&
                runCatching { inspection.version(binary) }.getOrNull() ==
                receipt.version
        }) { "YT_DLP_PRIVATE_STATE_INVALID" }
        writeAtomic(paths.pendingPortableReceipt, receiptBytes, MAX_RECEIPT_BYTES)
        check(recoverablePortableReceipt(paths, inspection)?.contentEquals(receiptBytes) == true) {
            "YT_DLP_PRIVATE_STATE_INVALID"
        }
    }

    private fun admissionFloor(
        paths: Paths,
        portable: PortableYtDlpAuthority
    ): YtDlpDateVersion = maxOf(
        YtDlpDateVersion.parse(YT_DLP_MINIMUM_SAFE_VERSION),
        readHighWater(paths) ?: YtDlpDateVersion.parse(YT_DLP_BUNDLED_VERSION),
        portable.version
    )

    private fun validateBundled(binary: File, inspection: YtDlpBinaryInspection = YtDlpBinaryInspection()): VerifiedYtDlpBinary? = runCatching {
        check(exactFileMatches(binary, YT_DLP_BUNDLED_SHA256, inspection)) { "YT_DLP_NOT_BUNDLED" }
        val version = inspection.version(binary)
        check(version.toString() == YT_DLP_BUNDLED_VERSION) { "YT_DLP_BUNDLED_VERSION_INVALID" }
        val receiptBytes = appContext.resources.openRawResource(app.stemwise.R.raw.yt_dlp_bundled_receipt)
            .use { YtDlpReleaseSecurity.readBounded(it, MAX_RECEIPT_BYTES, "YT_DLP_RECEIPT_INVALID") }
        val receipt = decodeYtDlpReceipt(receiptBytes)
        validateYtDlpReceipt(receipt, publicKey)
        check(receipt.version == version && receipt.sha256 == YT_DLP_BUNDLED_SHA256) {
            "YT_DLP_BUNDLED_RECEIPT_INVALID"
        }
        VerifiedYtDlpBinary(version, YT_DLP_BUNDLED_SHA256, receiptBytes)
    }.getOrNull()

    private fun restoreLastKnownGood(paths: Paths, inspection: YtDlpBinaryInspection = YtDlpBinaryInspection()): VerifiedYtDlpBinary? {
        val backup = validateReceiptFile(paths.lastKnownGoodBinary, paths.lastKnownGoodReceipt, inspection)
            ?: return null
        copyAtomic(paths.lastKnownGoodBinary, paths.executable, backup.sha256,
            MAX_YT_DLP_BINARY_BYTES)
        inspection.invalidate(paths.executable)
        writeAtomic(paths.activeReceipt, checkNotNull(backup.receiptBytes), MAX_RECEIPT_BYTES)
        return validateReceiptFile(paths.executable, paths.activeReceipt, inspection)
    }

    private fun restoreBundled(paths: Paths, inspection: YtDlpBinaryInspection = YtDlpBinaryInspection()): VerifiedYtDlpBinary {
        val staging = paths.executableDirectory.resolve(".yt-dlp.bundled-recovery")
        deleteExactLeaf(staging)
        try {
            appContext.resources.openRawResource(app.stemwise.R.raw.ytdlp)
                .use { writeStaging(it, staging, MAX_YT_DLP_BINARY_BYTES) }
            check(inspection.hash(staging) == YT_DLP_BUNDLED_SHA256) {
                "YT_DLP_BUNDLED_HASH_INVALID"
            }
            atomicReplace(staging, paths.executable, YT_DLP_BUNDLED_SHA256, inspection)
            deleteExactLeaf(paths.activeReceipt)
            return checkNotNull(validateBundled(paths.executable, inspection)) {
                "YT_DLP_BUNDLED_RECOVERY_FAILED"
            }
        } finally {
            if (Files.exists(staging.toPath(), LinkOption.NOFOLLOW_LINKS)) deleteExactLeaf(staging)
        }
    }

    private fun writeLastKnownGood(paths: Paths, current: VerifiedYtDlpBinary) {
        copyAtomic(paths.executable, paths.lastKnownGoodBinary, current.sha256,
            MAX_YT_DLP_BINARY_BYTES)
        writeAtomic(paths.lastKnownGoodReceipt, checkNotNull(current.receiptBytes), MAX_RECEIPT_BYTES)
        check(validateReceiptFile(paths.lastKnownGoodBinary, paths.lastKnownGoodReceipt) != null) {
            "YT_DLP_LAST_KNOWN_GOOD_INVALID"
        }
    }

    private fun remember(value: VerifiedYtDlpBinary): VerifiedYtDlpBinary {
        verified = value
        return value
    }

    private suspend fun resolveLatestVersion(): YtDlpDateVersion {
        val latest = LATEST_CHECKSUMS_URL.toHttpUrl()
        return networkAccess.newCall(client, request(latest)).executeCancellable { response ->
            check(response.code in REDIRECT_CODES) { "YT_DLP_LATEST_REDIRECT_INVALID" }
            val target = latest.resolve(checkNotNull(response.header("Location")) {
                "YT_DLP_LATEST_REDIRECT_INVALID"
            }) ?: error("YT_DLP_LATEST_REDIRECT_INVALID")
            parseTaggedAssetUrl(target, YT_DLP_CHECKSUMS_NAME)
        }
    }

    private suspend fun downloadBytes(
        url: HttpUrl,
        maximum: Int,
        failure: String
    ): ByteArray = consumeTaggedAsset(url) { response ->
            val body = checkNotNull(response.body) { failure }
            val declared = body.contentLength()
            check(declared < 0L || declared in 1..maximum.toLong()) { failure }
            body.byteStream().use { YtDlpReleaseSecurity.readBounded(it, maximum, failure) }
                .also { check(it.isNotEmpty()) { failure } }
        }

    private suspend fun downloadFile(url: HttpUrl, target: File, maximum: Long) {
        consumeTaggedAsset(url) { response ->
            val body = checkNotNull(response.body) { "YT_DLP_DOWNLOAD_INVALID" }
            val declared = body.contentLength()
            check(declared < 0L || declared in 1..maximum) { "YT_DLP_DOWNLOAD_INVALID" }
            body.byteStream().use { writeStaging(it, target, maximum) }
        }
    }

    private suspend fun <T> consumeTaggedAsset(
        initial: HttpUrl,
        consume: (okhttp3.Response) -> T
    ): T {
        var current = initial
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            requireApprovedAssetUrl(current, redirectCount == 0)
            when (val step = networkAccess.newCall(client, request(current)).executeCancellable { response ->
                if (response.code == 200) {
                    TaggedFetchStep.Value(consume(response))
                } else {
                    check(response.code in REDIRECT_CODES && redirectCount < MAX_REDIRECTS) {
                        "YT_DLP_ASSET_RESPONSE_INVALID"
                    }
                    TaggedFetchStep.Redirect(checkNotNull(response.header("Location")) {
                        "YT_DLP_ASSET_REDIRECT_INVALID"
                    })
                }
            }) {
                is TaggedFetchStep.Value -> return step.value
                is TaggedFetchStep.Redirect -> {
                    current = current.resolve(step.location)
                        ?: error("YT_DLP_ASSET_REDIRECT_INVALID")
                }
            }
        }
        error("YT_DLP_ASSET_REDIRECT_INVALID")
    }

    private fun request(url: HttpUrl): Request = Request.Builder()
        .url(url)
        .header("User-Agent", "StemWise/${app.stemwise.BuildConfig.VERSION_NAME}")
        .header("Accept", "application/octet-stream")
        .header("Cache-Control", "no-store")
        .build()

    private fun taggedAssetUrl(version: YtDlpDateVersion, name: String): HttpUrl =
        "https://github.com/yt-dlp/yt-dlp/releases/download/$version/$name".toHttpUrl()

    private fun parseTaggedAssetUrl(url: HttpUrl, assetName: String): YtDlpDateVersion {
        requireBaseHttps(url)
        check(url.host == "github.com" && url.query == null && url.fragment == null) {
            "YT_DLP_RELEASE_URL_INVALID"
        }
        val prefix = "/yt-dlp/yt-dlp/releases/download/"
        check(url.encodedPath.startsWith(prefix) && url.encodedPath.endsWith("/$assetName")) {
            "YT_DLP_RELEASE_URL_INVALID"
        }
        val tag = url.encodedPath.removePrefix(prefix).removeSuffix("/$assetName")
        check('/' !in tag && tag.isNotBlank()) { "YT_DLP_RELEASE_URL_INVALID" }
        return YtDlpDateVersion.parse(tag)
    }

    private fun requireApprovedAssetUrl(url: HttpUrl, initial: Boolean) {
        requireBaseHttps(url)
        if (initial) {
            parseTaggedAssetUrl(url, url.pathSegments.last())
        } else {
            check(url.host in setOf("github.com", "release-assets.githubusercontent.com",
                "objects.githubusercontent.com")) { "YT_DLP_ASSET_REDIRECT_INVALID" }
        }
    }

    private fun requireBaseHttps(url: HttpUrl) {
        check(url.scheme == "https" && url.port == 443 && url.username.isEmpty() &&
            url.password.isEmpty()) { "YT_DLP_HTTPS_REQUIRED" }
    }

    private fun readHighWater(paths: Paths): YtDlpDateVersion? {
        val bytes = readPrivateFile(paths.highWater, MAX_VERSION_BYTES) ?: return null
        return parsePrivateYtDlpHighWater(bytes)
    }

    private fun writeHighWater(paths: Paths, version: YtDlpDateVersion) {
        val old = readHighWater(paths)
        check(old == null || version >= old) { "YT_DLP_ROLLBACK_REJECTED" }
        if (old == version) return
        writeAtomic(paths.highWater, version.toString().toByteArray(Charsets.UTF_8), MAX_VERSION_BYTES)
    }

    private fun exactFileMatches(file: File, expectedSha256: String,
        inspection: YtDlpBinaryInspection = YtDlpBinaryInspection()): Boolean = runCatching {
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            inspection.hash(file) == expectedSha256
    }.getOrDefault(false)

    private fun readPrivateFile(file: File, maximum: Int): ByteArray? {
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "YT_DLP_PRIVATE_PATH_INVALID"
        }
        check(file.length() in 1..maximum.toLong()) { "YT_DLP_PRIVATE_STATE_INVALID" }
        return file.inputStream().use {
            YtDlpReleaseSecurity.readBounded(it, maximum, "YT_DLP_PRIVATE_STATE_INVALID")
        }
    }

    private fun copyAtomic(source: File, target: File, expectedSha256: String, maximum: Long) {
        val targetParent = exactParent(target)
        val staging = targetParent.resolve(".${target.name}.copy")
        deleteExactLeaf(staging)
        try {
            source.inputStream().use { writeStaging(it, staging, maximum) }
            atomicReplace(staging, target, expectedSha256)
        } finally {
            if (Files.exists(staging.toPath(), LinkOption.NOFOLLOW_LINKS)) deleteExactLeaf(staging)
        }
    }

    private fun writeAtomic(target: File, bytes: ByteArray, maximum: Int) {
        check(bytes.size in 1..maximum) { "YT_DLP_PRIVATE_STATE_INVALID" }
        val targetParent = exactParent(target)
        if (readPrivateFile(target, maximum)?.contentEquals(bytes) == true) return
        val staging = targetParent.resolve(".${target.name}.new")
        deleteExactLeaf(staging)
        try {
            ByteArrayInputStream(bytes).use { writeStaging(it, staging, maximum.toLong()) }
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
            fsyncDirectory(targetParent)
            check(readPrivateFile(target, maximum)?.contentEquals(bytes) == true) {
                "YT_DLP_PRIVATE_STATE_INVALID"
            }
        } finally {
            if (Files.exists(staging.toPath(), LinkOption.NOFOLLOW_LINKS)) deleteExactLeaf(staging)
        }
    }

    private fun writeStaging(input: java.io.InputStream, target: File, maximum: Long) {
        check(maximum > 0L && maximum <= Int.MAX_VALUE) { "YT_DLP_DOWNLOAD_INVALID" }
        check(exactParent(target).isDirectory) { "YT_DLP_PRIVATE_PATH_INVALID" }
        FileOutputStream(target, false).use { output ->
            val buffer = ByteArray(16 * 1024)
            var copied = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                copied = Math.addExact(copied, read.toLong())
                check(copied <= maximum) { "YT_DLP_DOWNLOAD_TOO_LARGE" }
                output.write(buffer, 0, read)
            }
            check(copied > 0L) { "YT_DLP_DOWNLOAD_INVALID" }
            output.flush()
            output.fd.sync()
        }
    }

    private fun atomicReplace(source: File, target: File, expectedSha256: String,
        inspection: YtDlpBinaryInspection = YtDlpBinaryInspection()) {
        val targetParent = exactParent(target)
        check(exactParent(source) == targetParent && exactFileMatches(source, expectedSha256, inspection)) {
            "YT_DLP_INSTALL_VERIFY_FAILED"
        }
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING)
        fsyncDirectory(targetParent)
        inspection.invalidate(source)
        inspection.invalidate(target)
        check(exactFileMatches(target, expectedSha256, inspection)) { "YT_DLP_INSTALL_VERIFY_FAILED" }
    }

    private fun deleteExactLeaf(file: File) {
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        check(!Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "YT_DLP_PRIVATE_PATH_INVALID"
        }
        Files.delete(file.toPath())
    }

    private fun fsyncDirectory(directory: File) {
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    private fun paths(): Paths {
        val root = appContext.noBackupFilesDir.canonicalFile
        check(root.isDirectory && !Files.isSymbolicLink(root.toPath())) {
            "YT_DLP_PRIVATE_PATH_INVALID"
        }
        val upstream = exactDirectory(root, YoutubeDL.baseName)
        val executableDirectory = exactDirectory(upstream, YoutubeDL.ytdlpDirName)
        val state = exactDirectory(root, "stemwise-verified-ytdlp")
        val lkg = exactDirectory(state, "last-known-good")
        return Paths(
            executableDirectory,
            exactLeaf(executableDirectory, YoutubeDL.ytdlpBin),
            exactLeaf(executableDirectory, ".yt-dlp.initialization-preserved"),
            exactLeaf(executableDirectory, ".yt-dlp.untrusted-legacy"),
            exactLeaf(state, "active.receipt"),
            exactLeaf(state, "pending-portable.receipt"),
            exactLeaf(state, "high-water.version"),
            exactLeaf(lkg, YoutubeDL.ytdlpBin),
            exactLeaf(lkg, "receipt")
        )
    }

    private fun exactDirectory(parent: File, name: String): File {
        check(Regex("^[A-Za-z0-9._-]+$").matches(name)) { "YT_DLP_PRIVATE_PATH_INVALID" }
        val literal = File(parent, name)
        check(!Files.isSymbolicLink(literal.toPath())) { "YT_DLP_PRIVATE_PATH_INVALID" }
        if (!literal.exists()) check(literal.mkdir()) { "YT_DLP_PRIVATE_PATH_INVALID" }
        val canonical = literal.canonicalFile
        check(canonical.isDirectory && canonical.parentFile == parent) {
            "YT_DLP_PRIVATE_PATH_INVALID"
        }
        return canonical
    }

    private fun exactLeaf(parent: File, name: String): File {
        check(Regex("^[A-Za-z0-9._-]+$").matches(name)) { "YT_DLP_PRIVATE_PATH_INVALID" }
        return File(parent, name).absoluteFile.also {
            check(it.parentFile == parent) { "YT_DLP_PRIVATE_PATH_INVALID" }
        }
    }

    private data class Paths(
        val executableDirectory: File,
        val executable: File,
        val initializationPreserved: File,
        val quarantinedLegacy: File,
        val activeReceipt: File,
        val pendingPortableReceipt: File,
        val highWater: File,
        val lastKnownGoodBinary: File,
        val lastKnownGoodReceipt: File
    )

    private companion object {
        const val VERIFIED_DOWNLOAD_NAME = ".yt-dlp.verified-download"
        const val LATEST_CHECKSUMS_URL =
            "https://github.com/yt-dlp/yt-dlp/releases/latest/download/SHA2-256SUMS"
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

private fun verifiedYtDlpHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .callTimeout(45, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .build()

internal suspend fun smokeYtDlpCandidate(
    context: Context,
    networkAccess: AppNetworkAccess,
    candidate: File,
    expectedVersion: String,
    probeUrl: String? = null
) {
    val nativeDirectory = File(context.applicationInfo.nativeLibraryDir)
    val python = nativeDirectory.resolve("libpython.so")
    val quickJs = nativeDirectory.resolve("libqjs.so")
    val ffmpeg = nativeDirectory.resolve("libffmpeg.so")
    val packageRoot = context.noBackupFilesDir.resolve("youtubedl-android/packages")
    val pythonHome = packageRoot.resolve("python/usr")
    val certificate = pythonHome.resolve("etc/tls/cert.pem")
    check(python.isFile && quickJs.isFile && ffmpeg.isFile && pythonHome.isDirectory && certificate.isFile) {
        "YT_DLP_SMOKE_ENVIRONMENT_INVALID"
    }
    val wrapperOptions = listOf(
        "--ignore-config",
        "--no-plugin-dirs",
        "--js-runtimes",
        "quickjs:${quickJs.absolutePath}",
        "--ffmpeg-location",
        ffmpeg.absolutePath
    )
    val environment = bundledToolProcessEnvironment(context)
    val versionOutput = runYtDlpSmokeProcess(
        python,
        candidate,
        wrapperOptions + "--version",
        environment,
        4 * 1024
    ).trim()
    check(versionOutput == expectedVersion) { "YT_DLP_SMOKE_FAILED" }
    val extractorOutput = runYtDlpSmokeProcess(
        python,
        candidate,
        wrapperOptions + "--list-extractors",
        environment,
        1024 * 1024
    )
    check(extractorOutput.lineSequence().any { it == "youtube" || it.startsWith("youtube:") }) {
        "YT_DLP_SMOKE_FAILED"
    }
    if (probeUrl == null) return
    val probeDirectory = Files.createTempDirectory(context.cacheDir.toPath(), "yt-dlp-probe-").toFile()
    try {
        verifyYtDlpMetadataProbe(probeDirectory, probeUrl) { arguments ->
            runYtDlpSmokeProcess(
                python, candidate, wrapperOptions + arguments,
                environment + networkAccess.wifiProcessEnvironment(context),
                64 * 1024, timeoutSeconds = 60L, workingDirectory = probeDirectory
            )
        }
    } finally {
        if (app.stemwise.integration.WorkerRetirementQuarantine.failureFor(probeDirectory) == null)
            check(probeDirectory.deleteRecursively()) { "YT_DLP_PROBE_CLEANUP_FAILED" }
    }
}

/** Candidate promotion verifies the only retained extractor capability: metadata. */
internal suspend fun verifyYtDlpMetadataProbe(
    directory: File,
    probeUrl: String,
    run: suspend (List<String>) -> String
) {
    val expectedId = checkNotNull(YT_DLP_WATCH_URL.matchEntire(probeUrl)) {
        "YT_DLP_PROBE_URL_INVALID"
    }.groupValues[1]
    val output = run(listOf(
        "--skip-download", "--print", "%(.{id,title})j", "--quiet", "--no-warnings", "--no-playlist", "--no-cache-dir",
        "--retries", "0", "--fragment-retries", "0", "--socket-timeout", "10",
        probeUrl
    ))
    val metadata = JSONObject(output)
    check(metadata.optString("id") == expectedId && metadata.optString("title").isNotBlank() &&
        directory.listFiles()?.isEmpty() == true) {
        "YT_DLP_METADATA_PROBE_FAILED"
    }
}

private val YT_DLP_WATCH_URL = Regex("^https://(?:www\\.|music\\.)?youtube\\.com/watch\\?v=([A-Za-z0-9_-]{11})(?:&[^\\s]*)?$")

private suspend fun runYtDlpSmokeProcess(
    python: File,
    candidate: File,
    arguments: List<String>,
    environment: Map<String, String>,
    maximumOutputBytes: Int,
    timeoutSeconds: Long = 10L,
    workingDirectory: File? = null
): String {
    val result = try {
        runOwnedToolProcess(listOf(python.absolutePath, candidate.absolutePath) + arguments,
            environment, timeoutSeconds, maximumOutputBytes.toLong(), cwd = workingDirectory)
    } catch (error: app.stemwise.playback.engine.WorkerRetirementException) {
        app.stemwise.integration.WorkerRetirementQuarantine.retain(error,
            files = listOfNotNull(python, candidate, workingDirectory))
        throw error
    }
    if (result.exitCode == 125 && "WIFI_BINDING_UNAVAILABLE" in result.stderr) {
        throw UnmeteredNetworkRequiredException(operationStarted = true)
    }
    if (result.exitCode != 0 || result.stdout.isEmpty()) {
        // Retain one bounded private cause; callers continue to expose only the stable code.
        throw IllegalStateException("YT_DLP_SMOKE_FAILED", IllegalStateException(
            "Tool exit=${result.exitCode}; stderr=${result.stderr.takeLast(4096)}"
        ))
    }
    return result.stdout
}
