package app.stemwise.ui

import app.stemwise.model.StemSourceCatalogue
import app.stemwise.ui.player.nativeplayer.FullPlayerStemRole
import app.stemwise.ui.design.generated.StemwiseGeneratedGlyphCatalog
import org.junit.Assert.*
import org.junit.Test

class SourcePartIconPolicyTest {
    @Test fun everySourceAndRemainingHaveUniqueGeometryAndStableIdentity() {
        val ids = StemSourceCatalogue.sources.map { it.id } + "remaining"
        assertEquals(ids.toSet(), sourcePartIconRoles.keys)
        val glyphs = ids.map { id ->
            val role = sourcePartIconRole(id)
            assertNotEquals("$id has a generic icon", FullPlayerStemRole.OTHER, role)
            StemwiseGeneratedGlyphCatalog.glyph(role.icon)
        }
        assertEquals(ids.size, glyphs.map { it.role }.toSet().size)
        assertEquals(ids.size, glyphs.map { it.layers }.toSet().size)
        StemSourceCatalogue.sources.forEach { source ->
            assertEquals(sourcePartIconRole(source.id), sourcePartIconRole(source.label))
        }
    }

    @Test fun differentInstrumentsAndVocalPartsNeverCollapseToFamilyIcons() {
        assertEquals(FullPlayerStemRole.BASSOON, sourcePartIconRole("bassoon"))
        assertEquals(FullPlayerStemRole.BASS, sourcePartIconRole("bass"))
        assertEquals(FullPlayerStemRole.VIOLIN, sourcePartIconRole("violin"))
        assertEquals(FullPlayerStemRole.TRUMPET, sourcePartIconRole("trumpet"))
        assertEquals(FullPlayerStemRole.HI_HAT, sourcePartIconRole("hi-hat"))
        assertEquals(FullPlayerStemRole.BACKING_VOCALS, sourcePartIconRole("backing-vocals"))
        assertEquals(FullPlayerStemRole.REMAINING, sourcePartIconRole("remaining"))
        assertEquals(FullPlayerStemRole.OTHER, sourcePartIconRole("not-an-instrument"))
    }
}
