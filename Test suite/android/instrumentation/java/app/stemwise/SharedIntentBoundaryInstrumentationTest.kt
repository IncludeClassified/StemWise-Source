package app.stemwise

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.text.SpannableString
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.stemwise.work.AppTaskWorkGate
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedIntentBoundaryInstrumentationTest {
    @Test
    fun manualPlaylistSyncRejectsWhileLibrarySwitchOwnsTheWorkGate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as StemwiseApplication
        assumeTrue(app.packageName == "app.stemwise.development")
        runBlocking { withTimeout(20_000L) { app.workerInitialization.await() } }
        instrumentation.runOnMainSync {
            AppTaskWorkGate.instance.closeForLibrarySwitch()
            try {
                assertFalse(app.services.manualTasks.sync())
            } finally {
                AppTaskWorkGate.instance.openAfterLibrarySwitch()
            }
        }
    }

    @Test
    fun sharedPlaylistTextAcceptsStyledTextAndRejectsWrongExtraTypes() {
        val link = "https://music.youtube.com/playlist?list=PLexample"
        listOf<CharSequence>(link, SpannableString(link)).forEach { text ->
            assertEquals(link, sharedPlaylistText(
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, text)
            ))
        }
        assertNull(sharedPlaylistText(
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, Bundle())
        ))
    }

    @Test
    fun malformedStreamParcelablesAreRejectedWhileContentUrisRemainReadable() {
        assertNull(
            sharedEnrichmentStreams(
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, Bundle())
            )
        )

        val content = Uri.parse("content://provider/enrichment.zip")
        val mixed = arrayListOf<Parcelable>(content, Bundle())
        assertNull(
            sharedEnrichmentStreams(
                Intent(Intent.ACTION_SEND_MULTIPLE)
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, mixed)
            )
        )
        assertEquals(
            listOf(content),
            sharedEnrichmentStreams(
                Intent(Intent.ACTION_SEND)
                    .putExtra(Intent.EXTRA_STREAM, content)
                    .apply { clipData = ClipData.newRawUri("enrichment", content) }
            )
        )
    }
}
