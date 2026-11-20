package app.stemwise.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableAuthorityRecoveryPolicyTest {
    @Test
    fun futureRootCanonicalNeverFallsBackToCurrentBackup() {
        val current = "current".toByteArray()
        val future = "future-with-unknown-fields".toByteArray()
        val before = future.copyOf()

        assertThrows(IllegalStateException::class.java) {
            selectRootCopies(
                mapOf(
                    SelectedLibraryContract.ROOT_MANIFEST to future,
                    SelectedLibraryContract.ROOT_MANIFEST_BACKUP to current
                )
            )
        }
        assertArrayEquals(before, future)
    }

    @Test
    fun rootRepairRequiresARecognizedCurrentEnvelopeAndDurablePeers() {
        val current = "current".toByteArray()
        val corruptCurrent = "current-corrupt".toByteArray()

        assertThrows(IllegalStateException::class.java) {
            selectRootCopies(
                mapOf(
                    SelectedLibraryContract.ROOT_MANIFEST to corruptCurrent,
                    SelectedLibraryContract.ROOT_MANIFEST_BACKUP to current,
                    SelectedLibraryContract.ROOT_MANIFEST_TEMP to current
                )
            )
        }
        assertThrows(IllegalStateException::class.java) {
            selectRootCopies(
                mapOf(
                    SelectedLibraryContract.ROOT_MANIFEST to corruptCurrent,
                    SelectedLibraryContract.ROOT_MANIFEST_BACKUP to current,
                    SelectedLibraryContract.ROOT_MANIFEST_TEMP to current
                ),
                authenticatedLibraryId = LIBRARY_ID
            )
        }
        assertThrows(IllegalStateException::class.java) {
            selectRootCopies(
                mapOf(
                    SelectedLibraryContract.ROOT_MANIFEST to byteArrayOf(0, 1, 2),
                    SelectedLibraryContract.ROOT_MANIFEST_BACKUP to current,
                    SelectedLibraryContract.ROOT_MANIFEST_TEMP to current
                )
            )
        }
    }

    @Test
    fun authenticatedRootLocatorRepairsOnlyAnExactTruncatedSibling() {
        val complete = "current".toByteArray()
        val truncated = complete.copyOf(complete.size / 2)
        val interruptedCreateAndWrite = mapOf(
            SelectedLibraryContract.ROOT_MANIFEST to truncated,
            SelectedLibraryContract.ROOT_MANIFEST_TEMP to complete
        )

        assertThrows(IllegalStateException::class.java) {
            selectRootCopies(interruptedCreateAndWrite)
        }
        assertEquals(
            LIBRARY_ID,
            selectRootCopies(interruptedCreateAndWrite, authenticatedLibraryId = LIBRARY_ID)
        )
        assertThrows(IllegalStateException::class.java) {
            selectRootCopies(
                interruptedCreateAndWrite +
                    (SelectedLibraryContract.ROOT_MANIFEST to "other".toByteArray()),
                authenticatedLibraryId = LIBRARY_ID
            )
        }
        assertThrows(IllegalStateException::class.java) {
            selectRootCopies(
                interruptedCreateAndWrite,
                authenticatedLibraryId = "00000000-0000-4000-8000-000000000002"
            )
        }
    }

    @Test
    fun emptyTemporaryOnlyIsAnUncommittedCreate() {
        assertThrows(IllegalStateException::class.java) {
            selectRootCopies(
                mapOf(SelectedLibraryContract.ROOT_MANIFEST_TEMP to byteArrayOf())
            )
        }
        assertEquals(
            LIBRARY_ID,
            selectRootCopies(
                mapOf(SelectedLibraryContract.ROOT_MANIFEST_TEMP to byteArrayOf()),
                authenticatedLibraryId = LIBRARY_ID
            )
        )
        val complete = "current".toByteArray()
        assertThrows(IllegalStateException::class.java) {
            selectRootCopies(
                mapOf(
                    SelectedLibraryContract.ROOT_MANIFEST_TEMP to complete.copyOf(3)
                )
            )
        }
        assertEquals(
            LIBRARY_ID,
            selectRootCopies(
                mapOf(
                    SelectedLibraryContract.ROOT_MANIFEST_TEMP to complete.copyOf(3)
                ),
                authenticatedLibraryId = LIBRARY_ID
            )
        )
    }

    @Test
    fun sharedTornWriteRuleRequiresAuthenticationAndAnExactStrictPrefix() {
        val complete = "authenticated-generation".toByteArray()
        val truncated = complete.copyOf(8)

        assertFalse(
            interruptedReservedAuthorityWriteIsRecoverable(
                truncated,
                listOf(complete),
                authorityAuthenticated = false
            )
        )
        assertTrue(
            interruptedReservedAuthorityWriteIsRecoverable(
                truncated,
                listOf(complete),
                authorityAuthenticated = true
            )
        )
        assertTrue(
            interruptedReservedAuthorityWriteIsRecoverable(
                byteArrayOf(),
                listOf(complete),
                authorityAuthenticated = true
            )
        )
        assertFalse(
            interruptedReservedAuthorityWriteIsRecoverable(
                "different".toByteArray(),
                listOf(complete),
                authorityAuthenticated = true
            )
        )
        assertFalse(
            interruptedReservedAuthorityWriteIsRecoverable(
                complete,
                listOf(complete),
                authorityAuthenticated = true
            )
        )
    }

    @Test
    fun unversionedAuthorityRejectsAnInvalidPeerInsteadOfPromotingOverIt() {
        val current = "current".toByteArray()
        val unknown = "future-with-unknown-fields".toByteArray()
        val before = unknown.copyOf()

        assertThrows(IllegalStateException::class.java) {
            parsePortableAuthorityCopies(
                mapOf("canonical" to unknown, "temporary" to current),
                "AUTHORITY_INVALID"
            ) { bytes ->
                bytes.toString(Charsets.UTF_8).also { check(it == "current") }
            }
        }
        assertArrayEquals(before, unknown)
    }

    @Test
    fun selfIdentifiedCurrentCorruptionIsRecoverableButFutureEnvelopeIsNot() {
        val valid = "v1:valid".toByteArray()
        val corruptCurrent = "v1:corrupt".toByteArray()
        val recovered = parsePortableAuthorityCopies(
            mapOf("canonical" to corruptCurrent, "temporary" to valid),
            "AUTHORITY_INVALID",
            { damaged, peers ->
                damaged.toString(Charsets.UTF_8) == "v1:corrupt" &&
                    peers == listOf("v1:valid")
            }
        ) { bytes ->
            bytes.toString(Charsets.UTF_8).also { check(it == "v1:valid") }
        }
        assertEquals(listOf("v1:valid"), recovered.map { it.second })

        assertThrows(IllegalStateException::class.java) {
            parsePortableAuthorityCopies(
                mapOf("canonical" to "v2:future".toByteArray(), "temporary" to valid),
                "AUTHORITY_INVALID",
                { damaged, _ -> damaged.toString(Charsets.UTF_8).startsWith("v1:") }
            ) { bytes ->
                bytes.toString(Charsets.UTF_8).also { check(it == "v1:valid") }
            }
        }
    }

    private fun selectRootCopies(
        copies: Map<String, ByteArray>,
        authenticatedLibraryId: String? = null
    ): String? =
        selectPortableLibraryIdCopies(
            copies,
            parse = { bytes ->
                bytes.toString(Charsets.UTF_8).also { check(it == "current") }
                LIBRARY_ID
            },
            authenticatedGeneration = authenticatedLibraryId?.let {
                "current".toByteArray() to it
            }
        )

    private companion object {
        const val LIBRARY_ID = "9e720909-1ce8-4f22-a02c-cf5da87041de"
    }
}
