package app.stemwise.data

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** RFC 8785 conformance and digest-boundary tests for package publication. */
class CanonicalJsonTest {
    @Test
    fun canonicalizesTheRfc8785PrimitiveAndPropertyOrderingExample() {
        val input =
            """{"numbers":[333333333.33333329,1E30,4.50,2e-3,0.000000000000000000000000001],"string":"\u20ac$\u000F\u000aA'\u0042\u0022\u005c\\\"\/","literals":[null,true,false]}"""
        val expected =
            """{"literals":[null,true,false],"numbers":[333333333.3333333,1e+30,4.5,0.002,1e-27],"string":"€$\u000f\nA'B\"\\\\\"/"}"""

        assertEquals(expected, canonical(input))
    }

    @Test
    fun integerTokensCannotBeRoundedAcrossTheExactBoundary() {
        for (value in listOf(9_007_199_254_740_991L, -9_007_199_254_740_991L)) {
            assertEquals("{\"n\":$value}", canonical("{\"n\":$value}"))
        }
        for (value in listOf(9_007_199_254_740_992L, 9_007_199_254_740_993L,
                -9_007_199_254_740_992L, Long.MAX_VALUE)) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                CanonicalJson.encode(JSONObject().put("n", value))
            }
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                canonical("{\"n\":$value}")
            }
        }
    }

    @Test
    fun sortsPropertyNamesByRawUtf16CodeUnitsRecursively() {
        val input =
            """{"\u20ac":"Euro Sign","\r":"Carriage Return","\ufb33":"Hebrew Letter Dalet With Dagesh","1":"One","\ud83d\ude00":"Emoji: Grinning Face","\u0080":"Control","\u00f6":"Latin Small Letter O With Diaeresis","nested":{"b":1,"a":2}}"""
        val expected =
            """{"\r":"Carriage Return","1":"One","nested":{"a":2,"b":1},"${'\u0080'}":"Control","ö":"Latin Small Letter O With Diaeresis","€":"Euro Sign","😀":"Emoji: Grinning Face","דּ":"Hebrew Letter Dalet With Dagesh"}"""

        assertEquals(expected, canonical(input))
    }

    @Test
    fun preservesArrayOrderWhileCanonicalizingEveryNestedObject() {
        val input = """{"z":[{"b":2,"a":1},3,{"d":4,"c":3}],"a":0}"""
        val expected = """{"a":0,"z":[{"a":1,"b":2},3,{"c":3,"d":4}]}"""

        assertEquals(expected, canonical(input))
        assertEquals(expected, canonical(expected))
    }

    @Test
    fun normalizesNumericBoundariesAndNegativeZero() {
        val vectors = linkedMapOf(
            "-0" to "0",
            "0.000001" to "0.000001",
            "0.0000001" to "1e-7",
            "1e20" to "100000000000000000000",
            "1e21" to "1e+21",
            "5e-324" to "5e-324",
            "1.7976931348623157e308" to "1.7976931348623157e+308"
        )

        vectors.forEach { (raw, expected) ->
            assertEquals(raw, """{"n":$expected}""", canonical("""{"n":$raw}"""))
        }
    }

    @Test
    fun equivalentJsonProducesIdenticalUtf8AndDigest() {
        val first = """{ "b" : [3,2,1], "a" : {"y":2,"x":1} }"""
        val second = """{"a":{"x":1.0,"y":2e0},"b":[3.0,2.0,1.0]}"""
        val firstBytes = CanonicalJson.encode(first)
        val secondBytes = CanonicalJson.encode(second)

        assertArrayEquals(firstBytes, secondBytes)
        assertEquals(sha256(firstBytes), sha256(secondBytes))
        assertEquals(
            sha256(firstBytes),
            CanonicalJson.sha256(JSONObject(first))
        )
    }

    @Test
    fun oneValueMutationChangesCanonicalBytesAndDigest() {
        val before = CanonicalJson.encode("""{"a":1,"b":[true,null]}""")
        val after = CanonicalJson.encode("""{"a":2,"b":[true,null]}""")

        assertNotEquals(before.toString(StandardCharsets.UTF_8), after.toString(StandardCharsets.UTF_8))
        assertNotEquals(sha256(before), sha256(after))
    }

    @Test
    fun rejectsInputsOutsideTheIJsonBoundary() {
        val invalid = listOf(
            """{"duplicate":1,"duplicate":2}""",
            """{"n":NaN}""",
            """{"n":Infinity}""",
            """{"n":-Infinity}""",
            "{\"s\":\"\uDEAD\"}"
        )

        invalid.forEach { raw ->
            val outcome = runCatching {
                CanonicalJson.encode(raw)
            }
            val acceptedHex = outcome.getOrNull()?.joinToString("") { byte ->
                "%02x".format(byte)
            }
            assertTrue(
                "Expected canonicalizer to reject $raw; accepted UTF-8 hex=$acceptedHex",
                outcome.isFailure
            )
        }
    }

    @Test
    fun validatesEscapedAndObjectDerivedUnicodeBeforeCanonicalization() {
        val invalidRaw = listOf(
            """{"s":"\uD800"}""",
            """{"s":"\uDEAD"}"""
        )
        invalidRaw.forEach { raw ->
            assertTrue("Expected escaped surrogate rejection for $raw", runCatching {
                CanonicalJson.encode(raw)
            }.isFailure)
        }

        val invalidObject = JSONObject().put("s", "\uDEAD")
        assertTrue(runCatching { CanonicalJson.encode(invalidObject) }.isFailure)

        val validPair = "\uD83D\uDE00"
        assertEquals(
            """{"s":"$validPair"}""",
            canonical("""{"s":"\uD83D\uDE00"}""")
        )
        assertEquals(
            """{"s":"$validPair"}""",
            CanonicalJson.encode(JSONObject().put("s", validPair))
                .toString(StandardCharsets.UTF_8)
        )
    }

    private fun canonical(raw: String): String =
        CanonicalJson.encode(raw).toString(StandardCharsets.UTF_8)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
