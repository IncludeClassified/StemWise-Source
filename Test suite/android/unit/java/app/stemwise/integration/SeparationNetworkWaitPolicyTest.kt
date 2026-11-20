package app.stemwise.integration

import app.stemwise.network.UnmeteredNetworkRequiredException
import app.stemwise.data.ProcessingJobEntity
import java.io.File
import java.nio.file.Files
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SeparationNetworkWaitPolicyTest {
    @Test
    fun acknowledgedRemoteReadsRetryFourTimesWithoutResubmissionAndThenStop() = runBlocking {
        val failure = assertThrows(MvsepTransientRemoteFailure::class.java) {
            runBlocking { withAcknowledgedMvsepRead<Unit> { throw SocketTimeoutException("timeout") } }
        }
        var current = job("DOWNLOADING", "accepted-42").copy(kind = SeparationRepository.KIND_SOURCES)
        listOf(30_000L, 60_000L, 120_000L, 240_000L).forEachIndexed { index, delay ->
            val retry = checkNotNull(scheduleAcknowledgedMvsepRetry(current, failure, 1_000L))
            assertEquals(1_000L + delay, retry.nextAttemptAtEpochMs)
            assertEquals("MVSEP_REMOTE_RETRY_${index + 1}", retry.lastErrorCode)
            assertEquals(current.remoteJobId, retry.remoteJobId)
            assertEquals(current.attempts, retry.attempts)
            assertEquals(current.state, retry.state)
            assertEquals(current.sourceVersionId, retry.sourceVersionId)
            assertEquals(current.libraryId, retry.libraryId)
            current = retry
        }
        assertNull(scheduleAcknowledgedMvsepRetry(current, failure, 1_000L))
        current = current.copy(lastErrorCode = null)
        listOf("CREATED", "SUBMISSION_STARTED", "SUBMISSION_UNKNOWN", "ASSEMBLING", "PUBLISHING")
            .forEach { state -> assertNull(scheduleAcknowledgedMvsepRetry(current.copy(state = state), failure, 1_000L)) }
        assertNull(scheduleAcknowledgedMvsepRetry(current.copy(remoteJobId = null), failure, 1_000L))
        assertNull(scheduleAcknowledgedMvsepRetry(current.copy(provider = "other"), failure, 1_000L))
        assertNull(scheduleAcknowledgedMvsepRetry(current.copy(kind = "UNSUPPORTED_KIND"), failure, 1_000L))
        assertEquals("PROCESSING", scheduleAcknowledgedMvsepRetry(current.copy(state = "PROCESSING"), failure, 1_000L)?.state)
        assertNull(scheduleAcknowledgedMvsepRetry(current.copy(lastErrorCode = null), IOException("local disk"), 1_000L))
        assertEquals("read result", withAcknowledgedMvsepRead { "read result" })
    }

    @Test
    fun onlyExplicitTransientRemoteFailuresEnterTheRetryPath() {
        listOf(408, 425, 429, 500, 502, 503, 504).forEach { code ->
            assertThrows(MvsepTransientRemoteFailure::class.java) {
                runBlocking { withAcknowledgedMvsepRead<Unit> { requireSuccessfulMvsepResponse(code) } }
            }
        }
        listOf(301, 400, 401, 403, 404, 422, 501).forEach { code ->
            assertEquals("MVSEP_HTTP_$code", assertThrows(MvsepHttpFailure::class.java) {
                runBlocking { withAcknowledgedMvsepRead<Unit> { requireSuccessfulMvsepResponse(code) } }
            }.message)
        }
        listOf(IOException("local disk"), IllegalStateException("SOURCE_AUDIO_CHANGED"),
            kotlinx.coroutines.CancellationException("cancelled")).forEach { error ->
            val observed = assertThrows(error.javaClass) {
                runBlocking { withAcknowledgedMvsepRead<Unit> { throw error } }
            }
            assertTrue(error === observed)
        }
    }

    @Test
    fun disposableUploadAndStemBytesAreRemovedForEveryRestartAndTerminalState() {
        val directory = Files.createTempDirectory("stemwise-separation-scratch").toFile()
        try {
            listOf(
                SeparationRepository.STATE_SUBMISSION_STARTED,
                SeparationRepository.STATE_SUBMISSION_UNKNOWN,
                SeparationRepository.STATE_PROCESSING,
                SeparationRepository.STATE_FAILED,
                SeparationRepository.STATE_COMPLETE
            ).forEach { state ->
                directory.resolve("upload-original.opus").writeBytes(byteArrayOf(1, 2, 3))
                directory.resolve("active-stem.flac").writeBytes(
                    byteArrayOf(state.length.toByte(), 5, 6)
                )
                cleanupDisposableSeparationScratch(directory)
                assertTrue("state=$state", directory.listFiles().orEmpty().isEmpty())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun partCleanupRetainsTheWorkerOriginalAndFinalCleanupRetiresIt() {
        val project = generateSequence(File(checkNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "AGENTS.md").isFile && File(it, "Test suite").isDirectory }
        val work = File(project, "Test suite/work").apply { check(isDirectory || mkdirs()) }
        val directory = Files.createTempDirectory(work.toPath(), "separation-original-owner-").toFile()
        try {
            val original = directory.resolve("upload-original.flac").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            directory.resolve("active-stem.flac").writeBytes(byteArrayOf(4, 5))
            cleanupDisposableSeparationScratch(directory, preserveOriginal = true)
            org.junit.Assert.assertArrayEquals(byteArrayOf(1, 2, 3), original.readBytes())
            assertEquals(listOf("upload-original.flac"), directory.listFiles()!!.map { it.name })
            assertEquals(6L, requireBoundedPrivatePreparationScratch(listOf(
                "upload-original.flac" to 3L, "active-stem.flac" to 2L, "active-part.flac.part" to 1L)))
            assertThrows(IllegalStateException::class.java) { requireBoundedPrivatePreparationScratch(listOf(
                "upload-original.flac" to MAX_PRIVATE_PREPARATION_ASSET_BYTES,
                "active-stem.flac" to 1L, "active-part.flac.part" to 1L)) }
            cleanupDisposableSeparationScratch(directory)
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun privateScratchOwnershipAllowsOnlyTheBoundedEncodePair() {
        PRIVATE_PREPARATION_SCRATCH_NAMES.forEach { name ->
            assertEquals(
                MAX_PRIVATE_PREPARATION_ASSET_BYTES,
                requireBoundedPrivatePreparationScratch(
                    listOf(name to MAX_PRIVATE_PREPARATION_ASSET_BYTES)
                )
            )
        }
        assertEquals(0L, requireBoundedPrivatePreparationScratch(emptyList()))
        assertEquals(
            2L,
            requireBoundedPrivatePreparationScratch(
                listOf("active-stem.flac" to 1L, "active-part.flac.part" to 1L)
            )
        )
        assertEquals(
            "PREPARATION_SCRATCH_PEAK_EXCEEDED",
            assertThrows(IllegalStateException::class.java) {
                requireBoundedPrivatePreparationScratch(
                    listOf("active-stem.flac.part" to 1L, "active-part.flac.part" to 1L)
                )
            }.message
        )
        assertEquals(
            "PREPARATION_SCRATCH_OWNERSHIP_INVALID",
            assertThrows(IllegalStateException::class.java) {
                requireBoundedPrivatePreparationScratch(listOf("foreign.bin" to 1L))
            }.message
        )
        assertEquals(
            "PREPARATION_SCRATCH_OWNERSHIP_INVALID",
            assertThrows(IllegalStateException::class.java) {
                requireBoundedPrivatePreparationScratch(
                    listOf("upload-original.opus" to MAX_PRIVATE_PREPARATION_ASSET_BYTES + 1L)
                )
            }.message
        )
    }

    @Test
    fun currentPartCommandPinsLossless48kStereoAndTheByteLimit() {
        val command = stemFlacEncodeCommand(
            File("ffmpeg"), File("active-stem.flac"), File("current.flac.part"),
            MAX_PRIVATE_PREPARATION_ASSET_BYTES, channels = 2
        )
        assertTrue(command.containsAll(listOf("flac", "s16", "48000", "2")))
        assertFalse("libopus" in command)
        assertTrue(command.windowed(2).contains(listOf("-fs", "3221225472")))
        assertTrue(command.windowed(2).contains(listOf("-fflags", "+bitexact")))
        assertTrue(command.windowed(2).contains(listOf("-flags:a", "+bitexact")))
        assertTrue(command.last().replace('\\', '/').endsWith("current.flac.part"))
        assertFalse(command.any { "atrim" in it || "apad" in it })
        val bound = stemFlacEncodeCommand(File("ffmpeg"), File("in.flac"), File("out.flac"),
            MAX_PRIVATE_PREPARATION_ASSET_BYTES, channels = 1, endFrame48k = 1_001)
        assertTrue(bound.windowed(2).contains(listOf("-af",
            "pan=stereo|c0=c0|c1=c0,aresample=48000,atrim=end_sample=1001")))
        assertFalse(bound.any { "apad" in it || "asetpts" in it })
    }

    @Test
    fun flac16InspectorRejectsLossyOrWrongBitDepthInput() {
        val file = Files.createTempFile("stemwise-flac16-", ".flac").toFile()
        try {
            file.writeBytes(flacHeader(bitsPerSample = 16))
            val facts = inspectFlac16(file)
            assertEquals(48_000, facts.sampleRateHz)
            assertEquals(2, facts.channelCount)
            assertEquals(48_000L, facts.totalSamples)
            assertEquals(1_000L, facts.durationMs)

            file.writeBytes(flacHeader(bitsPerSample = 24))
            assertEquals(
                "MVSEP_OUTPUT_FORMAT_INVALID",
                assertThrows(IllegalStateException::class.java) { inspectFlac16(file) }.message
            )
            file.writeBytes("not an MP3 renamed to FLAC".toByteArray())
            assertEquals(
                "MVSEP_OUTPUT_FORMAT_INVALID",
                assertThrows(IllegalStateException::class.java) { inspectFlac16(file) }.message
            )
        } finally {
            assertTrue(file.delete())
        }
    }

    private fun flacHeader(bitsPerSample: Int): ByteArray {
        val bytes = ByteArray(42)
        "fLaC".toByteArray().copyInto(bytes)
        bytes[4] = 0x80.toByte()
        bytes[7] = 34
        val packed = (48_000L shl 44) or
            (1L shl 41) or
            ((bitsPerSample - 1L) shl 36) or
            48_000L
        repeat(8) { index ->
            bytes[18 + index] = (packed ushr ((7 - index) * 8)).toByte()
        }
        return bytes
    }

    private fun job(state: String, remoteJobId: String?) = ProcessingJobEntity(
        id = "separation",
        songId = "song",
        sourceVersionId = "source",
        kind = SeparationRepository.KIND_SOURCES,
        provider = SeparationRepository.PROVIDER,
        modelId = "63",
        state = state,
        remoteJobId = remoteJobId,

        attempts = 3,
        nextAttemptAtEpochMs = 15_000L,
        lastErrorCode = null,
        lastErrorMessage = null,
        updatedAtEpochMs = 100L,
        createdAtEpochMs = 1L,
        libraryId = "library"
    )
}
