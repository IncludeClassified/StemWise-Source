package app.stemwise.integration

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.io.IOUtils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeArchiveCompatibilityTest {
    @Test(timeout = 5_000)
    fun bundledArchiveApisPreserveExactBytesAndRejectTruncation() {
        val payload = byteArrayOf(0, 1, 127, -128, -1)
        val archive = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("runtime/payload"))
                zip.write(payload)
                zip.closeEntry()
            }
        }.toByteArray()
        ZipArchiveInputStream(ByteArrayInputStream(archive)).use { zip ->
            assertEquals("runtime/payload", zip.nextZipEntry!!.name)
            assertArrayEquals(payload, IOUtils.toByteArray(zip))
            assertNull(zip.nextZipEntry)
        }
        val root = generateSequence(java.io.File(System.getProperty("user.dir"))) { it.parentFile }
            .first { java.io.File(it, "Test suite").isDirectory }
        val work = java.io.File(root, "Test suite/work").also { check(it.isDirectory || it.mkdirs()) }
        val directory = java.nio.file.Files.createTempDirectory(work.toPath(), "runtime-archive-").toFile()
        try {
            val file = directory.resolve("runtime.zip").apply { writeBytes(archive) }
            ZipFile(file).use { zip ->
                assertArrayEquals(payload, zip.getInputStream(zip.getEntry("runtime/payload")).use(IOUtils::toByteArray))
            }
            file.writeBytes(archive.copyOf(12))
            assertThrows(java.io.IOException::class.java) { ZipFile(file).close() }
        } finally {
            check(directory.deleteRecursively())
        }
    }
}
