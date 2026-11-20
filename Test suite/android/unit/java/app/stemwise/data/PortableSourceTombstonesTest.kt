package app.stemwise.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PortableSourceTombstonesTest {
    private val library = "current-library"
    private fun row(index: Int) = SourceBindingEntity(library,
        "youtube-video:${index.toString().padStart(11, '0')}", index.toString().padStart(11, '0'),
        "DELETED", "delete-$index", index + 1L)

    @Test fun retainedDeletionCountCanExceedTheLiveLibraryCapacityAndRoundTripAtItsOwnLimit() {
        for (count in listOf(8_193, 10_240, 16_384)) {
            val rows = (0 until count).map(::row)
            val bytes = encodePortableBlockedAuthority(library, 7L, rows)
            assertTrue(bytes.size <= MAX_PORTABLE_SOURCE_BINDING_BYTES)
            val restored = decodePortableBlockedAuthority(library, JSONObject(bytes.toString(Charsets.UTF_8)))
            assertEquals(7L, restored.revision)
            assertEquals(rows.map { it.sourceId }.toSet(), restored.bindings.map { it.sourceId }.toSet())
            assertTrue(restored.bindings.all { it.libraryId == library && it.state == "DELETED" })
        }
    }

    @Test fun overCapacityAndCrossLibraryWritesFailBeforeProducingAuthority() {
        assertEquals("SOURCE_BINDING_TOMBSTONE_CAPACITY_EXCEEDED", runCatching {
            encodePortableBlockedAuthority(library, 1L, (0..16_384).map(::row))
        }.exceptionOrNull()?.message)
        assertThrows(IllegalStateException::class.java) {
            encodePortableBlockedAuthority("other", 1L, listOf(row(1)))
        }
        val bytes = encodePortableBlockedAuthority(library, 1L, listOf(row(1)))
        assertThrows(IllegalStateException::class.java) {
            decodePortableBlockedAuthority("other", JSONObject(bytes.toString(Charsets.UTF_8)))
        }
    }
}
