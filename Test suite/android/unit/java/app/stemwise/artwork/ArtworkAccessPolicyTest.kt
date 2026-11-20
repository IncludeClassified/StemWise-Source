package app.stemwise.artwork

import app.stemwise.network.AppNetworkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkAccessPolicyTest {
    @Test
    fun remoteArtworkCanReadNetworkOnlyOnTheAllowedNetwork() {
        listOf(
            "https://example.com/cover.jpg",
            " HTTP://example.com/cover.jpg "
        ).forEach { source ->
            assertTrue(ArtworkAccessPolicy.isRemoteSource(source))
            assertTrue(ArtworkAccessPolicy.allowRemoteNetworkRead(source, ALLOWED))
            assertFalse(
                ArtworkAccessPolicy.allowRemoteNetworkRead(
                    source,
                    ALLOWED.copy(activeNetworkMetered = true)
                )
            )
            assertFalse(
                ArtworkAccessPolicy.allowRemoteNetworkRead(
                    source,
                    ALLOWED.copy(hasActiveNetwork = false)
                )
            )
        }
    }

    @Test
    fun localArtworkNeverNeedsADataNetwork() {
        val blocked = ALLOWED.copy(
            hasActiveNetwork = false,
            activeNetworkValidated = false,
            activeNetworkMetered = true,
        )
        listOf(
            "content://app.stemwise/cover/1",
            "file:///data/user/0/app.stemwise/cover.png",
            "/storage/emulated/0/Music/cover.png",
            "android.resource://app.stemwise/drawable/cover"
        ).forEach { source ->
            assertFalse(ArtworkAccessPolicy.isRemoteSource(source))
            assertTrue(ArtworkAccessPolicy.allowRemoteNetworkRead(source, blocked))
        }
    }

    @Test
    fun systemMediaReceivesNoRemoteOrPrivateArtworkUri() {
        assertEquals(
            "android.resource://app.stemwise/drawable/cover",
            ArtworkAccessPolicy.publicSessionArtworkUri(
                "android.resource://app.stemwise/drawable/cover"
            )
        )
        listOf(
            "https://example.com/cover.jpg",
            "HTTP://example.com/cover.jpg",
            "content://app.stemwise/cover/1",
            "file:///data/user/0/app.stemwise/cover.png",
            "/storage/emulated/0/Music/cover.png",
            "android.resource:///missing-authority"
        ).forEach { source ->
            assertNull(ArtworkAccessPolicy.publicSessionArtworkUri(source))
        }
    }

    private companion object {
        val ALLOWED = AppNetworkState(
            hasActiveNetwork = true,
            activeNetworkValidated = true,
            activeNetworkMetered = false,
            activeNetworkWifi = true
        )
    }
}
