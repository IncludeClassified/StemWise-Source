package app.stemwise.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceBindingReservationPolicyTest {
    @Test
    fun portableAndRoomDeletionMergePreservesConfirmedDeletionInEveryOrder() {
        val root = "88c5ec12-2fda-4414-8ada-a42311cfe6d4"
        val deleted = SourceBindingEntity(root, "youtube-video:AbCdEf_12-3", "AbCdEf_12-3", "DELETED", "deleted", 10L)
        val historical = deleted.copy(transactionId = "historical", updatedAtEpochMs = 20L)
        for (values in listOf(listOf(deleted, historical), listOf(historical, deleted))) {
            assertEquals(historical, strongestSourceBinding(values))
            assertEquals(listOf(historical), mergePortableBlockedBindings(root, values.take(1), values.drop(1)))
        }
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            strongestSourceBinding(listOf(deleted, deleted.copy(packageId = "ZbCdEf_12-3")))
        }
    }

    @Test
    fun currentBindingTransitionsCannotReviveDeletionOrCrossPackageOwnership() {
        val states = listOf("ACTIVE", "DELETED")
        states.forEach { old -> states.forEach { target ->
            assertEquals(old == target || old == "ACTIVE" && target == "DELETED",
                sourceBindingTransitionAllowed(old, target, true))
            assertFalse(sourceBindingTransitionAllowed(old, target, false))
        } }
        listOf("RESERVED", "MISSING", "RESERVATION_ORPHAN").forEach { state ->
            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                validateSourceBindingEntity(SourceBindingEntity("library", "youtube-video:AbCdEf_12-3",
                    "AbCdEf_12-3", state, "fixture", 1), "library")
            }
        }
    }

    @Test
    fun `deletion transitions preserve exact package ownership`() {
        assertTrue(sourceBindingTransitionAllowed("ACTIVE", "DELETED", samePackage = true))
        assertFalse(sourceBindingTransitionAllowed("DELETED", "ACTIVE", samePackage = true))
        assertFalse(sourceBindingTransitionAllowed("ACTIVE", "DELETED", samePackage = false))
        assertFalse(
            canonicalAssetPublicationAllowed(
                "AbCdEf_12-3",
                setOf("youtube-video:AbCdEf_12-3")
            )
        )
        assertTrue(
            canonicalAssetPublicationAllowed(
                "AbCdEf_12-3",
                setOf("youtube-video:OtherId1234")
            )
        )
    }
}
