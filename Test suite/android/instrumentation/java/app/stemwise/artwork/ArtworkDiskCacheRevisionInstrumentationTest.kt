package app.stemwise.artwork

import coil.disk.DiskCache
import java.io.File
import java.util.UUID
import androidx.test.platform.app.InstrumentationRegistry
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

@OptIn(coil.annotation.ExperimentalCoilApi::class)
class ArtworkDiskCacheRevisionInstrumentationTest {
    @Test fun aChangedRevisionCannotReadOldEncodedBytes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "artwork-revision-${UUID.randomUUID()}")
        val cache = DiskCache.Builder().directory(directory).maxSizeBytes(2L * 1024 * 1024).build()
        try {
            val old = requireNotNull(artworkDiskCacheIdentity("https://fixture/image", "old", "song"))
            val current = requireNotNull(artworkDiskCacheIdentity("https://fixture/image", "new", "song"))
            val editor = checkNotNull(cache.openEditor(old.canonicalKey))
            cache.fileSystem.sink(editor.data).buffer().use { it.writeUtf8("old image bytes") }
            editor.commit()
            assertNotEquals(old.canonicalKey, current.canonicalKey)
            assertNull(cache.openSnapshot(current.canonicalKey))
            checkNotNull(cache.openSnapshot(old.canonicalKey)).use { snapshot ->
                assertEquals("old image bytes", cache.fileSystem.source(snapshot.data).buffer().use { it.readUtf8() })
            }
            assertEquals(current.canonicalKey, requireNotNull(artworkDiskCacheIdentity(
                "https://fixture/image", "new", "another view owner")).canonicalKey)
        } finally {
            cache.clear()
            check(directory.deleteRecursively())
        }
    }
}
