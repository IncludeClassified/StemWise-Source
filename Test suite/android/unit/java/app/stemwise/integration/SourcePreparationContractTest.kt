package app.stemwise.integration

import org.junit.Assert.*
import org.junit.Test

class SourcePreparationContractTest {
    @Test fun timingMismatchKeepsItsSpecificRecoveryMessageBeforeGenericSourceErrors() {
        val mismatch = runCatching { requireSameSourceTimeline(48_100L, 48_000, 48_000L) }.exceptionOrNull()
        assertEquals("SOURCE_TIMELINE_MISMATCH", checkNotNull(mismatch).message)
        assertEquals("Returned audio does not match the song. The parts were not activated",
            SeparationRepository.friendlyError(mismatch))
        assertEquals("The original or a prepared part changed or is unavailable",
            SeparationRepository.friendlyError(IllegalStateException("SOURCE_AUDIO_MISSING")))
    }

    @Test fun eightDistinctRoutesBindReorderedResponsesByProviderRoleAndNeverArrivalPosition() {
        val plan = eightOperationMergerPlan()
        assertEquals(listOf(49, 63, 37, 66, 65, 67, 58, 88), plan.operations.map { it.renderId })
        val providerRoles = listOf("vocals-lead", "bass", "kick", "acoustic-guitar", "violin", "flute", "organ", "synth")
        plan.operations.forEachIndexed { operationIndex, operation ->
            val files = operation.expectedRoles.map { role ->
                MvsepRemoteFile(role, "https://example.test/job-$operationIndex/$role.flac", null, 2)
            }
            val expected = mapOf(plan.selectedSourceIds[operationIndex] to
                files.single { it.label == providerRoles[operationIndex] })
            for (offset in files.indices) {
                val reordered = (files.drop(offset) + files.take(offset)).reversed()
                assertEquals(expected, requireSourceOperationFiles(operation, reordered))
            }
            val invalid = listOf(files.dropLast(1), files + files.first(),
                files.dropLast(1) + files.first(),
                files.mapIndexed { index, file -> if (index == 0) file.copy(label = "wrong-role") else file },
                files.map { it.copy(url = files.first().url) })
            invalid.forEach { response ->
                assertEquals("MVSEP_ROLE_MISMATCH", assertThrows(IllegalStateException::class.java) {
                    requireSourceOperationFiles(operation, response)
                }.message)
            }
        }
    }

    @Test fun submissionOwnsLocalPreparationAndPreservesAuthorityOnEveryFailure() = kotlinx.coroutines.runBlocking {
        for (failedStage in listOf<String?>(null, "materialize", "resources", "encoder")) {
            val observed = mutableListOf<String>()
            var durableAuthority = "CREATED"
            suspend fun stage(name: String) {
                observed += name
                if (name == failedStage) error("blocked-$name")
            }
            val result = runCatching {
                submitPreparedSourceUpload(
                    materialize = { stage("materialize"); "exact-upload" },
                    requireResources = { assertEquals("exact-upload", it); stage("resources") },
                    ensureEncoderReady = { stage("encoder") },
                    submit = { assertEquals("exact-upload", it); observed += "submit"; durableAuthority = "SUBMISSION_STARTED"; "remote" }
                )
            }
            val all = listOf("materialize", "resources", "encoder", "submit")
            assertEquals(if (failedStage == null) all else all.take(all.indexOf(failedStage) + 1), observed)
            assertEquals(if (failedStage == null) "SUBMISSION_STARTED" else "CREATED", durableAuthority)
            assertEquals(failedStage == null, result.isSuccess)
            if (failedStage == null) assertEquals("exact-upload" to "remote", result.getOrThrow())
        }
    }

    @Test fun onlyRequestedRolesBecomeTracksWhileEveryProviderOutputIsChecked() {
        val operation = MvsepSourceOperation("test", 1, emptyMap(), setOf("male", "female"),
            mapOf("male-vocals" to "male"))
        val files = listOf(MvsepRemoteFile("Male", "https://example.test/male.flac", null, 2),
            MvsepRemoteFile("Female", "https://example.test/female.flac", null, 2))
        assertEquals(mapOf("male-vocals" to files.first()), requireSourceOperationFiles(operation, files))
        for (invalid in listOf(files.take(1), files + files.first(),
            files.map { it.copy(url = files.first().url) }, listOf(files.first(), files.last().copy(label = "Other")))) {
            assertTrue(runCatching { requireSourceOperationFiles(operation, invalid) }.isFailure)
        }
    }

    @Test fun conversionValidatesExtentWithoutMeasuringWaveformDisplacement() {
        requireSameSourceTimeline(44_100L, 44_100, 48_000L)
        requireSameSourceTimeline(48_001L, 48_000, 48_000L)
        assertTrue(runCatching { requireSameSourceTimeline(48_100L, 48_000, 48_000L) }.isFailure)
        assertTrue(runCatching { requireSameSourceTimeline(0L, 48_000, 48_000L) }.isFailure)
    }

    @Test fun originalEndpointOnlyRemovesOneProvenResamplerCeilingFrame() {
        assertEquals(1_001L, sourceConversionEndFrame(920, 44_100, 1_001))
        assertEquals(1_979_472L, sourceConversionEndFrame(1_818_640, 44_100, 1_979_472))
        assertNull(sourceConversionEndFrame(44_100, 44_100, 48_000))
        assertNull(sourceConversionEndFrame(1_001, 48_000, 1_001))
        for ((frames, rate) in listOf(918L to 44_100, 924L to 44_100, 1_002L to 48_000)) {
            assertEquals("SOURCE_TIMELINE_MISMATCH", assertThrows(IllegalStateException::class.java) {
                sourceConversionEndFrame(frames, rate, 1_001)
            }.message)
        }
    }

    @Test fun resourceAdmissionHappensBeforeProviderRequest() {
        requireSourcePreparationResources(28_800_000L, 8, 3L * 1024 * 1024 * 1024)
        assertEquals("MVSEP_FREE_DURATION_LIMIT", assertThrows(IllegalStateException::class.java) {
            requireSourcePreparationResources(28_800_001L, 1, Long.MAX_VALUE)
        }.message)
        assertEquals("APP_STORAGE_LOW", assertThrows(IllegalStateException::class.java) {
            requireSourcePreparationResources(28_800_000L, 8, 200L * 1024 * 1024)
        }.message)
        assertEquals("SOURCE_SELECTION_INVALID", assertThrows(IllegalArgumentException::class.java) {
            requireSourcePreparationResources(48_000L, 9, Long.MAX_VALUE)
        }.message)
    }

    @Test fun resourceAdmissionReservesCanonicalOriginalAlongsideResidualAndAllTargets() {
        val seconds = 600L
        val targetBytes = seconds * 196_608L * 8
        val canonicalAndResidualBytes = seconds * 48_000L * 2 * 4 * 2
        val originalUploadAndOverhead = seconds * 64_000L + 9L * 1024 * 1024
        val reserve = 512L * 1024 * 1024
        val required = targetBytes + canonicalAndResidualBytes + originalUploadAndOverhead + reserve
        assertEquals("APP_STORAGE_LOW", assertThrows(IllegalStateException::class.java) {
            requireSourcePreparationResources(seconds * 48_000L, 8, required - 1)
        }.message)
        requireSourcePreparationResources(seconds * 48_000L, 8, required)
    }
}

/** Offline advertised capabilities, admitted through the real planner; no provider calls. */
internal fun eightOperationMergerPlan(): MvsepSourcePlan {
    val ids = listOf("lead-vocals", "bass", "kick", "acoustic-guitar", "violin", "flute", "organ", "synth")
    fun algorithm(renderId: Int, roles: Set<String>, options: Map<String, String> = emptyMap()) =
        MvsepAlgorithm(renderId, "Offline merger fixture", roles.toList(), 1.0, 0,
            MvsepCatalogueMetadata(roles, options.map { (key, value) ->
                MvsepOptionField(key, key, setOf(value))
            }, emptyList()))
    return MvsepSourcePlanner.plan(ids, listOf(
        algorithm(49, setOf("vocals-lead", "vocals-back", "instrum-only", "back-instrum"),
            mapOf("add_opt1" to "6", "add_opt2" to "0")),
        algorithm(63, setOf("vocals", "bass", "drums", "guitar", "piano", "other", "instrum")),
        algorithm(37, setOf("drums", "kick", "snare", "toms", "ride", "hh", "crash", "residual"),
            mapOf("add_opt1" to "7", "add_opt2" to "0")),
        algorithm(66, setOf("acoustic-guitar", "other"), mapOf("add_opt2" to "0")),
        algorithm(65, setOf("violin", "other")),
        algorithm(67, setOf("flute", "other"), mapOf("add_opt1" to "1", "add_opt2" to "0")),
        algorithm(58, setOf("organ", "other"), mapOf("add_opt1" to "3")),
        algorithm(88, setOf("synth", "other"), mapOf("add_opt1" to "0"))
    ))
}
