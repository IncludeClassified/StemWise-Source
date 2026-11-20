package app.stemwise.integration

import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider
import org.bouncycastle.bcpg.HashAlgorithmTags
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.LocalDate
import java.util.Locale
import org.apache.commons.compress.archivers.zip.ZipFile

internal const val YT_DLP_ASSET_NAME = "yt-dlp"
internal const val YT_DLP_CHECKSUMS_NAME = "SHA2-256SUMS"
internal const val YT_DLP_SIGNATURE_NAME = "SHA2-256SUMS.sig"
internal const val YT_DLP_BUNDLED_VERSION = "2026.10.21"
internal const val YT_DLP_MINIMUM_SAFE_VERSION = "2026.07.04"
internal const val YT_DLP_BUNDLED_SHA256 =
    "b7af77f686e95f7845ce8d09d21957718febb8b48b30555adb9dd2292ad7bf14"
internal const val YT_DLP_SIGNING_KEY_FINGERPRINT =
    "AC0CBBE6848D6A873464AF4E57CF65933B5A7581"
internal const val MAX_YT_DLP_BINARY_BYTES = 16L * 1024L * 1024L
internal const val MAX_YT_DLP_CHECKSUMS_BYTES = 64 * 1024
internal const val MAX_YT_DLP_SIGNATURE_BYTES = 8 * 1024
internal const val MAX_YT_DLP_PUBLIC_KEY_BYTES = 16 * 1024
internal const val MAX_YT_DLP_VERSION_ENTRY_BYTES = 4 * 1024

internal data class YtDlpDateVersion(
    val year: Int,
    val month: Int,
    val day: Int,
    val revision: Int = 0
) : Comparable<YtDlpDateVersion> {
    override fun compareTo(other: YtDlpDateVersion): Int =
        compareValuesBy(this, other, YtDlpDateVersion::year, YtDlpDateVersion::month,
            YtDlpDateVersion::day, YtDlpDateVersion::revision)

    override fun toString(): String = buildString {
        append(String.format(Locale.ROOT, "%04d.%02d.%02d", year, month, day))
        if (revision != 0) append('.').append(revision)
    }

    companion object {
        private val EXACT = Regex("^(20\\d{2})\\.(0[1-9]|1[0-2])\\.(0[1-9]|[12]\\d|3[01])(?:\\.(\\d{1,4}))?$")

        fun parse(value: String): YtDlpDateVersion {
            val match = checkNotNull(EXACT.matchEntire(value)) { "YT_DLP_VERSION_INVALID" }
            val year = match.groupValues[1].toInt()
            val month = match.groupValues[2].toInt()
            val day = match.groupValues[3].toInt()
            val revisionText = match.groupValues[4]
            val revision = revisionText.takeIf(String::isNotEmpty)?.toInt() ?: 0
            check(revisionText.isEmpty() || (revision > 0 && !revisionText.startsWith('0'))) {
                "YT_DLP_VERSION_INVALID"
            }
            try {
                LocalDate.of(year, month, day)
            } catch (_: DateTimeException) {
                error("YT_DLP_VERSION_INVALID")
            }
            return YtDlpDateVersion(year, month, day, revision).also {
                check(it.toString() == value) { "YT_DLP_VERSION_INVALID" }
            }
        }
    }
}

internal object YtDlpReleaseSecurity {
    private val checksumLine = Regex("^([0-9a-f]{64}) [ *]([A-Za-z0-9._-]+)$")
    private val versionLine = Regex("(?m)^__version__ = '([^']+)'$")
    private val zipLocalFileHeader = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
    private val canonicalZipAppShebang = "#!/usr/bin/env python3\n".toByteArray(Charsets.US_ASCII)

    fun verifyChecksumsSignature(
        checksums: ByteArray,
        detachedSignature: ByteArray,
        armoredPublicKey: ByteArray
    ) {
        check(checksums.size in 1..MAX_YT_DLP_CHECKSUMS_BYTES) { "YT_DLP_CHECKSUMS_INVALID" }
        check(detachedSignature.size in 1..MAX_YT_DLP_SIGNATURE_BYTES) {
            "YT_DLP_SIGNATURE_INVALID"
        }
        check(armoredPublicKey.size in 1..MAX_YT_DLP_PUBLIC_KEY_BYTES) {
            "YT_DLP_PUBLIC_KEY_INVALID"
        }

        val calculator = BcKeyFingerprintCalculator()
        val keys = PGPPublicKeyRingCollection(
            PGPUtil.getDecoderStream(ByteArrayInputStream(armoredPublicKey)),
            calculator
        )
        val signingKey = findPinnedSigningKey(keys)
        val signatures = PGPObjectFactory(
            PGPUtil.getDecoderStream(ByteArrayInputStream(detachedSignature)),
            calculator
        )
        val signatureList = signatures.nextObject() as? PGPSignatureList
            ?: error("YT_DLP_SIGNATURE_INVALID")
        check(signatures.nextObject() == null && signatureList.size() == 1) {
            "YT_DLP_SIGNATURE_INVALID"
        }
        val signature = signatureList[0]
        check(signature.signatureType == PGPSignature.BINARY_DOCUMENT) {
            "YT_DLP_SIGNATURE_INVALID"
        }
        check(signature.keyID == signingKey.keyID) { "YT_DLP_SIGNING_KEY_INVALID" }
        check(signature.hashAlgorithm in setOf(
            HashAlgorithmTags.SHA256,
            HashAlgorithmTags.SHA384,
            HashAlgorithmTags.SHA512
        )) { "YT_DLP_SIGNATURE_HASH_INVALID" }
        signature.init(
            BcPGPContentVerifierBuilderProvider(),
            signingKey
        )
        signature.update(checksums)
        check(signature.verify()) { "YT_DLP_SIGNATURE_INVALID" }
    }

    fun exactAssetSha256(checksums: ByteArray, assetName: String = YT_DLP_ASSET_NAME): String {
        check(checksums.size in 1..MAX_YT_DLP_CHECKSUMS_BYTES) { "YT_DLP_CHECKSUMS_INVALID" }
        val text = strictUtf8(checksums, "YT_DLP_CHECKSUMS_INVALID")
        check(!text.startsWith('\uFEFF') && '\r' !in text && '\u0000' !in text) {
            "YT_DLP_CHECKSUMS_INVALID"
        }
        val matches = text.split('\n')
            .filter(String::isNotEmpty)
            .map { checkNotNull(checksumLine.matchEntire(it)) { "YT_DLP_CHECKSUMS_INVALID" } }
            .filter { it.groupValues[2] == assetName }
        check(matches.size == 1) { "YT_DLP_CHECKSUMS_INVALID" }
        return matches.single().groupValues[1]
    }

    fun versionFromBinary(binary: File): YtDlpDateVersion {
        val binaryLength = binary.length()
        check(binary.isFile && binaryLength in 1..MAX_YT_DLP_BINARY_BYTES) {
            "YT_DLP_BINARY_INVALID"
        }
        return withVersionArchive(binary, binaryLength) { zip ->
            val entries = zip.entries.asSequence()
                .filter { !it.isDirectory && it.name == "yt_dlp/version.py" }
                .toList()
            check(entries.size == 1) { "YT_DLP_BINARY_VERSION_INVALID" }
            val entry = entries.single()
            check(entry.size in 1..MAX_YT_DLP_VERSION_ENTRY_BYTES.toLong()) {
                "YT_DLP_BINARY_VERSION_INVALID"
            }
            val bytes = zip.getInputStream(entry).use {
                readBounded(it, MAX_YT_DLP_VERSION_ENTRY_BYTES, "YT_DLP_BINARY_VERSION_INVALID")
            }
            val text = strictUtf8(bytes, "YT_DLP_BINARY_VERSION_INVALID")
            val versions = versionLine.findAll(text).toList()
            check(versions.size == 1 && "CHANNEL = 'stable'" in text &&
                "ORIGIN = 'yt-dlp/yt-dlp'" in text) {
                "YT_DLP_BINARY_VERSION_INVALID"
            }
            YtDlpDateVersion.parse(versions.single().groupValues[1])
        }
    }

    private fun <T> withVersionArchive(
        binary: File,
        binaryLength: Long,
        block: (ZipFile) -> T
    ): T {
        val header = ByteArray(canonicalZipAppShebang.size + zipLocalFileHeader.size)
        binary.inputStream().buffered().use { input ->
            var headerBytes = 0
            while (headerBytes < header.size) {
                val read = input.read(header, headerBytes, header.size - headerBytes)
                check(read >= 0) { "YT_DLP_BINARY_VERSION_INVALID" }
                headerBytes += read
            }
            if (header.copyOfRange(0, zipLocalFileHeader.size).contentEquals(zipLocalFileHeader)) {
                return ZipFile.builder().setFile(binary).get().use(block)
            }
            check(header.copyOfRange(0, canonicalZipAppShebang.size)
                .contentEquals(canonicalZipAppShebang) &&
                header.copyOfRange(canonicalZipAppShebang.size, header.size)
                    .contentEquals(zipLocalFileHeader)) {
                "YT_DLP_BINARY_VERSION_INVALID"
            }
        }
        check(binary.length() == binaryLength) { "YT_DLP_BINARY_VERSION_INVALID" }
        return ZipFile.builder().setFile(binary).get().use(block)
    }

    fun sha256(file: File): String {
        check(file.isFile && file.length() in 1..MAX_YT_DLP_BINARY_BYTES) {
            "YT_DLP_BINARY_INVALID"
        }
        return file.inputStream().buffered().use(::sha256)
    }

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().toHexString()
    }

    fun readBounded(input: InputStream, maximum: Int, failure: String): ByteArray {
        check(maximum > 0) { failure }
        val output = ByteArrayOutputStream(minOf(maximum, 16 * 1024))
        val buffer = ByteArray(16 * 1024)
        var copied = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            copied = Math.addExact(copied, read)
            check(copied <= maximum) { failure }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun findPinnedSigningKey(keys: PGPPublicKeyRingCollection): PGPPublicKey {
        val matches = mutableListOf<PGPPublicKey>()
        val rings = keys.keyRings
        while (rings.hasNext()) {
            val publicKeys = rings.next().publicKeys
            while (publicKeys.hasNext()) {
                val key = publicKeys.next()
                val fingerprint = key.fingerprint.joinToString("") {
                    "%02X".format(Locale.ROOT, it.toInt() and 0xff)
                }
                if (fingerprint == YT_DLP_SIGNING_KEY_FINGERPRINT) matches += key
            }
        }
        check(matches.size == 1 && matches.single().isMasterKey) { "YT_DLP_SIGNING_KEY_INVALID" }
        return matches.single()
    }

    private fun strictUtf8(bytes: ByteArray, failure: String): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: Throwable) {
        error(failure)
    }
}
