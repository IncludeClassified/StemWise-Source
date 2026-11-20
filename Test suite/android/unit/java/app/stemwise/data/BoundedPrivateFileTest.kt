package app.stemwise.data

import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedPrivateFileTest {
    @Test
    fun `private recovery reads exact bounded bytes and refuses oversize`() {
        val root = Files.createTempDirectory("stemwise-bounded-private-").toFile()
        try {
            val valid = root.resolve("valid.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            assertArrayEquals(
                byteArrayOf(1, 2, 3),
                readBoundedPrivateFile(valid, 3, "RECOVERY_PENDING")
            )

            val oversized = root.resolve("oversized.bin").apply { writeBytes(ByteArray(4)) }
            assertEquals(
                "RECOVERY_PENDING",
                runCatching {
                    readBoundedPrivateFile(oversized, 3, "RECOVERY_PENDING")
                }.exceptionOrNull()?.message
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
