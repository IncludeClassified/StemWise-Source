package app.stemwise.debug

import java.io.FileOutputStream
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryPressureWaveFixtureTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun coldFixtureIsCompleteAndSecondEnsureReusesIt() {
        val file = temporaryFolder.newFolder("cold").resolve("source.wav")

        val created = LibraryPressureWaveFixture.ensure(file, sourceClass = 3)

        assertFalse(created.reused)
        assertEquals(LibraryPressureWaveFixture.expectedByteCount(), created.byteCount)
        assertEquals(created.byteCount, file.length())
        assertEquals(listOf(1_599, -2_351, -1_599, 2_351), firstStereoFrames(file))

        val reused = LibraryPressureWaveFixture.ensure(file, sourceClass = 3)
        assertTrue(reused.reused)
        assertEquals(created.byteCount, reused.byteCount)
        assertEquals(listOf("source.wav"), requireNotNull(file.parentFile).list()?.sorted())
    }

    @Test
    fun interruptedFixtureIsReplacedWithoutDeletingTheDirectory() {
        val directory = temporaryFolder.newFolder("interrupted")
        val file = directory.resolve("source.wav")
        FileOutputStream(file).use { output -> output.write(byteArrayOf(1, 2, 3, 4)) }

        val repaired = LibraryPressureWaveFixture.ensure(file, sourceClass = 15)

        assertFalse(repaired.reused)
        assertEquals(LibraryPressureWaveFixture.expectedByteCount(), file.length())
        assertEquals(listOf(4_395, -6_155, -4_395, 6_155), firstStereoFrames(file))
        assertEquals(listOf("source.wav"), directory.list()?.sorted())
    }

    private fun firstStereoFrames(file: java.io.File): List<Int> =
        RandomAccessFile(file, "r").use { input ->
            input.seek(LibraryPressureWaveFixture.HEADER_BYTES)
            List(4) { readLittleEndianShort(input) }
        }

    private fun readLittleEndianShort(input: RandomAccessFile): Int {
        val low = input.readUnsignedByte()
        val high = input.readUnsignedByte()
        return ((high shl 8) or low).toShort().toInt()
    }
}
