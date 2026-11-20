package app.stemwise.integration

import android.content.Context
import app.stemwise.suspendResult
import app.stemwise.data.LibraryRootManager
import app.stemwise.data.inspectOriginalAudio
import app.stemwise.network.AppNetworkAccess
import app.stemwise.network.whileUnmetered
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

enum class EngineState { IDLE, STARTING, READY, FAILED }

data class EngineStatus(
    val state: EngineState,
    val error: String? = null
)

class YoutubeDlEngine internal constructor(
    private val context: Context,
    private val networkAccess: AppNetworkAccess,
    roots: LibraryRootManager?
) {

    init { bundledToolRuntimeMutex.bind(context.applicationContext.noBackupFilesDir) }

    private val initializationMutex = Mutex()
    private val extractorMutex = Mutex()
    private val sourceEncoderMutex = Mutex()
    private val processAttempts = ExternalOperationAttemptRegistry()
    private val verifiedUpdater = VerifiedYtDlpUpdater(context, networkAccess, roots)
    @Volatile private var sourceEncodersReady = false
    private val mutableStatus = MutableStateFlow(EngineStatus(EngineState.IDLE))
    val status: StateFlow<EngineStatus> = mutableStatus.asStateFlow()

    suspend fun ensureReady(): Result<Unit> = initializationMutex.withLock {
        if (mutableStatus.value.state == EngineState.READY) return Result.success(Unit)
        mutableStatus.value = EngineStatus(EngineState.STARTING)
        return withContext(Dispatchers.IO) {
            suspendResult {
                bundledToolRuntimeMutex.withLock {
                    val preservation = verifiedUpdater.preserveForPackageInitialization()
                    try {
                        YoutubeDL.init(context.applicationContext)
                        FFmpeg.init(context.applicationContext)
                    } finally {
                        verifiedUpdater.restoreAfterPackageInitialization(preservation)
                    }
                }
                mutableStatus.value = EngineStatus(EngineState.READY)
            }.onFailure { error ->
                mutableStatus.value = EngineStatus(
                    state = EngineState.FAILED,
                    error = error.message ?: error.javaClass.simpleName
                )
            }
        }
    }

    internal suspend fun execute(
        request: YoutubeDLRequest,
        processId: String,
        preparedAttempt: ExternalOperationAttempt? = null
    ): YoutubeDLResponse {
        requireMetadataOnlyYtDlpRequest(request.buildCommand())
        request.addOption("--skip-download")
        val attempt = preparedAttempt ?: prepareProcess(processId)
        return try {
            attempt.cancellation.requireActive()
            extractorMutex.withLock {
                attempt.cancellation.requireActive()
                ensureReady().getOrThrow()
                bundledToolRuntimeMutex.withLock {
                    val admission = verifiedUpdater.admitExecutable()
                    request.addOption("--ignore-config")
                    request.addOption("--no-plugin-dirs")
                    admission.requireCurrentRoot()
                    networkAccess.whileUnmetered {
                        executeBoundYtDlp(context.applicationContext, networkAccess,
                            request, attempt.cancellation)
                    }
                }
            }
        } finally {
            if (preparedAttempt == null) finishProcess(attempt)
        }
    }

    internal fun prepareProcess(ownerId: String): ExternalOperationAttempt =
        processAttempts.prepare(ownerId)

    internal fun finishProcess(attempt: ExternalOperationAttempt) = processAttempts.finish(attempt)

    internal fun cancel(processId: String): Boolean =
        processAttempts.cancel(processId) || YoutubeDL.destroyProcessById(processId)


    /** Best-effort verified stable update; an unsafe/rolled-back current binary still fails closed. */
    suspend fun checkForUpdates(
        probeUrl: String? = null
    ): Result<Unit> = suspendResult {
        ensureReady().getOrThrow()
        extractorMutex.withLock {
            withContext(Dispatchers.IO) {
                bundledToolRuntimeMutex.withLock { verifiedUpdater.checkForUpdate(probeUrl) }
            }
        }
        Unit
    }


    internal suspend fun encodeLosslessPart(input: File, output: File, maxOutputBytes: Long, channels: Int,
                                           originalFrames48k: Long? = null) {
        val endFrame = originalFrames48k?.let { frames ->
            val facts = inspectFlac16(input)
            check(facts.channelCount == channels) { "SOURCE_TIMELINE_MISMATCH" }
            sourceConversionEndFrame(facts.totalSamples, facts.sampleRateHz, frames)
        }
        ensureSourceEncodersReady()
        check(!output.exists() || output.delete()) { "PRIVATE_CLEANUP_FAILED" }
        executeFfmpeg(stemFlacEncodeCommand(ffmpegExecutable(), input, output, maxOutputBytes, channels, endFrame),
            "STEM_FLAC_ENCODE_FAILED")
        check(output.isFile && output.length() > 0L) { "STEM_FLAC_ENCODE_FAILED" }
    }

    internal suspend fun ensureSourceEncodersReady() {
        bundledToolRuntimeMutex.requireHealthy()
        if (sourceEncodersReady) return
        sourceEncoderMutex.withLock {
            if (sourceEncodersReady) return@withLock
            initializationMutex.withLock {
                withContext(Dispatchers.IO) {
                    initializeBundledPythonLibraries(context.applicationContext)
                    bundledToolRuntimeMutex.withLock { FFmpeg.init(context.applicationContext) }
                }
            }
            val output = executeFfmpeg(
                command = listOf(ffmpegExecutable().absolutePath, "-hide_banner", "-encoders"),
                failureCode = "SOURCE_ENCODER_UNAVAILABLE"
            )
            check(listOf("flac", "wavpack").all { encoder ->
                output.lineSequence().any { line -> line.trim().split(Regex("\\s+")).getOrNull(1) == encoder }
            }) {
                "SOURCE_ENCODER_UNAVAILABLE"
            }
            sourceEncodersReady = true
        }
    }

    internal suspend fun renderRemaining(
        original: File, targets: List<File>, output: File, maxOutputBytes: Long,
        originalChannels: Int
    ) {
        ensureSourceEncodersReady()
        val inspectionContext = currentCoroutineContext()
        val facts = inspectOriginalAudio(original) { inspectionContext.ensureActive() }
        check(facts.channels == originalChannels) { "SOURCE_AUDIO_UNSUPPORTED" }
        val outputLimit = remainingOutputByteLimit(facts.audibleFrames48k, maxOutputBytes)
        val canonicalOriginal = File(output.parentFile, CANONICAL_ORIGINAL_PCM_FILE)
        check(canonicalOriginal.canonicalPath !in (listOf(original, output) + targets).map { it.canonicalPath }) {
            "PREPARATION_SCRATCH_OWNERSHIP_INVALID"
        }
        check(canonicalOriginal.createNewFile()) { "PREPARATION_SCRATCH_OWNERSHIP_INVALID" }
        try {
            prepareNativeOriginal48kPcm(context, original, canonicalOriginal, facts.audibleFrames48k,
                maxOutputBytes - outputLimit)
            check(!output.exists() || output.delete()) { "PRIVATE_CLEANUP_FAILED" }
            executeFfmpeg(remainingWavPackEncodeCommand(ffmpegExecutable(), canonicalOriginal, targets,
                output, outputLimit), "REMAINING_RENDER_FAILED")
            check(output.isFile && output.length() > 0L) { "REMAINING_RENDER_FAILED" }
        } catch (error: app.stemwise.playback.engine.WorkerRetirementException) {
            app.stemwise.integration.WorkerRetirementQuarantine.retain(error,
                files = listOf(original, canonicalOriginal, output) + targets)
            throw error
        } finally {
            if (app.stemwise.integration.WorkerRetirementQuarantine.failureFor(canonicalOriginal) == null)
                check(!canonicalOriginal.exists() || canonicalOriginal.delete()) { "PRIVATE_CLEANUP_FAILED" }
        }
    }

    private fun ffmpegExecutable(): File =
        File(context.applicationContext.applicationInfo.nativeLibraryDir, "libffmpeg.so").also {
            check(it.isFile) { "SOURCE_ENCODER_UNAVAILABLE" }
        }

    private suspend fun executeFfmpeg(command: List<String>, failureCode: String): String = bundledToolRuntimeMutex.withLock {
        val applicationContext = context.applicationContext
        val packageRoot = File(applicationContext.noBackupFilesDir, "youtubedl-android/packages")
        val ffmpegLibraries = File(packageRoot, "ffmpeg/usr/lib")
        check(ffmpegLibraries.isDirectory) { "SOURCE_ENCODER_UNAVAILABLE" }
        val environment = bundledToolProcessEnvironment(applicationContext)
        val result = runOwnedToolProcess(command, environment, timeoutSeconds = 600L,
            maximumOutputBytes = Long.MAX_VALUE, stdoutTailBytes = 256 * 1024)
        if (result.exitCode != 0) throw IllegalStateException(failureCode,
            IllegalStateException("Tool exit=${result.exitCode}; stderr=${result.stderr.takeLast(4096)}"))
        result.stdout + result.stderr
    }

}

private val PLAYLIST_METADATA_FLAGS = setOf("--flat-playlist", "--dump-single-json", "--abort-on-error",
    "--no-warnings", "--no-progress")

internal fun playlistMetadataRequest(url: String): YoutubeDLRequest = YoutubeDLRequest(url).apply {
    PLAYLIST_METADATA_FLAGS.forEach { addOption(it) }
}

/** Closed argument contract: metadata extraction cannot enable media or sidecar downloads. */
internal fun requireMetadataOnlyYtDlpRequest(arguments: List<String>) {
    val flags = PLAYLIST_METADATA_FLAGS + setOf("--skip-download", "--ignore-config", "--no-plugin-dirs", "--no-cache-dir")
    val valued = setOf("--js-runtimes", "--ffmpeg-location")
    var url: String? = null
    var index = 0
    while (index < arguments.size) {
        val argument = arguments[index++]
        when {
            argument in flags -> Unit
            argument in valued -> check(index < arguments.size &&
                arguments[index++].isNotBlank()) { "METADATA_REQUEST_REQUIRED" }
            PlaylistLinks.parse(argument)?.canonicalUrl == argument && url == null -> url = argument
            else -> error("METADATA_REQUEST_REQUIRED")
        }
    }
    check(url != null && "--flat-playlist" in arguments && "--dump-single-json" in arguments) {
        "METADATA_REQUEST_REQUIRED"
    }
}

internal const val CANONICAL_ORIGINAL_PCM_FILE = "active-original48.f32"

internal fun remainingOutputByteLimit(audibleFrames: Long, availableBytes: Long): Long {
    require(audibleFrames > 0L)
    val canonicalBytes = Math.multiplyExact(audibleFrames, 8L)
    check(availableBytes > canonicalBytes) { "PREPARATION_SCRATCH_PEAK_EXCEEDED" }
    return availableBytes - canonicalBytes
}

/** Subtract final lossless targets from the exact native playback Original, without clipping. */
internal fun remainingWavPackEncodeCommand(
    executable: File, original: File, targets: List<File>, output: File, maxOutputBytes: Long
): List<String> {
    require(targets.size in 1..app.stemwise.model.StemSourceCatalogue.MAX_TARGETS && maxOutputBytes > 0L)
    require((listOf(original) + targets).map { it.absolutePath }.distinct().size == targets.size + 1)
    require(output.absolutePath !in (listOf(original) + targets).map { it.absolutePath })
    val inputs = listOf(original) + targets
    val formats = inputs.indices.joinToString(";") { index ->
        "[$index:a:0]aformat=sample_fmts=dbl:sample_rates=48000:channel_layouts=stereo[a$index]"
    }
    val left = inputs.indices.joinToString("-") { "val(${it * 2})" }
    val right = inputs.indices.joinToString("-") { "val(${it * 2 + 1})" }
    val filter = formats + ";" + inputs.indices.joinToString("") { "[a$it]" } +
        "amerge=inputs=${inputs.size},aeval='$left|$right':channel_layout=stereo[remaining]"
    return ffmpegAudioCommandPrefix(executable) +
        listOf("-f", "f32le", "-ar", "48000", "-ac", "2", "-i", original.absolutePath) +
        targets.flatMap { listOf("-i", it.absolutePath) } +
        listOf("-filter_complex", filter, "-map", "[remaining]", "-vn", "-sn", "-dn",
            "-c:a", "wavpack", "-sample_fmt", "fltp", "-compression_level", "0",
            "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact",
            "-fs", maxOutputBytes.toString(), "-f", "wv", output.absolutePath)
}

internal fun stemFlacEncodeCommand(
    executable: File, input: File, output: File, maxOutputBytes: Long, channels: Int,
    endFrame48k: Long? = null
): List<String> {
    require(maxOutputBytes > 0 && channels in 1..2 && input.absolutePath != output.absolutePath)
    require(endFrame48k == null || endFrame48k > 0)
    val filters = buildList {
        if (channels == 1) add("pan=stereo|c0=c0|c1=c0")
        if (endFrame48k != null) add("aresample=48000,atrim=end_sample=$endFrame48k")
    }
    val filterOptions = if (filters.isEmpty()) emptyList() else listOf("-af", filters.joinToString(","))
    return ffmpegAudioCommandPrefix(executable) + listOf("-i", input.absolutePath, "-map", "0:a:0") +
        filterOptions + listOf("-vn", "-sn", "-dn", "-ac", "2", "-ar", "48000", "-c:a", "flac",
            "-sample_fmt", "s16", "-compression_level", "5", "-map_metadata", "-1",
            "-fflags", "+bitexact", "-flags:a", "+bitexact", "-fs", maxOutputBytes.toString(),
            "-f", "flac", output.absolutePath)
}

internal fun sourceConversionEndFrame(inputFrames: Long, inputRate: Int, originalFrames48k: Long): Long? {
    requireSameSourceTimeline(inputFrames, inputRate, originalFrames48k)
    val convertedFrames = Math.addExact(Math.multiplyExact(inputFrames, 48_000L), inputRate - 1L) / inputRate
    if (convertedFrames == originalFrames48k) return null
    val expectedInputFrames = Math.addExact(Math.multiplyExact(originalFrames48k, inputRate.toLong()), 47_999L) / 48_000L
    // Only the extra resampler ceiling may exceed the independently owned Original endpoint.
    check(inputRate != 48_000 && inputFrames == expectedInputFrames &&
        convertedFrames == Math.addExact(originalFrames48k, 1L)) { "SOURCE_TIMELINE_MISMATCH" }
    return originalFrames48k
}

private fun ffmpegAudioCommandPrefix(executable: File) = listOf(
    executable.absolutePath, "-hide_banner", "-nostdin", "-y", "-loglevel", "error", "-nostats")
