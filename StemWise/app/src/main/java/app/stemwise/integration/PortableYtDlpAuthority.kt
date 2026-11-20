package app.stemwise.integration

import app.stemwise.data.CanonicalJson
import app.stemwise.data.LibraryRootManager
import app.stemwise.data.RootHandle
import app.stemwise.data.SelectedLibraryContract
import app.stemwise.data.authority
import app.stemwise.data.interruptedReservedAuthorityWriteIsRecoverable
import app.stemwise.enrichment.parseStrictManifestJson
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.UUID
import org.json.JSONObject

internal const val PORTABLE_YT_DLP_AUTHORITY_MAX_BYTES = 256L * 1024L
internal const val PORTABLE_YT_DLP_AUTHORITY_REQUIRED = "YT_DLP_PORTABLE_AUTHORITY_REQUIRED"
internal const val PORTABLE_YT_DLP_AUTHORITY_INVALID = "YT_DLP_PORTABLE_AUTHORITY_INVALID"
internal const val PORTABLE_YT_DLP_AUTHORITY_RECOVERY_REQUIRED =
    "YT_DLP_PORTABLE_AUTHORITY_RECOVERY_REQUIRED"

internal data class PortableYtDlpAuthority(
    val libraryId: String,
    val version: YtDlpDateVersion,
    val sha256: String,
    val receiptBytes: ByteArray
) {
    init {
        require(runCatching { UUID.fromString(libraryId).toString() == libraryId }.getOrDefault(false)) {
            PORTABLE_YT_DLP_AUTHORITY_INVALID
        }
        require(YT_DLP_SHA256.matches(sha256) && receiptBytes.isNotEmpty()) {
            PORTABLE_YT_DLP_AUTHORITY_INVALID
        }
    }

    fun toBytes(): ByteArray {
        val payload = payloadJson()
        payload.put(AUTHORITY_INTEGRITY_SHA256, CanonicalJson.sha256(payload))
        return CanonicalJson.encode(payload).also {
            check(it.size.toLong() <= PORTABLE_YT_DLP_AUTHORITY_MAX_BYTES) {
                PORTABLE_YT_DLP_AUTHORITY_INVALID
            }
        }
    }

    fun sameRelease(other: PortableYtDlpAuthority): Boolean =
        version == other.version && sha256 == other.sha256 &&
            receiptBytes.contentEquals(other.receiptBytes)

    private fun payloadJson(): JSONObject = JSONObject()
        .put("schemaVersion", PORTABLE_YT_DLP_SCHEMA_VERSION)
        .put("kind", PORTABLE_YT_DLP_KIND)
        .put("libraryId", libraryId)
        .put("version", version.toString())
        .put("sha256", sha256)
        .put("receiptSha256", receiptSha256(receiptBytes))
        .put("receipt", Base64.getEncoder().encodeToString(receiptBytes))
}

internal fun parsePortableYtDlpAuthority(
    bytes: ByteArray,
    expectedLibraryId: String,
    publicKey: ByteArray
): PortableYtDlpAuthority {
    check(bytes.isNotEmpty() && bytes.size.toLong() <= PORTABLE_YT_DLP_AUTHORITY_MAX_BYTES) {
        PORTABLE_YT_DLP_AUTHORITY_INVALID
    }
    val json = runCatching { parseStrictManifestJson(bytes) }
        .getOrElse { error(PORTABLE_YT_DLP_AUTHORITY_INVALID) }
    check(json.keys().asSequence().toSet() == PORTABLE_YT_DLP_KEYS) {
        PORTABLE_YT_DLP_AUTHORITY_INVALID
    }
    val integrity = json.requiredString(AUTHORITY_INTEGRITY_SHA256)
    check(YT_DLP_SHA256.matches(integrity)) { PORTABLE_YT_DLP_AUTHORITY_INVALID }
    json.remove(AUTHORITY_INTEGRITY_SHA256)
    check(CanonicalJson.sha256(json) == integrity) { PORTABLE_YT_DLP_AUTHORITY_INVALID }
    check(
        json.requiredExactLong("schemaVersion") == PORTABLE_YT_DLP_SCHEMA_VERSION &&
            json.requiredString("kind") == PORTABLE_YT_DLP_KIND &&
            json.requiredString("libraryId") == expectedLibraryId
    ) { PORTABLE_YT_DLP_AUTHORITY_INVALID }
    val receiptBytes = runCatching {
        Base64.getDecoder().decode(json.requiredString("receipt"))
    }.getOrElse { error(PORTABLE_YT_DLP_AUTHORITY_INVALID) }
    check(receiptSha256(receiptBytes) == json.requiredString("receiptSha256")) {
        PORTABLE_YT_DLP_AUTHORITY_INVALID
    }
    val receipt = decodeYtDlpReceipt(receiptBytes)
    validateYtDlpReceipt(receipt, publicKey)
    val version = YtDlpDateVersion.parse(json.requiredString("version"))
    val sha256 = json.requiredString("sha256")
    check(receipt.version == version && receipt.sha256 == sha256) {
        PORTABLE_YT_DLP_AUTHORITY_INVALID
    }
    return PortableYtDlpAuthority(expectedLibraryId, version, sha256, receiptBytes)
}

internal fun selectPortableYtDlpAuthority(
    copies: Map<String, ByteArray>,
    expectedLibraryId: String,
    publicKey: ByteArray,
    authenticatedAuthority: PortableYtDlpAuthority? = null
): PortableYtDlpAuthority? {
    if (copies.isEmpty() && authenticatedAuthority == null) return null
    check(copies.keys.all { it in PORTABLE_YT_DLP_NAMES }) {
        PORTABLE_YT_DLP_AUTHORITY_RECOVERY_REQUIRED
    }
    val valid = mutableMapOf<String, Pair<ByteArray, PortableYtDlpAuthority>>()
    val invalid = mutableSetOf<String>()
    copies.forEach { (name, bytes) ->
        runCatching { parsePortableYtDlpAuthority(bytes, expectedLibraryId, publicKey) }
            .onSuccess { valid[name] = bytes to it }
            .onFailure { invalid += name }
    }
    val authenticated = authenticatedAuthority?.also { candidate ->
        val parsed = parsePortableYtDlpAuthority(candidate.toBytes(), expectedLibraryId, publicKey)
        check(parsed.sameRelease(candidate)) { PORTABLE_YT_DLP_AUTHORITY_RECOVERY_REQUIRED }
    }
    val recoveryGenerations = buildList {
        addAll(valid.values.map { it.first })
        authenticated?.let { add(it.toBytes()) }
    }
    val records = buildList {
        addAll(valid.values.map { it.second })
        authenticated?.let { expected ->
            if (none(expected::sameRelease)) add(expected)
        }
    }
    records.groupBy(PortableYtDlpAuthority::version).values.forEach { sameVersion ->
        val first = sameVersion.first()
        check(sameVersion.all(first::sameRelease)) {
            PORTABLE_YT_DLP_AUTHORITY_RECOVERY_REQUIRED
        }
    }
    if (invalid.isNotEmpty()) {
        invalid.forEach { name ->
            val bytes = checkNotNull(copies[name])
            check(
                interruptedReservedAuthorityWriteIsRecoverable(
                    bytes,
                    recoveryGenerations,
                    authorityAuthenticated = recoveryGenerations.isNotEmpty()
                ) || canRecoverInvalidPortableYtDlpCopy(bytes, records)
            ) { PORTABLE_YT_DLP_AUTHORITY_RECOVERY_REQUIRED }
        }
    }
    check(records.isNotEmpty()) { PORTABLE_YT_DLP_AUTHORITY_RECOVERY_REQUIRED }
    return records.maxBy(PortableYtDlpAuthority::version)
}

private fun canRecoverInvalidPortableYtDlpCopy(
    bytes: ByteArray,
    valid: List<PortableYtDlpAuthority>
): Boolean = runCatching {
    val damaged = parseStrictManifestJson(bytes)
    check(damaged.keys().asSequence().toSet() == PORTABLE_YT_DLP_KEYS)
    check(damaged.requiredExactLong("schemaVersion") == PORTABLE_YT_DLP_SCHEMA_VERSION)
    check(damaged.opt("kind") == PORTABLE_YT_DLP_KIND)
    damaged.remove(AUTHORITY_INTEGRITY_SHA256)
    valid.any { candidate ->
        val expected = parseStrictManifestJson(candidate.toBytes())
        expected.remove(AUTHORITY_INTEGRITY_SHA256)
        CanonicalJson.encode(damaged).contentEquals(CanonicalJson.encode(expected))
    }
}.getOrDefault(false)

/** Root-locked portable rollback authority for the exact selected library owner. */
internal class SelectedRootYtDlpAuthority(
    private val roots: LibraryRootManager
) {
    fun captureSession(): Session {
        val handle = roots.captureHandle() ?: error(PORTABLE_YT_DLP_AUTHORITY_REQUIRED)
        return Session(handle)
    }

    internal inner class Session internal constructor(
        private val handle: RootHandle
    ) {
        suspend fun read(
            publicKey: ByteArray,
            recoveryReceiptBytes: ByteArray? = null
        ): PortableYtDlpAuthority? =
            roots.withExactRootMutation(handle.authority()) {
                readAndRepairLocked(
                    handle,
                    publicKey,
                    recoveryReceiptBytes?.let { authority(handle, it, publicKey) }
                )
            }

        suspend fun advance(
            receiptBytes: ByteArray,
            publicKey: ByteArray
        ): PortableYtDlpAuthority = roots.withExactRootMutation(handle.authority()) {
            val requested = authority(handle, receiptBytes, publicKey)
            val current = readAndRepairLocked(handle, publicKey, requested)
            check(current == null || requested.version >= current.version) {
                "YT_DLP_ROLLBACK_REJECTED"
            }
            if (current != null && requested.version == current.version) {
                check(current.sameRelease(requested)) {
                    PORTABLE_YT_DLP_AUTHORITY_RECOVERY_REQUIRED
                }
                return@withExactRootMutation current
            }
            writeCopiesLocked(handle, requested.toBytes())
            checkNotNull(readAndRepairLocked(handle, publicKey)).also {
                check(it.sameRelease(requested)) { PORTABLE_YT_DLP_AUTHORITY_RECOVERY_REQUIRED }
            }
        }

        fun requireCurrentRoot() = roots.requireSameLibrary(handle)

        suspend fun <T> withCommittedAuthority(
            expected: PortableYtDlpAuthority,
            publicKey: ByteArray,
            block: () -> T
        ): T = roots.withExactRootMutation(handle.authority()) {
            val current = checkNotNull(readAndRepairLocked(handle, publicKey)) {
                PORTABLE_YT_DLP_AUTHORITY_RECOVERY_REQUIRED
            }
            check(current.sameRelease(expected)) {
                PORTABLE_YT_DLP_AUTHORITY_RECOVERY_REQUIRED
            }
            block()
        }
    }

    private suspend fun readAndRepairLocked(
        handle: RootHandle,
        publicKey: ByteArray,
        authenticatedAuthority: PortableYtDlpAuthority? = null
    ): PortableYtDlpAuthority? {
        val copies = roots.readExactRootSidecars(
            handle,
            PORTABLE_YT_DLP_NAMES,
            PORTABLE_YT_DLP_AUTHORITY_MAX_BYTES
        )
        val selected = selectPortableYtDlpAuthority(
            copies,
            handle.libraryId,
            publicKey,
            authenticatedAuthority
        )
            ?: return null
        val canonical = selected.toBytes()
        val needsPromotion =
            copies[SelectedLibraryContract.YT_DLP_AUTHORITY]?.contentEquals(canonical) != true ||
                copies[SelectedLibraryContract.YT_DLP_AUTHORITY_BACKUP]?.contentEquals(canonical) != true ||
                SelectedLibraryContract.YT_DLP_AUTHORITY_TEMP in copies
        if (needsPromotion) {
            writeCopiesLocked(handle, canonical)
        }
        return selected
    }

    private fun authority(
        handle: RootHandle,
        receiptBytes: ByteArray,
        publicKey: ByteArray
    ): PortableYtDlpAuthority {
        val receipt = decodeYtDlpReceipt(receiptBytes)
        validateYtDlpReceipt(receipt, publicKey)
        return PortableYtDlpAuthority(
            handle.libraryId,
            receipt.version,
            receipt.sha256,
            receiptBytes
        )
    }

    private suspend fun writeCopiesLocked(handle: RootHandle, bytes: ByteArray) {
        roots.writeExactRootAuthorityCopies(
            handle,
            SelectedLibraryContract.YT_DLP_AUTHORITY,
            SelectedLibraryContract.YT_DLP_AUTHORITY_BACKUP,
            SelectedLibraryContract.YT_DLP_AUTHORITY_TEMP,
            bytes,
            PORTABLE_YT_DLP_AUTHORITY_MAX_BYTES
        )
    }
}

private fun receiptSha256(bytes: ByteArray): String =
    YtDlpReleaseSecurity.sha256(ByteArrayInputStream(bytes))

private fun JSONObject.requiredString(key: String): String =
    (opt(key) as? String) ?: error(PORTABLE_YT_DLP_AUTHORITY_INVALID)

private fun JSONObject.requiredExactLong(key: String): Long = when (val value = opt(key)) {
    is Int -> value.toLong()
    is Long -> value
    else -> error(PORTABLE_YT_DLP_AUTHORITY_INVALID)
}

// Permanent 3.7.7 contract: v1 filenames only ever contain schema 1. Future schemas use new names.
private const val PORTABLE_YT_DLP_SCHEMA_VERSION = 1L
private const val PORTABLE_YT_DLP_KIND = "stemwise.yt-dlp-authority"
private const val AUTHORITY_INTEGRITY_SHA256 = "integritySha256"
private val YT_DLP_SHA256 = Regex("^[0-9a-f]{64}$")
private val PORTABLE_YT_DLP_NAMES = setOf(
    SelectedLibraryContract.YT_DLP_AUTHORITY,
    SelectedLibraryContract.YT_DLP_AUTHORITY_BACKUP,
    SelectedLibraryContract.YT_DLP_AUTHORITY_TEMP
)
private val PORTABLE_YT_DLP_KEYS = setOf(
    "schemaVersion",
    "kind",
    "libraryId",
    "version",
    "sha256",
    "receiptSha256",
    "receipt",
    AUTHORITY_INTEGRITY_SHA256
)
