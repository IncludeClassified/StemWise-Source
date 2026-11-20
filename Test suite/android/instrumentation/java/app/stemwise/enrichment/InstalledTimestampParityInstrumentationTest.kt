package app.stemwise.enrichment

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InstalledTimestampParityInstrumentationTest {
    @Test
    fun packagedGoldenCorpusMatchesTheProductionInstalledValidator() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val corpus = context.assets.open(CORPUS).bufferedReader().use { reader ->
            JSONObject(reader.readText())
        }
        val positive = corpus.getJSONArray("positive")
        repeat(positive.length()) { index ->
            val value = positive.getString(index)
            assertTrue(value, isInstalledUtcTimestamp(value))
        }
        val negative = corpus.getJSONArray("negative")
        repeat(negative.length()) { index ->
            val testCase = negative.getJSONObject(index)
            assertFalse(
                testCase.getString("name"),
                isInstalledUtcTimestamp(testCase.get("value"))
            )
        }
    }

    private companion object {
        const val CORPUS = "schemas/installed-enrichment-v2-timestamp-corpus.json"
    }
}
