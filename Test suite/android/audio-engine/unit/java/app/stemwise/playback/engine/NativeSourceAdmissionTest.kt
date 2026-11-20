package app.stemwise.playback.engine

import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class NativeSourceAdmissionTest {
    @Test fun externalByteAndExtentChangesAreRecoverableWithoutHidingProgrammingOrCancellationErrors() {
        val path = Files.createTempFile("source-admission-read-", ".bin")
        try {
            RandomAccessFile(path.toFile(), "rw").use { file ->
                file.write(byteArrayOf(1, 2, 3))
                val owner = NativeSourceAdmission()
                owner.admitBytes(file.channel, 0, 3) {}
                file.seek(1); file.write(9)
                assertThrows(java.io.IOException::class.java) { owner.admitBytes(file.channel, 0, 3) {} }
                assertThrows(java.io.IOException::class.java) { owner.admitBytes(file.channel, 0, 4) {} }
                assertThrows(InterruptedException::class.java) { owner.admitBytes(file.channel, 0, 3) { throw InterruptedException() } }
                assertThrows(IllegalArgumentException::class.java) { NativeSourceAdmission("bad hash").admitBytes(file.channel, 0, 3) {} }
                assertThrows(IllegalStateException::class.java) { NativeSourceAdmission().admitTrack(track) }
                file.seek(1); file.write(2)
                owner.admitBytes(file.channel, 0, 3) {}
                assertEquals(3L, file.length())
            }
        } finally { Files.delete(path) }
    }

    @Test fun verifiedReopenReusesHeaderFactsButChangedBytesCannotReachThem() {
        val path = Files.createTempFile("source-admission-facts-", ".bin")
        try {
            RandomAccessFile(path.toFile(), "rw").use { file ->
                file.write(byteArrayOf(1, 2, 3))
                val owner = NativeSourceAdmission()
                val facts = app.stemwise.data.PreparedSourceAudioFacts(48_000, 2, 32, true, 100, mime = "audio/wavpack")
                var inspections = 0
                val inspect = { inspections++; facts }
                assertThrows(IllegalStateException::class.java) { owner.floatAudioFacts(inspect) }
                owner.admitBytes(file.channel, 0, 3) {}
                assertSame(facts, owner.floatAudioFacts(inspect))
                owner.admitBytes(file.channel, 0, 3) {}
                assertSame(facts, owner.floatAudioFacts(inspect))
                assertEquals(1, inspections)
                file.seek(1); file.write(9)
                assertThrows(java.io.IOException::class.java) { owner.admitBytes(file.channel, 0, 3) {} }
                assertEquals(1, inspections)
            }
        } finally { Files.delete(path) }
    }

    private val track = NativeAudioTrackInfo(0, "audio/raw", null, 100_000L, 48_000, 2, 0, 0)

    @Test fun exactDescriptorSliceRejectsChangedBytesAndExtentButPreservesAdmittedSource() {
        val path = Files.createTempFile("source-admission-", ".bin")
        try {
            RandomAccessFile(path.toFile(), "rw").use { file ->
                file.write(byteArrayOf(9, 1, 2, 3, 8))
                val owner = NativeSourceAdmission("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81")
                owner.admitBytes(file.channel, 1, 3) {}
                owner.admitTrack(track)
                val pcm = NativePcmFormat(48_000, 2, NativePcmEncoding.PCM_FLOAT)
                owner.admitPcmFormat(pcm)
                assertThrows(IllegalStateException::class.java) {
                    owner.admitPcmFormat(NativePcmFormat(44_100, 2, NativePcmEncoding.PCM_FLOAT))
                }
                owner.admitPcmFormat(pcm)
                file.seek(2); file.write(4)
                assertThrows(java.io.IOException::class.java) { owner.admitBytes(file.channel, 1, 3) {} }
                file.seek(2); file.write(2)
                owner.admitBytes(file.channel, 1, 3) {}
                owner.admitTrack(track)
                assertThrows(IllegalStateException::class.java) { owner.admitTrack(track.copy(sampleRateHz = 44_100)) }
                assertThrows(IllegalStateException::class.java) { owner.admitTrack(track.copy(durationUs = 200_000L)) }
                owner.admitTrack(track)
                assertThrows(java.io.IOException::class.java) { owner.admitBytes(file.channel, 1, 9) {} }
            }
        } finally { Files.delete(path) }
    }

    @Test fun firstAdmissionFreezesOpaqueIdentityAndCancellationCannotReplaceIt() {
        val path = Files.createTempFile("source-admission-", ".bin")
        try {
            RandomAccessFile(path.toFile(), "rw").use { file ->
                file.write(byteArrayOf(1, 2, 3))
                val owner = NativeSourceAdmission()
                owner.admitBytes(file.channel, 0, 3) {}
                owner.admitTrack(track)
                file.seek(0); file.write(byteArrayOf(4, 5, 6))
                assertThrows(InterruptedException::class.java) {
                    owner.admitBytes(file.channel, 0, 3) { throw InterruptedException() }
                }
                assertThrows(java.io.IOException::class.java) { owner.admitBytes(file.channel, 0, 3) {} }
                file.seek(0); file.write(byteArrayOf(1, 2, 3, 4))
                assertThrows(java.io.IOException::class.java) { owner.admitBytes(file.channel, 0, 4) {} }
                owner.admitBytes(file.channel, 0, 3) {}
                owner.admitTrack(track)
            }
        } finally { Files.delete(path) }
    }

    @Test fun formatCannotBeAdmittedWithoutBytesAndMalformedExpectedHashIsRejected() {
        assertThrows(IllegalStateException::class.java) { NativeSourceAdmission().admitTrack(track) }
        val path = Files.createTempFile("source-admission-", ".bin")
        try {
            RandomAccessFile(path.toFile(), "rw").use { file ->
                file.write(1)
                assertThrows(IllegalArgumentException::class.java) {
                    NativeSourceAdmission("opaque-revision").admitBytes(file.channel, 0, 1) {}
                }
                assertThrows(java.io.IOException::class.java) {
                    NativeSourceAdmission("0".repeat(64)).admitBytes(file.channel, 0, 1) {}
                }
            }
        } finally { Files.delete(path) }
    }
}
