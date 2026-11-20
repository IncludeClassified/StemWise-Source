package app.stemwise.playback

import app.stemwise.integration.deleteAfterWorkerRetirement

import android.content.Context
import android.provider.DocumentsContract
import android.net.Uri
import app.stemwise.StemwiseApplication
import app.stemwise.qa.GoldenQaDocumentsProvider
import app.stemwise.data.StemAssetEntity
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Publishes synthetic playback bytes through the disposable target's SAF provider.
 *
 * The package-verification boundary is replaced only for explicitly registered synthetic URIs;
 * provider reads, asynchronous facade ownership, decoding and native playback remain real.
 */
object PlaybackContentFixture {
    private const val FILE_PREFIX = "playback-fixture-"
    private val publishedByDigest = linkedMapOf<String, String>()
    private val registeredSources = linkedSetOf<String>()
    private val ownedCacheEntries = linkedSetOf<File>()

    fun stem(context: Context, id: String, file: File, durationMs: Long,
        setId: String): StemAssetEntity {
        val format = if (file.extension == "wv") app.stemwise.data.inspectPreparedSourceAudio(file).let {
            Triple("wavpack", it.sampleRate, it.channels)
        } else RandomAccessFile(file, "r").use { input ->
            check(input.readInt() == 0x52494646)
            input.seek(8L)
            check(input.readInt() == 0x57415645 && input.readInt() == 0x666d7420)
            check(Integer.reverseBytes(input.readInt()) >= 16)
            val encoding = java.lang.Short.reverseBytes(input.readShort()).toInt()
            val channels = java.lang.Short.reverseBytes(input.readShort()).toInt()
            val rate = Integer.reverseBytes(input.readInt())
            input.seek(34L)
            val bits = java.lang.Short.reverseBytes(input.readShort()).toInt()
            Triple(when {
                encoding == 1 && bits == 16 -> "pcm_s16le"
                else -> error("Unsupported synthetic WAV encoding")
            }, rate, channels)
        }
        return StemAssetEntity(id = id, separationSetId = setId, providerLabel = id,
            normalizedCapability = id, localPath = publish(context, file), sha256 = sourceHash(file),
            durationMs = durationMs, codec = format.first, sampleRateHz = format.second,
            channelCount = format.third, validated = true)
    }

    fun sourceHash(source: File): String = source.inputStream().use(::digest)

    fun sourceHash(context: Context, location: String): String =
        checkNotNull(context.contentResolver.openInputStream(Uri.parse(location))).use(::digest)

    private fun digest(input: java.io.InputStream): String {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var bytes = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
            bytes += count
        }
        check(bytes > 0L) { "Synthetic playback source is empty" }
        return hash.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    /** Register the cache child before writing so failed/partial fixture generation is owned too. */
    @Synchronized
    fun own(context: Context, file: File) {
        val root = context.cacheDir.canonicalFile
        val path = file.canonicalFile
        app.stemwise.integration.WorkerRetirementQuarantine.requireReleased(path)
        require(path != root && path.toPath().startsWith(root.toPath()))
        var entry = path
        while (entry.parentFile != root) entry = requireNotNull(entry.parentFile)
        ownedCacheEntries += entry
    }

    /** Current Remaining decoding requires lossless 48 kHz stereo Float32 WavPack. */
    fun writeRemainingTone(
        context: Context, file: File, durationMs: Long, frequency: Double, amplitude: Double
    ): File {
        require(durationMs > 0L && frequency.isFinite() && frequency >= 0.0)
        require(amplitude.isFinite() && amplitude in 0.0..1.0)
        val frames = Math.toIntExact(Math.multiplyExact(durationMs, 48L))
        return writeFloat32WavPack(context, file, frames) {
            if (frequency != 0.0) repeat(frames) { frame ->
                val sample = (kotlin.math.sin(2.0 * Math.PI * frequency * frame / 48_000) * amplitude).toFloat()
                val bits = java.lang.Float.floatToRawIntBits(sample)
                repeat(2) { writeInt(Integer.reverseBytes(bits)) }
            }
        }
    }

    fun writeFloat32WavPack(context: Context, file: File, frames: Int,
        writeSamples: DataOutputStream.() -> Unit): File {
        require(frames > 0 && file.extension == "wv")
        own(context, file)
        val raw = File.createTempFile("remaining-pcm-", ".f32le", file.parentFile)
        try {
            DataOutputStream(raw.outputStream().buffered()).use { it.writeSamples() }
            // Sparse zero input keeps long silence fixtures cheap without changing their extent.
            if (raw.length() == 0L) RandomAccessFile(raw, "rw").use { it.setLength(frames * 8L) }
            check(raw.length() == frames * 8L)
            kotlinx.coroutines.runBlocking {
                app.stemwise.integration.YoutubeDlEngine(context,
                    (context.applicationContext as StemwiseApplication).networkAccess, null).ensureSourceEncodersReady()
                val result = app.stemwise.integration.runOwnedToolProcess(listOf(
                    File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").absolutePath,
                    "-v", "error", "-y", "-f", "f32le", "-ar", "48000", "-ac", "2",
                    "-i", raw.absolutePath, "-c:a", "wavpack", "-sample_fmt", "fltp",
                    "-compression_level", "0", "-map_metadata", "-1", "-fflags", "+bitexact",
                    "-flags:a", "+bitexact", "-f", "wv", file.absolutePath),
                    app.stemwise.integration.bundledToolProcessEnvironment(context),
                    timeoutSeconds = 120L, maximumOutputBytes = 256L * 1024L)
                check(result.exitCode == 0) { "Remaining fixture encode failed: ${result.stderr.takeLast(2048)}" }
            }
            check(app.stemwise.data.inspectPreparedSourceAudio(file).audibleFrames == frames.toLong())
        } catch (error: app.stemwise.playback.engine.WorkerRetirementException) {
            app.stemwise.integration.WorkerRetirementQuarantine.retain(error, files = listOf(raw, file))
            throw error
        } finally {
            if (app.stemwise.integration.WorkerRetirementQuarantine.failureFor(raw) == null) check(raw.delete())
        }
        return file
    }

    @Synchronized
    fun publish(context: Context, source: File): String {
        requireDisposablePlaybackTarget()
        require(source.isFile && source.length() > 0L) {
            "Synthetic playback source is unavailable: ${source.name}"
        }
        val digest = sourceHash(source)
        own(context, source)
        val extension = source.extension.lowercase().takeIf(String::isNotBlank) ?: "bin"
        val name = "$FILE_PREFIX$digest.$extension"
        val target = File(providerRoot(context), name)
        // A provider reset can retire a published URI while this process stays alive.
        if (!target.isFile || target.length() != source.length()) {
            source.copyTo(target, overwrite = true)
        }
        return register(context, publishedByDigest.getOrPut("$digest.$extension") {
            DocumentsContract.buildDocumentUri(
                GoldenQaDocumentsProvider.AUTHORITY,
                "root/$name"
            ).toString()
        })
    }

    /** Admit one test-owned provider URI without pretending it is a canonical library package. */
    @Synchronized
    fun register(context: Context, sourcePath: String): String {
        requireDisposablePlaybackTarget()
        require(Uri.parse(sourcePath).scheme == "content")
        registeredSources += sourcePath
        val application = context.applicationContext as StemwiseApplication
        val playback = application.services.audioPlayback
        if (playback.debug.verifyPlaybackSong == null) {
            playback.debug.verifyPlaybackSong = { song ->
                val registered = synchronized(this) { song.sourcePath in registeredSources }
                if (!registered) {
                    application.services.playlistRepository.verifyPlaybackSong(song.id)
                } else {
                    require(song.durationMs > 0L)
                    val hash = sourceHash(application, requireNotNull(song.sourcePath))
                    check(song.sourceHash == null || song.sourceHash == hash) { "Synthetic Original identity differs" }
                    song.copy(sourceHash = hash)
                }
            }
        }
        if (playback.debug.verifyPlaybackStems == null) {
            playback.debug.verifyPlaybackStems = { songId, stems ->
                val registered = synchronized(this) { stems.isNotEmpty() && stems.all { it.localPath in registeredSources } }
                if (!registered) {
                    application.services.separationRepository.verifyPlaybackStems(songId, stems)
                } else {
                    stems.forEach { stem ->
                        require(stem.validated && stem.sha256.matches(Regex("[0-9a-f]{64}")))
                        checkNotNull(application.contentResolver.openInputStream(Uri.parse(stem.localPath))).use {
                            val hash = MessageDigest.getInstance("SHA-256")
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var bytes = 0L
                            while (true) {
                                val count = it.read(buffer)
                                if (count < 0) break
                                hash.update(buffer, 0, count)
                                bytes += count
                            }
                            check(bytes > 0L && hash.digest().joinToString("") { byte -> "%02x".format(byte) } == stem.sha256) {
                                "Synthetic playback part identity differs"
                            }
                        }
                    }
                    stems
                }
            }
        }
        return sourcePath
    }

    @Synchronized
    fun clear(context: Context) {
        val playback = (context.applicationContext as StemwiseApplication).services.audioPlayback
        playback.debug.verifyPlaybackSong = null
        playback.debug.verifyPlaybackStems = null
        registeredSources.clear()
        ownedCacheEntries.forEach(app.stemwise.integration.WorkerRetirementQuarantine::requireReleased)
        val sourceFailures = ownedCacheEntries.filter { it.exists() && !it.deleteAfterWorkerRetirement() }
        ownedCacheEntries.removeAll { !it.exists() }
        val failures = providerRoot(context).listFiles().orEmpty()
            .filter { it.name.startsWith(FILE_PREFIX) }
            .filterNot(File::delete)
        publishedByDigest.clear()
        check(failures.isEmpty() && sourceFailures.isEmpty()) {
            "Unable to delete synthetic playback fixtures: ${(failures + sourceFailures).joinToString { it.name }}"
        }
    }

    private fun providerRoot(context: Context): File = File(
        context.noBackupFilesDir,
        "golden-enrichment-document-provider"
    ).apply {
        check(isDirectory || mkdirs()) { "Unable to create synthetic provider root" }
    }
}
