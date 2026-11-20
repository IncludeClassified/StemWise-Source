package app.stemwise.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class YoutubeDlUpdatePolicyTest {
    @Test
    fun bundledExtractorHasAnEligibleSignedReceiptMatchingItsActualBytes() {
        val raw = File(projectRoot(), "app/src/main/res/raw")
        val binary = File(raw, "ytdlp")
        val receipt = decodeYtDlpReceipt(File(raw, "yt_dlp_bundled_receipt.txt").readBytes())
        validateYtDlpReceipt(receipt, pinnedKey())
        assertEquals(YT_DLP_BUNDLED_SHA256, YtDlpReleaseSecurity.sha256(binary))
        assertEquals(receipt.sha256, YtDlpReleaseSecurity.sha256(binary))
        assertEquals(receipt.version, YtDlpReleaseSecurity.versionFromBinary(binary))
        assertTrue(receipt.version >= YtDlpDateVersion.parse(YT_DLP_MINIMUM_SAFE_VERSION))
    }

    @Test
    fun engineIsTheOnlyOwnerOfYtDlpProcesses() {
        val root = projectRoot()
        val production = File(root, "app/src/main/java")
        val files = production.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        files.forEach { file ->
            val relative = file.relativeTo(production).invariantSeparatorsPath
            val source = file.readText(Charsets.UTF_8)
            assertFalse("Unowned upstream execution in $relative", "YoutubeDL.execute(" in source)
            assertFalse("Unverified upstream update in $relative", "updateYoutubeDL(" in source)
            if (relative != "app/stemwise/integration/YoutubeDlEngine.kt") {
                assertFalse("Process destruction outside engine in $relative", "YoutubeDL.destroyProcessById(" in source)
            }
            if (relative !in setOf("app/stemwise/integration/YoutubeDlEngine.kt",
                    "app/stemwise/integration/BoundYtDlpProcess.kt")) {
                assertFalse("Execution outside engine adapter in $relative", "executeBoundYtDlp(" in source)
            }
        }
    }

    @Test
    fun dateVersionsAreCanonicalAndMonotonic() {
        val floor = YtDlpDateVersion.parse(YT_DLP_MINIMUM_SAFE_VERSION)
        assertTrue(YtDlpDateVersion.parse("2026.07.04.1") > floor)
        assertTrue(YtDlpDateVersion.parse("2026.08.01") > floor)
        listOf("2026.7.04", "2026.07.4", "2026.07.04.0", "2026.07.04.01", "2026.02.30")
            .forEach { invalid ->
                assertThrows(IllegalStateException::class.java) {
                    YtDlpDateVersion.parse(invalid)
                }
            }
    }

    @Test
    fun malformedOrFuturePrivateHighWaterFailsClosedAndValidHigherFloorIsPreserved() {
        assertEquals(
            YtDlpDateVersion.parse("2026.08.19"),
            parsePrivateYtDlpHighWater("2026.08.19".toByteArray())
        )
        listOf(byteArrayOf(0, 1, 2), "2026.08.19.0".toByteArray()).forEach { invalid ->
            val failure = assertThrows(IllegalStateException::class.java) {
                parsePrivateYtDlpHighWater(invalid)
            }
            assertEquals("YT_DLP_PRIVATE_STATE_INVALID", failure.message)
        }
    }

    @Test
    fun officialSignedManifestValidatesAndBindsExactBinaryHash() {
        val checksums = fixtureChecksums()
        YtDlpReleaseSecurity.verifyChecksumsSignature(
            checksums,
            fixtureSignature(),
            pinnedKey()
        )
        assertEquals(
            "495be29ff4d9d4e9be7eabdfef225221e5d5282e77f2f505abc6dca80349f3fd",
            YtDlpReleaseSecurity.exactAssetSha256(checksums)
        )
    }

    @Test
    fun signatureHashAndTruncationFailuresFailClosed() {
        val key = pinnedKey()
        val checksums = fixtureChecksums()
        assertThrows(Throwable::class.java) {
            YtDlpReleaseSecurity.verifyChecksumsSignature(
                checksums,
                fixtureSignature().copyOf(48),
                key
            )
        }
        assertThrows(Throwable::class.java) {
            YtDlpReleaseSecurity.verifyChecksumsSignature(
                checksums,
                fixtureSignature() + fixtureSignature(),
                key
            )
        }
        val tamperedKey = key.copyOf().also { it[it.lastIndex / 2] = (it[it.lastIndex / 2].toInt() xor 1).toByte() }
        assertThrows(Throwable::class.java) {
            YtDlpReleaseSecurity.verifyChecksumsSignature(checksums, fixtureSignature(), tamperedKey)
        }
        val receipt = YtDlpVerifiedReceipt(
            YtDlpDateVersion.parse("2026.07.04"),
            "0".repeat(64),
            checksums,
            fixtureSignature()
        )
        assertThrows(IllegalStateException::class.java) {
            validateYtDlpReceipt(receipt, key)
        }
        assertThrows(IllegalStateException::class.java) {
            decodeYtDlpReceipt(ByteArray(200 * 1024))
        }
    }

    @Test
    fun checksumParserRejectsDuplicateAndMalformedLines() {
        val valid = fixtureChecksums().toString(Charsets.UTF_8)
        val ytDlpLine = valid.lineSequence().first { it.endsWith("  yt-dlp") }
        assertThrows(IllegalStateException::class.java) {
            YtDlpReleaseSecurity.exactAssetSha256(
                (valid + ytDlpLine + "\n").toByteArray()
            )
        }
        assertThrows(IllegalStateException::class.java) {
            YtDlpReleaseSecurity.exactAssetSha256("not-a-checksum\n".toByteArray())
        }
    }

    @Test
    fun binaryVersionRequiresCanonicalStableOfficialMetadata() {
        fun archive(versionFile: String, prefix: ByteArray = byteArrayOf()): File =
            File.createTempFile("stemwise-ytdlp-", ".zip")
            .also { file ->
                file.outputStream().use { output ->
                    output.write(prefix)
                    ZipOutputStream(output).use { zip ->
                        zip.putNextEntry(ZipEntry("yt_dlp/version.py"))
                        zip.write(versionFile.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }
                }
            }

        val validMetadata =
            "__version__ = '2026.08.19'\nCHANNEL = 'stable'\nORIGIN = 'yt-dlp/yt-dlp'\n"
        listOf(
            archive(validMetadata),
            archive(validMetadata, "#!/usr/bin/env python3\n".toByteArray(Charsets.US_ASCII))
        ).forEach { valid ->
            try {
                assertEquals(
                    YtDlpDateVersion.parse("2026.08.19"),
                    YtDlpReleaseSecurity.versionFromBinary(valid)
                )
            } finally {
                valid.delete()
            }
        }
        val nonCanonicalPrefix = archive(
            validMetadata,
            "#!/usr/bin/python3\n".toByteArray(Charsets.US_ASCII)
        )
        try {
            assertThrows(Throwable::class.java) {
                YtDlpReleaseSecurity.versionFromBinary(nonCanonicalPrefix)
            }
        } finally {
            nonCanonicalPrefix.delete()
        }
        listOf(
            "__version__ = '2026.8.19'\nCHANNEL = 'stable'\nORIGIN = 'yt-dlp/yt-dlp'\n",
            "__version__ = '2026.08.19'\nCHANNEL = 'nightly'\nORIGIN = 'yt-dlp/yt-dlp'\n",
            "__version__ = '2026.08.19'\nCHANNEL = 'stable'\nORIGIN = 'someone/else'\n"
        ).forEach { invalidVersionFile ->
            val invalid = archive(invalidVersionFile)
            try {
                assertThrows(Throwable::class.java) {
                    YtDlpReleaseSecurity.versionFromBinary(invalid)
                }
            } finally {
                invalid.delete()
            }
        }
    }

    private fun projectRoot(): File {
        val working = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(working) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory }
            ?: error("Could not locate StemWise project from ${working.absolutePath}")
    }

    private fun pinnedKey(): ByteArray = File(
        projectRoot(),
        "app/src/main/res/raw/yt_dlp_release_signing_public_key.asc"
    ).readBytes()

    private fun fixtureChecksums(): ByteArray = Base64.getDecoder().decode(FIXTURE_SUMS_BASE64)

    private fun fixtureSignature(): ByteArray = Base64.getDecoder().decode(FIXTURE_SIG_BASE64)

    private companion object {
        const val FIXTURE_SUMS_BASE64 =
            "NDk1YmUyOWZmNGQ5ZDRlOWJlN2VhYmRmZWYyMjUyMjFlNWQ1MjgyZTc3ZjJmNTA1YWJjNmRjYTgwMzQ5ZjNmZCAgeXQtZGxwCjUyZmUzYzI2ZGNmNzFmYmRjODViNTI4NTg5MDIwYmIwYjhlMzgzMTU1Y2ZhODFiNjRkZDQ0N2JiZTM1ZTI0YjggIHl0LWRscC5leGUKMzFjMzI0NTdkMWE1NzNhMzQxYmIwOTI5Mzg2YzYyNGZlNDczMzlhNTMzODgyOWU2ZTljOTQ1NGJkZmE3Mzk3YSAgeXQtZGxwLnRhci5negoxNTI1NjkwYjAzN2VjYzBiYjY3N2UzOGU3MTQ3YjAwMjUxNzljYmM5YThkMGM1NzI2NGUzMTAwYjE4MDk5MjgwICB5dC1kbHBfYXJtNjQuZXhlCjZiYmIzZDMxNGNkZTRmZWJlMzZlNWZhMWQ1NTQ2MmUyOWM5NzRmNjM0NDRlNzA3ODcxODM0ZjZkOGNjMjEwYWUgIHl0LWRscF9saW51eApkN2QyZDA5ZTkwMGI1YWUxMTgyMWI1Nzg0YjE4Y2YwNjQ5ODRhMmJkODhiMWNhNWM3OThkNzQ0YmNiZTM2NThiICB5dC1kbHBfbGludXguemlwCmI2Y2U5NzY0Njc3MzA3MGQ3YTdmZmQ2YmJiZGNhZWNiNDdjNDg0ODM5MDljNTRjOTE1YmYwOGE3YTliNWUwYjEgIHl0LWRscF9saW51eF9hYXJjaDY0CjA1NTRkMzliMjJlMDM5YjRjMGY3MGE0OTJiOTg1Mjg3MGYyODFlZDZhOTNlYjc1ZTc4ZjE1MGIzMmRmNzY1NDMgIHl0LWRscF9saW51eF9hYXJjaDY0LnppcApmZmZkYTk2ZGVkOGU3YTUxYzUzNjRkNmQwM2ZlNzZkYWUwZWJjNjUyNThlZDJkOTY2MGQ5ZGYxYzcxYmI5YzYwICB5dC1kbHBfbGludXhfYXJtdjdsLnppcAo0OThiZDBkYWUxNzg1NWM1OTlkMzcxZDY4ZWM1YmFmYzQzOWE5ZDg2NDBlODM4YmUyNWM3NjVhOTc5MmYyNjFiICB5dC1kbHBfbWFjb3MKYjA3MjQ0NzBhMGNmNmRhZTUxNzVhODdlZWUwNWQ2ZTc1YzVhMGMxMGQyYzMwMTUxNjZiZDRkMzRlOTJiMWI3YiAgeXQtZGxwX21hY29zLnppcApmNzQzOWVjMmUzZmZlNjllMDZhYzIzM2Y4M2YwZDk2ODdiODkxMDU5MzkxMjliZGRjYmY3NGU1ZGUwZjJiNDBlICB5dC1kbHBfbXVzbGxpbnV4CmVlNjZhZTE1OTg3ZWUxYWE5MTFlNzYyODgzZTk0NTg2ZmNhOTU4YmEwMzA0MGY2NTljNjc0MTBiNDY3MjQ0NGUgIHl0LWRscF9tdXNsbGludXguemlwCjlhNmE0ZGU4OGYzNWRjNjhjMTc2Mzk0NWZiYjQxN2UwOTJlYmQ5YWZjNWQ2NjA1MmFjMzFiNjhkNDA1YTEyYTcgIHl0LWRscF9tdXNsbGludXhfYWFyY2g2NApmYmJkNzFlYjk2NDUxMzk1NDIzMmM3MDVlODJmMzZiOTgyZmVjNWFmNDc3MTgwODhmYjVlYWViY2E2NDIxZmMxICB5dC1kbHBfbXVzbGxpbnV4X2FhcmNoNjQuemlwCjkwMjU0ODQ1YmU1MjgyYjFmNGQ4NDNhODczYWJmZjA0ZjU2OWY4NTdmNjQyNTBmODMzZmUxNTJiMjFlZWMxNTIgIHl0LWRscF93aW4uemlwCjk3OTU3NjU3M2EyZWJlNTJjMzc3YTZjYzAyZGI5MTM5MGVkNjI2YmRmOTQzNjhlZWRkYTJjNDJkZTNhOWI1MjMgIHl0LWRscF93aW5fYXJtNjQuemlwCmVjOGI4OTZjY2E3ODgzY2ZlMzMyNGU2MTFjYzdiMWM5MjU4YjI0M2EwNDA1Mjk3YzZlZDdlN2ZjYTYxZGI4ODAgIHl0LWRscF93aW5feDg2LnppcApjYWMzYTkzNTkzNjdlYTgxOTI4OWFmZTRjNTlmM2U0MzI4NjVkYWZiNmIwOGM5MzhlMmMyMmI0NTM0ODk4ZjEyICB5dC1kbHBfeDg2LmV4ZQo="
        const val FIXTURE_SIG_BASE64 =
            "iQIzBAABCgAdFiEErAy75oSNaoc0ZK9OV89lkztadYEFAmpJjAgACgkQV89lkztadYEwXhAA83HUDaIvZG06vcEX8guAXSXLNqxlQUWRtvndboyFYOWyP6tGFdNMtNM2xb27Hds3b3eZv+VqSGY6vP9pC7jP+ch0bUYzAwDmpmwqNxKqjUfpif7UkrPU2bggub0kd2Jcl8KsRDKF/pAJeVXabHiKFN3a/ydEhFxZS2PdluXoVbxYZWjNo2CRoYjfWdZ7OVV1Pf7AKZF+ESDmEZoamK8tG8SptW2teFWSqzAgeR8Ss9j2a+lQuzgAlMESe96LpAKIYWw9iOToXVTWrL5Ss4P/pUTwdmLAFfi9awCALyLP7wtrS9ZJEJi86TsZ8/Y+xi8hysl2FWUrNnmBrZjTjkn6WjJjUbXTsnZfHlj0XydGiUMA/3odAhN9ECMjikIeqW4CtVxnk9UnUx757eZKb2DpQOtCr72boVZd08dZgONpahOwiijqJFiheEhzIR8XsrLHf+t++EgPA+HbTuxv6FJupwpVWP4hoRLy2hsSs+X0IdXKCGGIdDR+cQG/SHryVEV2zWbBkjvBxsxuW9DFBD4pKLO9iRA5cv/dUaPE4rlfdD4eUjUsGY+Tc6ilR8cMnw1R5j4tJ3ZSIqwGopCHVC8SvQWNlDEWugT+VrPQisF6iQwd/3kZrRdGWyYkSLjvsvdRiipYqYRVjmKldTdrlwkjvC3WHj0D6F4c+LYXSWzkFco="
    }
}
