package app.stemwise.ui

import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.R
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogResourceInstrumentationTest {
    @Test
    fun compiledResourcesKeepCompleteSentencesAndFormatFolderErrors() {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("Your current StemWise Library is still connected. Try another folder.",
            context.getString(R.string.ui_your_current_stemwise_library_is_still_connected, "Try another folder."))
        assertTrue(context.getString(R.string.ui_this_removes_only_the_prepared_song_parts)
            .contains("lyrics, song details, and analysis"))
        val deletion = context.getString(R.string.ui_this_permanently_removes_the_original_all_stems)
        assertTrue(deletion.contains("artwork, lyrics, and saved song data."))
        assertTrue(deletion.contains("Automatic playlist discovery and same-ID copies stay blocked"))
        assertTrue(deletion.endsWith("until you explicitly choose Send to PC in Activity."))
        assertTrue(context.getString(R.string.ui_android_uses_a_notification_while_stemwise_prepares)
            .contains("prepares stems in the background."))
        assertTrue(context.getString(R.string.ui_mvsep_may_already_be_processing_the_first)
            .contains("without submitting another job."))
    }
}
