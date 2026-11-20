package app.stemwise.integration

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

sealed interface SharedPlaylistState {
    val offerId: Long?

    data object Idle : SharedPlaylistState {
        override val offerId: Long? = null
    }

    data class Confirm(
        override val offerId: Long,
        val link: PlaylistLink
    ) : SharedPlaylistState

    data class AddSong(
        override val offerId: Long,
        val originalText: String,
        val videoId: String
    ) : SharedPlaylistState

    data class Error(
        override val offerId: Long,
        val message: String,
        val editableText: String = ""
    ) : SharedPlaylistState

    data class Syncing(override val offerId: Long) : SharedPlaylistState

    data class Notice(
        override val offerId: Long,
        val message: String,
        val playlistCommitted: Boolean = false
    ) : SharedPlaylistState
}

internal class PlaylistCommitAuthorization internal constructor(
    private val owner: ShareIntentCoordinator,
    internal val offerId: Long,
    internal val link: PlaylistLink,
    private val nonce: Long
) {
    private val executionConsumed = AtomicBoolean(false)

    internal fun consumeForExecution(): Boolean =
        executionConsumed.compareAndSet(false, true) &&
            owner.isCurrentCommitClaim(offerId, link, nonce)

    internal fun requireCurrent() = owner.requireCurrentCommitClaim(offerId, link, nonce)

    internal fun complete(
        message: String,
        playlistCommitted: Boolean
    ): Boolean = owner.completeCurrentCommitClaim(
        offerId = offerId,
        link = link,
        nonce = nonce,
        message = message,
        playlistCommitted = playlistCommitted
    )
}

class ShareIntentCoordinator {
    private data class CommitClaim(val offerId: Long, val link: PlaylistLink, val nonce: Long)

    private val offerLock = Any()
    private var lastOfferId = 0L
    private var lastCommitNonce = 0L
    private var activeCommitClaim: CommitClaim? = null
    private val mutablePlaylist = MutableStateFlow<SharedPlaylistState>(SharedPlaylistState.Idle)
    val playlist: StateFlow<SharedPlaylistState> = mutablePlaylist.asStateFlow()

    /** A share offers a confirmation only; receiving text never queues remote work. */
    fun offerText(text: String?): Long = publishOffer { offerId -> textOffer(offerId, text) }

    private fun textOffer(offerId: Long, text: String?): SharedPlaylistState {
        val retained = PlaylistLinks.retainForCorrection(text)
        if (app.stemwise.bridge.bridgeHasMultipleLinks(text.orEmpty())) {
            return SharedPlaylistState.Error(offerId, "Send one song link per job. Remove the extra links.", retained)
        }
        PlaylistLinks.parse(retained)?.let { return SharedPlaylistState.Confirm(offerId, it) }
        val candidate = Regex("""https?://[^\s<>"']+""").find(retained)?.value
            ?.trimEnd('.', ',', ')', ']', '}') ?: retained.trim()
        val videoId = runCatching { app.stemwise.bridge.bridgeVideoId(candidate) }.getOrNull()
        return if (videoId != null) SharedPlaylistState.AddSong(offerId, candidate, videoId)
        else SharedPlaylistState.Error(offerId, "Enter a YouTube song or playlist link.", retained)
    }

    fun confirm(link: PlaylistLink): Long = publishOffer { offerId ->
        SharedPlaylistState.Confirm(offerId, link)
    }

    fun error(message: String, editableText: String = ""): Long = publishOffer { offerId ->
        SharedPlaylistState.Error(
            offerId = offerId,
            message = message,
            editableText = PlaylistLinks.retainForCorrection(editableText)
        )
    }

    /**
     * Re-checks only the currently visible offer and always advances the offer generation.
     * A valid edit becomes a fresh song confirmation or read-only playlist preview.
     * This coordinator never mutates a repository.
     */
    fun recheck(offerId: Long, editedText: String): Long? = synchronized(offerLock) {
        val current = mutablePlaylist.value
        if (current !is SharedPlaylistState.Error || current.offerId != offerId) {
            return@synchronized null
        }
        check(lastOfferId < Long.MAX_VALUE) { "Playlist offer generation exhausted" }
        val nextOfferId = ++lastOfferId
        mutablePlaylist.value = textOffer(nextOfferId, editedText)
        nextOfferId
    }

    fun syncing(): Long = publishOffer(SharedPlaylistState::Syncing)

    internal fun claimPlaylistCommit(
        offerId: Long,
        link: PlaylistLink
    ): PlaylistCommitAuthorization? = synchronized(offerLock) {
        val current = mutablePlaylist.value
        if (
            current !is SharedPlaylistState.Confirm ||
            current.offerId != offerId ||
            current.link != link ||
            activeCommitClaim != null
        ) return@synchronized null
        check(lastCommitNonce < Long.MAX_VALUE) { "Playlist commit generation exhausted" }
        val claim = CommitClaim(offerId, link, ++lastCommitNonce)
        activeCommitClaim = claim
        mutablePlaylist.value = SharedPlaylistState.Syncing(offerId)
        PlaylistCommitAuthorization(this, offerId, link, claim.nonce)
    }

    internal fun requireCurrentCommitClaim(
        offerId: Long,
        link: PlaylistLink,
        nonce: Long
    ) = synchronized(offerLock) {
        val state = mutablePlaylist.value
        check(
            state is SharedPlaylistState.Syncing &&
                state.offerId == offerId &&
                activeCommitClaim == CommitClaim(offerId, link, nonce)
        ) { "PLAYLIST_COMMIT_AUTHORIZATION_STALE" }
    }

    internal fun isCurrentCommitClaim(
        offerId: Long,
        link: PlaylistLink,
        nonce: Long
    ): Boolean = synchronized(offerLock) {
        val state = mutablePlaylist.value
        state is SharedPlaylistState.Syncing &&
            state.offerId == offerId &&
            activeCommitClaim == CommitClaim(offerId, link, nonce)
    }

    /** Only the exact still-Syncing claim may publish its terminal result. */
    internal fun completeCurrentCommitClaim(
        offerId: Long,
        link: PlaylistLink,
        nonce: Long,
        message: String,
        playlistCommitted: Boolean
    ): Boolean = synchronized(offerLock) {
        val expected = CommitClaim(offerId, link, nonce)
        val state = mutablePlaylist.value
        if (
            state !is SharedPlaylistState.Syncing ||
            state.offerId != offerId ||
            activeCommitClaim != expected
        ) return@synchronized false
        activeCommitClaim = null
        mutablePlaylist.value = SharedPlaylistState.Notice(
            offerId = offerId,
            message = message,
            playlistCommitted = playlistCommitted
        )
        true
    }

    fun notice(
        offerId: Long,
        message: String,
        playlistCommitted: Boolean = false
    ): Boolean = synchronized(offerLock) {
        if (mutablePlaylist.value.offerId != offerId) return@synchronized false
        activeCommitClaim = null
        mutablePlaylist.value = SharedPlaylistState.Notice(
            offerId = offerId,
            message = message,
            playlistCommitted = playlistCommitted
        )
        true
    }

    fun dismiss(offerId: Long): Boolean = synchronized(offerLock) {
        if (mutablePlaylist.value.offerId != offerId) return@synchronized false
        activeCommitClaim = null
        mutablePlaylist.value = SharedPlaylistState.Idle
        true
    }

    fun isCurrent(offerId: Long): Boolean = synchronized(offerLock) {
        mutablePlaylist.value.offerId == offerId
    }

    private fun publishOffer(
        state: (offerId: Long) -> SharedPlaylistState
    ): Long = synchronized(offerLock) {
        check(lastOfferId < Long.MAX_VALUE) { "Playlist offer generation exhausted" }
        val offerId = ++lastOfferId
        activeCommitClaim = null
        mutablePlaylist.value = state(offerId)
        offerId
    }

}
