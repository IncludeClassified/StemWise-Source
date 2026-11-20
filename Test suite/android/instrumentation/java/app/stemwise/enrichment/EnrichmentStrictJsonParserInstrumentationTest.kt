package app.stemwise.enrichment

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EnrichmentStrictJsonParserInstrumentationTest {
    @Test
    fun inputSchemaVersionIsRejectedAtTheProductionContractBoundary() {
        val parsed = parseStrictManifestJson(
            """{"schemaVersion":3}""".toByteArray(Charsets.UTF_8)
        )

        assertImportFailure("JSON_FIELD_UNKNOWN") {
            validateEnrichmentContract(parsed, hasCover = false)
        }
    }

    @Test
    fun utf8BomIsRejectedByTheProductionReader() {
        val bytes = byteArrayOf(
            0xef.toByte(), 0xbb.toByte(), 0xbf.toByte(),
            '{'.code.toByte(), '}'.code.toByte()
        )

        assertImportFailure("MANIFEST_JSON_INVALID") { parseStrictManifestJson(bytes) }
    }

    @Test
    fun duplicateObjectKeysAreRejectedByTheProductionReader() {
        val bytes = """{"kind":"one","kind":"two"}""".toByteArray(Charsets.UTF_8)

        assertImportFailure("MANIFEST_JSON_INVALID") { parseStrictManifestJson(bytes) }
    }

    @Test
    fun trailingJsonIsRejectedByTheProductionReader() {
        val bytes = "{}{}".toByteArray(Charsets.UTF_8)

        assertImportFailure("MANIFEST_JSON_INVALID") { parseStrictManifestJson(bytes) }
    }

    @Test
    fun malformedUtf8IsRejectedByTheProductionReader() {
        val bytes = byteArrayOf(
            '{'.code.toByte(), '"'.code.toByte(), 'x'.code.toByte(), '"'.code.toByte(),
            ':'.code.toByte(), '"'.code.toByte(), 0xc3.toByte(), '('.code.toByte(),
            '"'.code.toByte(), '}'.code.toByte()
        )

        assertImportFailure("MANIFEST_UTF8_INVALID") { parseStrictManifestJson(bytes) }
    }

    @Test
    fun resourceBudgetsRejectDeepAndExpandedJsonBeforeContractValidation() {
        val deep = "{\"x\":" + "[".repeat(64) + "0" + "]".repeat(64) + "}"
        assertImportFailure("MANIFEST_JSON_INVALID") { parseStrictManifestJson(deep.toByteArray()) }
        val expanded = "{\"x\":[" + "0,".repeat(500_000) + "0]}"
        assertImportFailure("MANIFEST_JSON_INVALID") { parseStrictManifestJson(expanded.toByteArray()) }
        val number = "{\"x\":" + "1".repeat(129) + "}"
        assertImportFailure("MANIFEST_JSON_INVALID") { parseStrictManifestJson(number.toByteArray()) }
    }

    @Test
    fun integralTokensCannotRoundIntoTheCrossRuntimeSafeIntegerDomain() {
        for (value in listOf("9007199254740992", "-9007199254740992", "9223372036854775808")) {
            assertImportFailure("MANIFEST_JSON_INVALID") {
                parseStrictManifestJson("{\"x\":$value}".toByteArray())
            }
        }
        for (value in listOf("9007199254740991", "-9007199254740991")) {
            assertEquals(value.toLong(), parseStrictManifestJson("{\"x\":$value}".toByteArray()).getLong("x"))
        }
    }

    private fun assertImportFailure(expectedCode: String, block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue(failure is EnrichmentImportException)
        assertEquals(expectedCode, (failure as EnrichmentImportException).code)
    }
}
