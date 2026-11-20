package app.stemwise.data

import app.stemwise.demo.uiDemoLibraryDurableMimeType
import app.stemwise.demo.uiDemoLibraryManifest
import app.stemwise.demo.uiDemoLibrarySourceManifest
import app.stemwise.demo.UiDemoLibraryDocumentsProvider
import app.stemwise.enrichment.installedMediaIdentities
import app.stemwise.enrichment.validateInstalledEnrichmentDocument
import app.stemwise.ui.PlayerStemAvailability
import app.stemwise.ui.playerStemAvailability
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiDemoLibraryContractTest {
    @Test
    fun `complete demo stems are selectable before a runtime package is published`() {
        val assets = listOf("vocals", "remaining").map { role ->
            StemAssetEntity(role, "demo", role, role, "/fixture/$role.flac", "fixture-hash",
                1_000L, "flac", 48_000, 2, true)
        }
        fun availability(parts: List<StemAssetEntity> = assets, loaded: Boolean = true,
            current: Boolean = true, ready: Boolean = true) = playerStemAvailability(parts,
            playbackPackagePresent = false, contentLoaded = loaded, hasUsableSeparationSets = true,
            currentAudio = current, controllerReady = ready)
        assertEquals(PlayerStemAvailability.READY, availability())
        assertEquals(PlayerStemAvailability.PREPARING, availability(loaded = false))
        assertEquals(PlayerStemAvailability.PREPARING, availability(current = false))
        assertEquals(PlayerStemAvailability.PREPARING, availability(ready = false))
        assertEquals(PlayerStemAvailability.RESOLVING, availability(parts = assets.take(1)))
        assertEquals(PlayerStemAvailability.MISSING, playerStemAvailability(emptyList(), false,
            true, false, false, false))
    }

    @Test
    fun `ten logical demo songs expose nine lossless stems from three media templates`() {
        val root = File(projectRoot(), "app/src/development/assets/demo_library")
        val expectedTemplateIds = setOf("DEMOUI00001", "DEMOUI00002", "DEMOUI00003")
        val folders = root.listFiles().orEmpty().filter(File::isDirectory)
        assertEquals(expectedTemplateIds, folders.map(File::getName).toSet())
        assertEquals(10, UiDemoLibraryDocumentsProvider.FIXTURE_IDS.size)
        assertEquals(10, UiDemoLibraryDocumentsProvider.FIXTURE_IDS.toSet().size)
        val titles = linkedSetOf<String>()

        UiDemoLibraryDocumentsProvider.FIXTURE_TEMPLATES.entries.forEachIndexed {
                index, (videoId, templateId) ->
            val folder = File(root, templateId)
            assertEquals(
                setOf("original.opus", "cover.png", "song.json") +
                    (1..8).map { "part-$it.flac" } + "remaining.wv" + "sources.json",
                folder.listFiles().orEmpty().filter(File::isFile).map(File::getName).toSet()
            )
            assertEquals(
                folder.listFiles().orEmpty().filter(File::isFile).map {
                    if (it.name == "sources.json") SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST else it.name
                }.toSet(),
                UiDemoLibraryDocumentsProvider.FIXTURE_FILES.toSet()
            )
            val originalSha256 = sha256(File(folder, SelectedLibraryContract.ORIGINAL))
            val sources = parseCustomSourceManifest(uiDemoLibrarySourceManifest(
                File(folder, "sources.json").readBytes(),
                templateId, videoId
            ), videoId, originalSha256)
            assertEquals(8, sources.selectedSourceIds.size)
            assertEquals(9, sources.lanes.size)
            assertEquals("remaining", sources.lanes.last().sourceId)
            assertEquals(9, sources.lanes.map { it.sha256 }.toSet().size)
            validateCustomSourceManifestFiles(sources, UiDemoLibraryDocumentsProvider.FIXTURE_FILES.toSet())
            sources.lanes.forEach { lane ->
                val media = File(folder, lane.fileName)
                validateCustomSourceAudioAsset(lane, sha256(media), media.length(), inspectPreparedSourceAudio(media))
            }
            val installed = JSONObject(String(uiDemoLibraryManifest(
                template = File(folder, SelectedLibraryContract.ENRICHMENT).readText(),
                templateId = templateId,
                videoId = videoId,
                trackNumber = index + 1
            ), Charsets.UTF_8))
            validateInstalledEnrichmentDocument(installed, videoId, originalSha256)
            titles += installed.getJSONObject("metadata").getString("title")

            installedMediaIdentities(installed).forEach { (name, identity) ->
                val media = File(folder, name)
                assertEquals(media.length(), identity.byteLength)
                assertEquals(sha256(media), identity.sha256)
            }
            val artwork = installed.getJSONObject("artwork")
            val cover = File(folder, SelectedLibraryContract.COVER)
            assertEquals(cover.length(), artwork.getLong("byteLength"))
            assertEquals(sha256(cover), artwork.getString("sha256"))

            assertTrue(installed.getJSONObject("metadata").getJSONArray("artists").length() > 0)
            val lyrics = installed.getJSONObject("lyrics")
            assertEquals("complete", lyrics.getString("status"))
            assertEquals(
                setOf("original", "translation", "transliteration"),
                (0 until lyrics.getJSONArray("tracks").length()).map { index ->
                    lyrics.getJSONArray("tracks").getJSONObject(index).getString("kind")
                }.toSet()
            )
            assertEquals("stemwise.display-words.v2", lyrics.getString("tokenizationVersion"))
            var hasWordTiming = false
            val lyricTracks = lyrics.getJSONArray("tracks")
            for (trackIndex in 0 until lyricTracks.length()) {
                val track = lyricTracks.getJSONObject(trackIndex)
                val lines = track.getJSONArray("lines")
                for (lineIndex in 0 until lines.length()) {
                    val line = lines.getJSONObject(lineIndex)
                    assertTrue(line.getLong("endMs") > line.getLong("startMs"))
                    hasWordTiming = hasWordTiming ||
                        (track.getString("kind") == "original" &&
                            line.optJSONArray("words")?.length()?.let { it > 0 } == true)
                }
            }
            assertTrue(hasWordTiming)
            val analysis = installed.getJSONObject("analysis")
            assertEquals("complete", analysis.getString("status"))
            listOf("rhythm", "key", "structure", "tuning", "loudness", "waveform")
                .forEach { group ->
                    assertEquals("complete", analysis.getJSONObject(group).getString("status"))
                }
            assertEquals(6, analysis.getJSONArray("toolRuns").length())
        }
        assertEquals(10, titles.size)
    }

    @Test
    fun `demo provider preserves semantic MIME through transaction suffixes`() {
        val jsonNames = SelectedLibraryContract.rootAuthorityFiles +
            SelectedLibraryContract.packageFiles.filter { ".json" in it }
        jsonNames.forEach { name ->
            assertEquals("application/json", uiDemoLibraryDurableMimeType(name))
        }
        setOf(
            SelectedLibraryContract.COVER,
            SelectedLibraryContract.ENRICHMENT_COVER_BACKUP,
            SelectedLibraryContract.ENRICHMENT_COVER_REPLACEMENT
        ).forEach { name ->
            assertEquals("image/png", uiDemoLibraryDurableMimeType(name))
        }
        assertEquals(null, uiDemoLibraryDurableMimeType(SelectedLibraryContract.ORIGINAL))
        assertEquals("audio/wavpack", uiDemoLibraryDurableMimeType(SelectedLibraryContract.REMAINING_FILE))
        assertEquals("audio/wavpack", uiDemoLibraryDurableMimeType(SelectedLibraryContract.REMAINING_FILE + ".tmp"))
    }

    @Test
    fun `demo startup provider and media belong only to Development`() {
        val project = projectRoot()
        val manifest = File(project, "app/src/development/AndroidManifest.xml").readText(Charsets.UTF_8)
        val mainManifest = File(project, "app/src/main/AndroidManifest.xml").readText(Charsets.UTF_8)
        val applicationSource = File(project, "app/src/main/java/app/stemwise/MainActivity.kt").readText(Charsets.UTF_8)
        val appSource = File(project, "app/src/main/java/app/stemwise/ui/StemwiseApp.kt").readText(Charsets.UTF_8)
        val providerSource = File(project, "app/src/development/java/app/stemwise/demo/UiDemoLibraryDocumentsProvider.kt").readText(Charsets.UTF_8)
        val startupSource = File(project, "app/src/development/java/app/stemwise/demo/DevelopmentStemwiseApplication.kt").readText(Charsets.UTF_8)
        val provider = manifest.substringAfter(".demo.UiDemoLibraryDocumentsProvider")
            .substringBefore("/>")
        assertTrue("android:exported=\"true\"" in provider)
        assertTrue("android:grantUriPermissions=\"true\"" in provider)
        assertTrue("android:permission=\"android.permission.MANAGE_DOCUMENTS\"" in provider)
        org.junit.Assert.assertFalse(mainManifest.contains(".demo.UiDemoLibraryDocumentsProvider"))
        org.junit.Assert.assertFalse(mainManifest.contains("DevelopmentStemwiseApplication"))
        org.junit.Assert.assertFalse(applicationSource.contains("UiDemoLibraryDocumentsProvider"))
        org.junit.Assert.assertFalse(applicationSource.contains("StemwisePhoneTestViewport"))
        org.junit.Assert.assertFalse(appSource.contains("onUseDemo"))
        assertTrue(providerSource.contains("context.grantUriPermission(context.packageName, treeUri"))
        assertTrue(manifest.contains("android:name=\".demo.DevelopmentStemwiseApplication\""))
        assertTrue(startupSource.contains("services.librarySessionCoordinator.switchTo(candidate).getOrThrow()"))
        assertTrue(startupSource.contains(".validateCandidate(UiDemoLibraryDocumentsProvider.treeUri).getOrThrow()"))
        val recoveredAccount = applicationSource.indexOf("services.mvsepAccount.awaitLocalLoad()")
        val startSession = applicationSource.indexOf("startLibrarySession()", recoveredAccount)
        assertTrue(recoveredAccount >= 0 && startSession > recoveredAccount)
        assertTrue(startupSource.contains("if (!activated) super.startLibrarySession()"))
        listOf("main", "release").forEach { sourceSet ->
            val sourceRoot = File(project, "app/src/$sourceSet")
            org.junit.Assert.assertFalse(File(sourceRoot, "assets/demo_library").exists())
            org.junit.Assert.assertFalse(File(sourceRoot, "java/app/stemwise/demo").exists())
        }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private fun projectRoot(): File {
        val working = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(working) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory }
            ?: error("Could not locate StemWise project from ${working.absolutePath}")
    }
}
