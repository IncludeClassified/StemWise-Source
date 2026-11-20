package app.stemwise.playback

import app.stemwise.data.StemAssetEntity
import app.stemwise.model.Song
import app.stemwise.playback.engine.NativeAudioPlaybackSessionState

/** Optional test boundary; production construction installs no overrides or collector gates. */
internal interface PlaybackTestAdapter {
    suspend fun verifySong(song: Song): Song?
    suspend fun verifyStems(songId: String, stems: List<StemAssetEntity>): List<StemAssetEntity>?
    suspend fun beforeAdapterState(snapshot: NativeAudioPlaybackSessionState)
    suspend fun beforeSavedMetronomeRead(song: Song) {}
    fun beforeSongSettingsWrite() {}
}
