package app.stemwise.integration

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class YtDlpBinaryInspectionTest {
    @Test fun oneAdmissionReusesChecksButReplacementAndNewAdmissionRevalidate() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "Test suite").isDirectory }
        val work = File(root, "Test suite/work").also { check(it.isDirectory || it.mkdirs()) }
        val directory = Files.createTempDirectory(work.toPath(), "binary-inspection-").toFile()
        try {
            fun archive(file: File, version: String) = file.also {
                it.outputStream().use { output ->
                    output.write("#!/usr/bin/env python3\n".toByteArray(Charsets.US_ASCII))
                    ZipOutputStream(output).use { zip ->
                        zip.putNextEntry(ZipEntry("yt_dlp/version.py"))
                        zip.write("__version__ = '$version'\nCHANNEL = 'stable'\nORIGIN = 'yt-dlp/yt-dlp'\n".toByteArray())
                        zip.closeEntry()
                    }
                }
            }
            val binary = archive(File(directory, "yt-dlp"), "2026.10.21")
            var hashes = 0
            var versions = 0
            fun inspection() = YtDlpBinaryInspection(
                { hashes++; YtDlpReleaseSecurity.sha256(it) },
                { versions++; YtDlpReleaseSecurity.versionFromBinary(it) })
            val first = inspection()
            val initialHash = first.hash(binary)
            repeat(3) {
                assertEquals(initialHash, first.hash(binary))
                assertEquals(YtDlpDateVersion.parse("2026.10.21"), first.version(binary))
            }
            assertEquals(1, hashes)
            assertEquals(1, versions)
            val replacement = archive(File(directory, "candidate"), "2026.10.22")
            Files.move(replacement.toPath(), binary.toPath(), StandardCopyOption.REPLACE_EXISTING)
            assertNotEquals(initialHash, first.hash(binary))
            assertEquals(YtDlpDateVersion.parse("2026.10.22"), first.version(binary))
            assertEquals(2, hashes)
            assertEquals(2, versions)
            val next = inspection()
            assertEquals(first.hash(binary), next.hash(binary))
            assertEquals(first.version(binary), next.version(binary))
            assertEquals(3, hashes)
            assertEquals(3, versions)
        } finally { check(directory.deleteRecursively()) }
    }
}
