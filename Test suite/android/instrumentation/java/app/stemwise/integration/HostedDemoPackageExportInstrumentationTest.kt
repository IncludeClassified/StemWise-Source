package app.stemwise.integration

import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.StemwiseApplication
import app.stemwise.data.LibraryDatabase
import app.stemwise.data.SelectedLibraryContract
import app.stemwise.data.parseCustomSourceManifest
import app.stemwise.data.readBoundedProviderDocument
import app.stemwise.demo.UiDemoLibraryDocumentsProvider
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/** Explicit post-completion byte export; never submits, retries or substitutes source audio. */
class HostedDemoPackageExportInstrumentationTest {
    @Test
    fun exportCompletedUiDemoPackages(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("class") == "${this@HostedDemoPackageExportInstrumentationTest.javaClass.name}#exportCompletedUiDemoPackages" &&
            arguments.getString("stemwiseHostedDemoExport") == "hosted-demo-completed") { "EXPLICIT_DEMO_EXPORT_REQUIRED" }
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as StemwiseApplication
        withTimeout(30_000L) {
            application.workerInitialization.await()
            application.services.coldOpenCoordinator.awaitCompletion()
        }
        val roots = application.services.libraryRootManager
        val root = checkNotNull(roots.captureHandle())
        check(root.treeUri == UiDemoLibraryDocumentsProvider.treeUri) { "EXACT_DEMO_ROOT_REQUIRED" }
        check(application.services.coldOpenCoordinator.isProducerAdmissionReady(root)) { "DEMO_ROOT_NOT_READY" }
        check(LibraryDatabase.get(context).libraryDao().automaticPreparationOwnerJobs().isEmpty()) { "PREPARATION_STILL_ACTIVE" }
        val authorities = listOf("DEMOUI00001", "DEMOUI00002", "DEMOUI00003", "DEMOUI00004").associateWith { id ->
            checkNotNull(readPortableSourceJobAuthority(roots, root, id)).also {
                check(it.state == "COMPLETE" && it.remoteJobIds.all { remote -> !remote.isNullOrBlank() }) { "HOSTED_DEMO_INCOMPLETE" }
            }
        }
        val external = checkNotNull(context.getExternalFilesDir(null)).canonicalFile
        val directory = File(external, "hosted-demo-completed").canonicalFile
        check(directory.parentFile == external && !directory.exists() && directory.mkdir()) { "EXPORT_DIRECTORY_UNAVAILABLE" }
        var complete = false
        try {
            for ((id, authority) in authorities) {
                val snapshot = checkNotNull(roots.checkedAuthoritativeSongSnapshot(root, id, requireDurableIdentity = true))
                val names = SOURCE_JOB_AUTHORITY_FILES + setOf(SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST,
                    SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST_TEMP, SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST_BACKUP)
                val sidecars = roots.readExactSongSidecars(root, id, names, PORTABLE_SOURCE_JOB_MAX_BYTES)
                val manifest = parseCustomSourceManifest(checkNotNull(sidecars[SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST]), id, authority.sourceSha256)
                check(manifest.planSha256 == authority.planSha256 && manifest.selectedSourceIds == authority.plan.selectedSourceIds &&
                    manifest.lanes.associateBy { it.sourceId } == authority.assets) { "EXPORT_PACKAGE_BINDING_CHANGED" }
                val destination = File(directory, id)
                check(destination.mkdir())
                sidecars.forEach { (name, bytes) ->
                    val output = File(destination, name)
                    output.writeBytes(bytes)
                    check(output.readBytes().contentEquals(bytes)) { "EXPORT_READBACK_FAILED" }
                }
                val original = checkNotNull(snapshot.original)
                val media = listOf(Triple(original.displayName, authority.sourceSha256, checkNotNull(original.sizeBytes))) +
                    manifest.lanes.map { Triple(it.fileName, it.sha256, it.byteLength) }
                manifest.lanes.forEach { requirePortableSourceTimingBinding(authority, it) }
                for ((name, expectedHash, expectedBytes) in media) {
                    val document = checkNotNull(snapshot.file(name))
                    val bytes = readBoundedProviderDocument(context, document.uri, document.sizeBytes, expectedBytes)
                    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
                    check(bytes.size.toLong() == expectedBytes && hash == expectedHash) { "EXPORT_AUDIO_IDENTITY_CHANGED" }
                    val output = File(destination, name)
                    output.writeBytes(bytes)
                    check(output.readBytes().contentEquals(bytes)) { "EXPORT_READBACK_FAILED" }
                }
                fun record(file: File): JSONObject {
                    val digest = MessageDigest.getInstance("SHA-256")
                    file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                    }
                    return JSONObject().put("path", file.relativeTo(destination).invariantSeparatorsPath)
                        .put("sha256", digest.digest().toHexString()).put("byteLength", file.length())
                }
                val frames = manifest.audibleFrames
                val originalFile = File(destination, original.displayName)
                val originalPcm = File(destination, "original48.f32")
                withTimeout(120_000L) {
                    prepareNativeOriginal48kPcm(context, originalFile, originalPcm, frames, Math.multiplyExact(frames, 8L))
                }
                val targets = JSONArray()
                var remaining: JSONObject? = null
                manifest.lanes.forEachIndexed { index, lane ->
                    val encoded = File(destination, lane.fileName)
                    val output = File(destination, if (lane.sourceId == "remaining") "remaining.f32" else "target-${index + 1}.f32")
                    val profile = MergerOracleFixture.captureDecoded(context, encoded, output, frames, 48_000, 2,
                        directFloat = lane.sourceId == "remaining")
                    val pcm = record(output).also { receipt -> profile.keys().forEach { key -> receipt.put(key, profile.get(key)) } }
                    if (lane.sourceId == "remaining") {
                        check(profile.getString("decoderPcmEncoding") == "PCM_FLOAT") { "EXPORT_REMAINING_FLOAT_REQUIRED" }
                        remaining = pcm.put("encoded", record(encoded))
                    } else targets.put(pcm.put("role", lane.sourceId).put("prepared", record(encoded)))
                }
                val capture = JSONObject().put("schemaVersion", 1).put("sampleRate", 48_000).put("channels", 2)
                    .put("frames", frames).put("source", record(originalFile))
                    .put("original", record(originalPcm).put("sampleRate", 48_000).put("channels", 2)
                        .put("frames", frames).put("encoding", "f32le").put("originFrame", 0)
                        .put("conversion", "prepareNativeOriginal48kPcm"))
                    .put("targets", targets).put("remaining", checkNotNull(remaining))
                    .put("limitations", JSONArray(listOf(
                        "Original PCM is a post-completion re-decode through the production Original48 lane.",
                        "Targets and Remaining are decoded from the actual published encoded bytes; Remaining was not re-rendered.",
                        "Converted-away raw provider audio is unavailable; this capture cannot establish its waveform timing.")))
                    .toString().toByteArray(Charsets.UTF_8)
                val captureFile = File(destination, "capture.json")
                captureFile.writeBytes(capture)
                check(captureFile.readBytes().contentEquals(capture)) { "EXPORT_CAPTURE_READBACK_FAILED" }
                val after = roots.readExactSongSidecars(root, id, names, PORTABLE_SOURCE_JOB_MAX_BYTES)
                check(after.keys == sidecars.keys && sidecars.all { (name, bytes) -> bytes.contentEquals(after.getValue(name)) } &&
                    authority.toBytes().contentEquals(checkNotNull(readPortableSourceJobAuthority(roots, root, id)).toBytes())) { "EXPORT_PACKAGE_CHANGED" }
            }
            roots.requireSameLibrary(root)
            complete = true
            android.util.Log.i("HostedDemoPackageExport", directory.absolutePath)
        } catch (error: app.stemwise.playback.engine.WorkerRetirementException) {
            app.stemwise.integration.WorkerRetirementQuarantine.retain(error, files = listOf(directory))
            throw error
        } finally {
            if (!complete && app.stemwise.integration.WorkerRetirementQuarantine.failureFor(directory) == null) {
                check(directory.canonicalFile.parentFile == external)
                check(directory.deleteAfterWorkerRetirement()) { "EXPORT_CLEANUP_FAILED: ${directory.absolutePath}" }
            }
        }
    }
}
