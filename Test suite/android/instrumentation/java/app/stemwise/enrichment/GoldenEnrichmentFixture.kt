package app.stemwise.enrichment

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.analysis.ImportedAnalysisRepository
import app.stemwise.data.ExternalLibraryRepository
import app.stemwise.data.CanonicalPackagePublisher
import app.stemwise.data.CanonicalJson
import app.stemwise.data.CustomSourceAsset
import app.stemwise.data.CustomSourceManifest
import app.stemwise.data.ExternalPackageTrash
import app.stemwise.data.NATIVE_ORIGINAL_48K_REMAINING_PRODUCER
import app.stemwise.data.SOURCE_REMAINING_REPREPARE_REQUIRED
import app.stemwise.data.inspectOriginalAudio
import app.stemwise.data.inspectPreparedSourceAudio
import app.stemwise.data.parseCustomSourceManifest
import app.stemwise.data.validateCustomSourceAsset
import app.stemwise.data.validateCustomSourceAudioAsset
import app.stemwise.integration.MvsepSourceOperation
import app.stemwise.integration.MvsepSourcePlan
import app.stemwise.integration.SourceJobTransition
import app.stemwise.integration.YoutubeDlEngine
import app.stemwise.integration.createPortableSourceJobAuthority
import app.stemwise.integration.recordPortableSourceAsset
import app.stemwise.integration.transitionPortableSourceJob
import app.stemwise.data.LibraryDatabase
import app.stemwise.data.LibraryRootException
import app.stemwise.data.LibraryRootManager
import app.stemwise.data.PackageMutationCoordinator
import app.stemwise.data.SelectedLibraryContract
import app.stemwise.data.SongEntity
import app.stemwise.data.SourceBindingRegistry
import app.stemwise.playback.BackgroundWorkCoordinator
import app.stemwise.qa.GoldenQaDocumentsProvider
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Shared current provider, root and private-state lifecycle for focused import families. */
internal abstract class GoldenEnrichmentFixture {
    protected val targetContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    protected val testContext: Context
        get() = InstrumentationRegistry.getInstrumentation().context
    protected var isolatedContext: IsolatedQaContext? = null

    @After
    fun cleanPrivateQaState() {
        val owned = isolatedContext
        isolatedContext = null
        runCatching {
            targetContext.revokeUriPermission(
                targetContext.packageName,
                GoldenQaDocumentsProvider.treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        resetProvider()
        owned?.destroy()
    }

    protected fun seedDirectMedia(
        wrongOriginal: Boolean = false,
        wrongStem: Boolean = false,
        withEnrichment: Boolean = false
    ) {
        val sourceFixture = app.stemwise.data.CurrentSourcePublicationFixture.load(targetContext, VIDEO_ID)
        val imported = decodeAsset("decoder/imported-mp3.mp3.base64")
        val folder = File(providerRoot(), VIDEO_ID).apply { check(mkdir()) }
        File(folder, SelectedLibraryContract.ORIGINAL).writeBytes(if (wrongOriginal) imported else sourceFixture.original)
        sourceFixture.parts.forEach { (name, bytes) ->
            File(folder, name).writeBytes(if (wrongStem && name == "part-1.flac") imported else bytes)
        }
        val manifest = if (wrongStem) sourceFixture.manifest.copy(lanes = sourceFixture.manifest.lanes.map { lane ->
            if (lane.fileName != "part-1.flac") lane else lane.copy(
                sha256 = MessageDigest.getInstance("SHA-256").digest(imported).joinToString("") { "%02x".format(it) },
                byteLength = imported.size.toLong())
        }) else sourceFixture.manifest
        File(folder, SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST).writeBytes(manifest.toBytes())
        if (withEnrichment) {
            val original = File(folder, SelectedLibraryContract.ORIGINAL)
            val incoming = payload("2026-08-09T11:00:00Z", "Complete fixture", includeArtwork = true)
            val coverHash = MessageDigest.getInstance("SHA-256").digest(COVER_PNG).toHexString()
            val identity = InstalledMediaIdentity(sha256(original), original.length())
            val installed = createInstalledEnrichmentDocument(incoming, identity.sha256,
                mapOf(SelectedLibraryContract.ORIGINAL to identity), 1L,
                enrichmentRevisionIdentity(incoming, coverHash), incoming.getJSONObject("artwork"), coverHash, COVER_PNG.size)
            File(folder, SelectedLibraryContract.ENRICHMENT).writeText(installed.toString(), Charsets.UTF_8)
            File(folder, SelectedLibraryContract.COVER).writeBytes(COVER_PNG)
        }
    }

    protected val currentSourcePackageFiles: List<String>
        get() = (setOf("original.opus", "remaining.wv", SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST) +
            (1..8).map { "part-$it.flac" }).toList()

    protected fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    protected fun validInstalledDocument(): JSONObject {
        val media = mapOf(
            SelectedLibraryContract.ORIGINAL to InstalledMediaIdentity(
                "1".padStart(64, '0'),
                1L
            )
        )
        return createInstalledEnrichmentDocument(
            payload("2026-08-09T12:00:00Z", "Corpus title", includeArtwork = true),
            media.getValue(SelectedLibraryContract.ORIGINAL).sha256,
            media,
            1L,
            "f".repeat(64),
            payload(
                "2026-08-09T12:00:00Z",
                "Corpus title",
                includeArtwork = true
            ).getJSONObject("artwork"),
            "a".repeat(64),
            COVER_PNG.size
        )
    }

    protected fun payload(generatedAt: String, title: String, includeArtwork: Boolean): JSONObject =
        JSONObject()
            .put("formatVersion", 2)
            .put("kind", "stemwise.song-enrichment")
            .put("source", JSONObject().put("provider", "youtube").put("videoId", VIDEO_ID))
            .put("generatedAt", generatedAt)
            .put(
                "generator",
                JSONObject()
                    .put("name", ENRICHMENT_GENERATOR_NAME)
                    .put("version", ENRICHMENT_GENERATOR_VERSION)
            )
            .put(
                "metadata",
                JSONObject()
                    .put("matchStatus", "youtube-fallback")
                    .put("title", title)
                    .put("artists", JSONArray().put("StemWise Fixture"))
                    .put(
                        "sources",
                        JSONArray().put(
                            JSONObject()
                                .put("name", "YouTube Music")
                                .put("url", "https://music.youtube.com/watch?v=$VIDEO_ID")
                        )
                    )
            ).apply {
                if (includeArtwork) {
                    put(
                        "artwork",
                        JSONObject()
                            .put("file", "cover.png")
                            .put("kind", "youtube-thumbnail")
                            .put(
                                "source",
                                JSONObject()
                                    .put("name", "YouTube Music")
                                    .put("url", "https://music.youtube.com/watch?v=$VIDEO_ID")
                            )
                    )
                    put(
                        "lyrics",
                        JSONObject()
                            .put("status", "unavailable")
                            .put("reason", "No licensed lyrics fixture is required.")
                            .put("tracks", JSONArray())
                    )
                    put("analysis", unavailableAnalysis())
                }
            }

    protected fun unavailableAnalysis(): JSONObject {
        fun unavailable() = JSONObject()
            .put("status", "unavailable")
            .put("reason", "Not required for the direct storage fixture.")
        val toolRuns = JSONArray()
        listOf(
            "beat-this-full",
            "stemwise-rhythm-normalizer",
            "s-key",
            "all-in-one",
            "essentia",
            "stemwise-waveform"
        ).forEach { name ->
            toolRuns.put(
                JSONObject()
                    .put("name", name)
                    .put("status", "skipped")
                    .put("reason", "Not required for the direct storage fixture.")
            )
        }
        return JSONObject()
            .put("status", "unavailable")
            .put("rhythm", unavailable())
            .put("key", unavailable())
            .put("structure", unavailable())
            .put("tuning", unavailable())
            .put("loudness", unavailable())
            .put("waveform", unavailable())
            .put("toolRuns", toolRuns)
    }

    protected fun writeArchive(
        context: Context,
        name: String,
        document: JSONObject,
        cover: ByteArray?,
        original: File? = null
    ): File = File(context.cacheDir, name).also { archive ->
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("song.json").apply { time = 0L })
            zip.write(document.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            cover?.let {
                zip.putNextEntry(ZipEntry("cover.png").apply { time = 0L })
                zip.write(it)
                zip.closeEntry()
            }
            (original ?: SelectedLibraryContract.originalFiles.map { File(providerRoot(), "$VIDEO_ID/$it") }
                .singleOrNull(File::isFile))?.let {
                zip.putNextEntry(ZipEntry(it.name).apply { time = 0L })
                it.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    protected fun songImporter(
        context: Context,
        roots: LibraryRootManager,
        mutations: PackageMutationCoordinator,
        bindings: SourceBindingRegistry,
        background: BackgroundWorkCoordinator,
        database: LibraryDatabase,
        commitCheckpoint: (String) -> Unit = {}
    ): SongEnrichmentImporter {
        val repository = ExternalLibraryRepository(context, roots, database.libraryDao(), bindings, mutations, background)
        val publisher = CanonicalPackagePublisher(context, roots, database.libraryDao(), mutations, bindings, repository, background)
        return SongEnrichmentImporter(context, roots, mutations, bindings, background,
            originalPublisher = { publisher }, commitCheckpoint = commitCheckpoint)
    }

    protected fun decodeAsset(name: String): ByteArray = testContext.assets.open(name)
        .bufferedReader().use { Base64.decode(it.readText(), Base64.DEFAULT) }

    protected fun mediaMime(bytes: ByteArray, suffix: String): String {
        val file = File.createTempFile("connected-codec-", suffix, targetContext.cacheDir)
        return try {
            file.writeBytes(bytes)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                extractor.getTrackFormat(0).getString(android.media.MediaFormat.KEY_MIME).orEmpty()
            } finally {
                extractor.release()
            }
        } finally {
            file.delete()
        }
    }

    protected fun mediaDurationMs(bytes: ByteArray, suffix: String): Long {
        val file = File.createTempFile("connected-duration-", suffix, targetContext.cacheDir)
        return try {
            file.writeBytes(bytes)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                extractor.getTrackFormat(0)
                    .getLong(android.media.MediaFormat.KEY_DURATION)
                    .div(1_000L)
                    .coerceAtLeast(1L)
            } finally {
                extractor.release()
            }
        } finally {
            file.delete()
        }
    }

    protected fun grantProviderTree(treeUri: Uri = GoldenQaDocumentsProvider.treeUri) {
        targetContext.grantUriPermission(
            targetContext.packageName,
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
    }

    protected fun resetProvider() {
        checkNotNull(
            targetContext.contentResolver.call(
                Uri.parse("content://${GoldenQaDocumentsProvider.AUTHORITY}"),
                GoldenQaDocumentsProvider.METHOD_RESET,
                null,
                null
            )
        )
    }

    protected fun setFault(fault: String) {
        providerCall(GoldenQaDocumentsProvider.METHOD_SET_FAULT, fault)
    }

    protected fun providerCall(method: String, arg: String? = null) = checkNotNull(
        targetContext.contentResolver.call(
            Uri.parse("content://" + GoldenQaDocumentsProvider.AUTHORITY),
            method,
            arg,
            null
        )
    )

    protected fun providerRoot() = File(
        targetContext.noBackupFilesDir,
        "golden-enrichment-document-provider"
    )

    protected class IsolatedQaContext(base: Context) : ContextWrapper(base) {
        private val namespace = UUID.randomUUID().toString()
        private val ownedPreferenceNames = linkedSetOf<String>()
        private val root = File(base.cacheDir, "golden-direct-test/$namespace").apply { mkdirs() }
        private val isolatedFiles = File(root, "files").apply { mkdirs() }
        private val isolatedCache = File(root, "cache").apply { mkdirs() }
        private val isolatedNoBackup = File(root, "no-backup").apply { mkdirs() }

        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = isolatedFiles
        override fun getCacheDir(): File = isolatedCache
        override fun getNoBackupFilesDir(): File = isolatedNoBackup
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = "golden-direct-$namespace-$name"
            synchronized(ownedPreferenceNames) { ownedPreferenceNames += isolated }
            return baseContext.getSharedPreferences(isolated, mode)
        }

        fun eraseAllPrivateState() {
            synchronized(ownedPreferenceNames) {
                ownedPreferenceNames.forEach(baseContext::deleteSharedPreferences)
                ownedPreferenceNames.clear()
            }
            listOf(isolatedFiles, isolatedCache, isolatedNoBackup).forEach { directory ->
                directory.listFiles().orEmpty().forEach { child ->
                    check(child.deleteRecursively()) { "Unable to erase ${child.name}" }
                }
            }
        }

        fun destroy() {
            synchronized(ownedPreferenceNames) {
                ownedPreferenceNames.forEach(baseContext::deleteSharedPreferences)
                ownedPreferenceNames.clear()
            }
            check(!root.exists() || root.deleteRecursively())
        }
    }

    protected companion object {
        const val VIDEO_ID = "FIXTURE0001"
        const val SOURCE_ID = "youtube-video:$VIDEO_ID"
        const val CAPACITY_ID = "Capacity001"
        const val NEGATIVE_CORPUS = "schemas/installed-enrichment-v2-negative-corpus.json"
        const val TIMESTAMP_CORPUS = "schemas/installed-enrichment-v2-timestamp-corpus.json"
        val COVER_PNG: ByteArray = Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            Base64.DEFAULT
        )
    }
}
