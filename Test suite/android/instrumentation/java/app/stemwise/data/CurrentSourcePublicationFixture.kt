package app.stemwise.data

import android.content.Context
import java.io.File

/** Current lossless lanes and native-rendered Remaining, reused by provider lifecycle fixtures. */
internal data class CurrentSourcePublicationFixture(
    val original: ByteArray,
    val manifest: CustomSourceManifest,
    val parts: Map<String, ByteArray>
) {
    val mediaFiles: Set<String> get() = setOf(SelectedLibraryContract.ORIGINAL) + parts.keys
    val packageFiles: Set<String> get() = mediaFiles + SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST

    suspend fun publishParts(
        context: Context,
        publisher: CanonicalPackagePublisher,
        external: ExternalLibraryRepository,
        root: RootHandle,
        owner: String
    ) {
        external.reconcilePackage(root, manifest.videoId)
        manifest.lanes.forEach { lane ->
            val file = File(context.cacheDir, lane.fileName).apply { writeBytes(parts.getValue(lane.fileName)) }
            publisher.publishSourcePart(root, manifest.videoId, lane.fileName, file, lane.sha256,
                owner, manifest.sourceSha256, manifest.audibleFrames).getOrThrow()
            check(!file.exists()) { "Verified private lane was not retired" }
        }
        publisher.publishCustomSources(root, manifest.videoId, manifest, owner).getOrThrow()
    }

    companion object {
        fun load(context: Context, videoId: String): CurrentSourcePublicationFixture {
            fun asset(name: String) = context.assets.open("demo_library/DEMOUI00001/$name").use { it.readBytes() }
            val original = asset("original.opus")
            val sourceHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(original).joinToString("") { "%02x".format(it) }
            val manifest = parseCustomSourceManifest(asset("sources.json"), "DEMOUI00001", sourceHash)
                .copy(videoId = videoId)
            return CurrentSourcePublicationFixture(original, manifest,
                manifest.lanes.associate { it.fileName to asset(it.fileName) })
        }
    }
}

/** A current required bundle for tests whose subject is optional processing or storage. */
internal fun seedCompleteOpusMetadata(
    folder: File, videoId: String, original: ByteArray,
    originalFileName: String = SelectedLibraryContract.ORIGINAL
) {
    val incoming = org.json.JSONObject().put("formatVersion", 2).put("kind", "stemwise.song-enrichment")
        .put("source", org.json.JSONObject().put("provider", "youtube").put("videoId", videoId))
        .put("generatedAt", "2026-08-09T12:00:00Z")
        .put("generator", org.json.JSONObject().put("name", app.stemwise.enrichment.ENRICHMENT_GENERATOR_NAME)
            .put("version", app.stemwise.enrichment.ENRICHMENT_GENERATOR_VERSION))
        .put("artwork", org.json.JSONObject().put("file", "cover.png").put("kind", "youtube-thumbnail")
            .put("source", org.json.JSONObject().put("name", "fixture")))
    val cover = android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=", android.util.Base64.DEFAULT)
    fun hash(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
    val identity = app.stemwise.enrichment.InstalledMediaIdentity(hash(original), original.size.toLong())
    val installed = app.stemwise.enrichment.createInstalledEnrichmentDocument(incoming, identity.sha256,
        mapOf(originalFileName to identity), 1L, "f".repeat(64),
        incoming.getJSONObject("artwork"), hash(cover), cover.size)
    File(folder, SelectedLibraryContract.ENRICHMENT).writeText(installed.toString(), Charsets.UTF_8)
    File(folder, SelectedLibraryContract.COVER).writeBytes(cover)
}
