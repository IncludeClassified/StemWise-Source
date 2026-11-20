package app.stemwise.integration

import android.content.Context
import app.stemwise.network.AppNetworkAccess
import app.stemwise.network.wifiProcessEnvironment
import app.stemwise.network.UnmeteredNetworkRequiredException
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import com.yausername.youtubedl_common.utils.ZipUtils
import java.io.File
import java.io.InputStream

/** FFmpeg also links Python's bundled libexpat; YoutubeDL.init couples its installation to yt-dlp. */
internal suspend fun initializeBundledPythonLibraries(context: Context) {
    val archive = File(context.applicationInfo.nativeLibraryDir, "libpython.zip.so")
    check(archive.isFile) { "SOURCE_ENCODER_UNAVAILABLE" }
    val runtime = File(context.noBackupFilesDir, "${YoutubeDL.baseName}/packages/python")
    installBundledPythonLibraries(archive, runtime, ZipUtils::unzip)
}

internal suspend fun installBundledPythonLibraries(archive: File, runtime: File, extract: (File, File) -> Unit) =
    bundledToolRuntimeMutex.withLock {
        val version = YtDlpReleaseSecurity.sha256(archive)
        val completed = File(runtime, ".stemwise-content-sha256")
        if (runtime.isDirectory && completed.isFile && completed.readText(Charsets.UTF_8) == version) return@withLock
        check(!runtime.exists() || runtime.deleteRecursively()) { "SOURCE_ENCODER_UNAVAILABLE" }
        check(runtime.mkdirs()) { "SOURCE_ENCODER_UNAVAILABLE" }
        try {
            extract(archive, runtime)
            completed.writeText(version, Charsets.UTF_8)
        } catch (error: Exception) {
            runtime.deleteRecursively()
            throw error
        }
    }

/** One subprocess environment for local encoders, metadata extraction and updater checks. */
internal fun bundledToolProcessEnvironment(context: Context): Map<String, String> {
    bundledToolRuntimeMutex.bind(context.applicationContext.noBackupFilesDir)
    val native = File(context.applicationInfo.nativeLibraryDir)
    val packages = File(context.noBackupFilesDir, "${YoutubeDL.baseName}/packages")
    val pythonHome = File(packages, "python/usr")
    return mapOf(
        "LD_LIBRARY_PATH" to (listOf("python", "ffmpeg", "aria2").map {
            File(packages, "$it/usr/lib").absolutePath
        } + native.absolutePath + listOfNotNull(System.getenv("LD_LIBRARY_PATH")?.takeIf(String::isNotBlank)))
            .joinToString(File.pathSeparator),
        "SSL_CERT_FILE" to File(pythonHome, "etc/tls/cert.pem").absolutePath,
        "PYTHONHOME" to pythonHome.absolutePath,
        "PYTHONNOUSERSITE" to "1",
        "PYTHONDONTWRITEBYTECODE" to "1",
        "HOME" to pythonHome.absolutePath,
        "TMPDIR" to context.cacheDir.absolutePath,
        "PATH" to listOfNotNull(System.getenv("PATH")?.takeIf(String::isNotBlank), native.absolutePath)
            .joinToString(File.pathSeparator)
    )
}

/** The bundled wrapper exposes no per-call environment, so own only its execution adapter. */
internal suspend fun executeBoundYtDlp(
    context: Context,
    networkAccess: AppNetworkAccess,
    request: YoutubeDLRequest,
    cancellation: ExternalOperationCancellation
): YoutubeDLResponse {
    requireMetadataOnlyYtDlpRequest(request.buildCommand())
    request.addOption("--skip-download")
    val native = File(context.applicationInfo.nativeLibraryDir)
    val packages = File(context.noBackupFilesDir, "${YoutubeDL.baseName}/packages")
    val pythonHome = File(packages, "python/usr")
    val certificate = File(pythonHome, "etc/tls/cert.pem")
    val python = File(native, "libpython.so")
    val ytdlp = File(context.noBackupFilesDir, "${YoutubeDL.baseName}/${YoutubeDL.ytdlpDirName}/${YoutubeDL.ytdlpBin}")
    check(python.isFile && ytdlp.isFile && pythonHome.isDirectory && certificate.isFile) {
        "YT_DLP_SMOKE_ENVIRONMENT_INVALID"
    }
    request.addOption("--no-cache-dir")
    request.addOption("--js-runtimes", "quickjs:${File(native, "libqjs.so").absolutePath}")
    request.addOption("--ffmpeg-location", File(native, "libffmpeg.so").absolutePath)
    val command = listOf(python.absolutePath, ytdlp.absolutePath) + request.buildCommand()
    val environment = bundledToolProcessEnvironment(context) + networkAccess.wifiProcessEnvironment(context)
    val started = System.nanoTime()
    val result = runOwnedToolProcess(command, environment, timeoutSeconds = 120L,
        maximumOutputBytes = 64L * 1024L * 1024L, cancellation = cancellation)
    cancellation.requireActive()
    if (result.exitCode == 125 && "WIFI_BINDING_UNAVAILABLE" in result.stderr) {
        throw UnmeteredNetworkRequiredException(operationStarted = true)
    }
    if (result.exitCode != 0) {
        throw YoutubeDLException(result.stderr.ifBlank { "yt-dlp failed with status ${result.exitCode}" })
    }
    return YoutubeDLResponse(command, result.exitCode, (System.nanoTime() - started) / 1_000_000L,
        result.stdout, result.stderr)
}

/** Byte counting happens before decoding, including a single unterminated JSON line. */
internal fun readBoundedYtDlpOutput(input: InputStream, target: java.io.ByteArrayOutputStream,
    maximumBytes: Long, tailBytes: Int? = null) {
    require(tailBytes == null || tailBytes > 0)
    var total = 0L
    val buffer = ByteArray(8192)
    val tail = tailBytes?.let(::ByteArray)
    var cursor = 0
    var retained = 0
    try {
        input.use {
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                total = Math.addExact(total, count.toLong())
                check(total <= maximumBytes) { "YT_DLP_METADATA_OUTPUT_LIMIT" }
                if (tail == null) target.write(buffer, 0, count)
                else {
                    var offset = 0
                    while (offset < count) {
                        val copied = minOf(count - offset, tail.size - cursor)
                        buffer.copyInto(tail, cursor, offset, offset + copied)
                        offset += copied
                        cursor = (cursor + copied) % tail.size
                        retained = minOf(tail.size, retained + copied)
                    }
                }
            }
        }
    } finally {
        if (tail != null) {
            val start = if (retained == tail.size) cursor else 0
            val first = minOf(retained, tail.size - start)
            target.write(tail, start, first)
            target.write(tail, 0, retained - first)
        }
    }
}
