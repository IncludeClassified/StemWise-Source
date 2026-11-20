package app.stemwise.ui.player.surface

import android.graphics.Canvas
import android.graphics.MaskFilter
import app.stemwise.ui.design.StemwiseNativeGlyphAtlas
import app.stemwise.ui.design.generated.StemwiseIconRole

/** Icons rendered by the native high-frequency player surface. */
internal enum class PlayerSurfaceIcon {
    COLLAPSE,
    INFO,
    SHUFFLE,
    PREVIOUS,
    PLAY,
    PAUSE,
    NEXT,
    REPEAT,
    REPEAT_ONE,
    ORIGINAL,
    STEMS,
    DOWNLOAD,
    STEM_VOCALS,
    STEM_DRUMS,
    STEM_BASS,
    STEM_GUITAR,
    STEM_PIANO,
    STEM_CHOIR,
    STEM_KICK,
    STEM_SNARE,
    STEM_TOMS,
    STEM_CYMBALS,
    STEM_PERCUSSION,
    STEM_BELLS,
    STEM_STRINGS,
    STEM_WOODWIND,
    STEM_BRASS,
    STEM_CROWD,
    STEM_OTHER,
    MUTE,
    SOLO,
    LOOP_AB,
    TUNE,
    LYRICS,
    METRONOME,
    MISSING_ARTWORK
}

internal fun PlayerSurfaceRepeatMode.transportIcon(): PlayerSurfaceIcon =
    if (this == PlayerSurfaceRepeatMode.ONE) PlayerSurfaceIcon.REPEAT_ONE else PlayerSurfaceIcon.REPEAT

internal fun PlayerSurfaceIcon.catalogRole(): StemwiseIconRole = when (this) {
    PlayerSurfaceIcon.COLLAPSE -> StemwiseIconRole.PLAYER_COLLAPSE
    PlayerSurfaceIcon.INFO -> StemwiseIconRole.PLAYER_SONG_INFO
    PlayerSurfaceIcon.SHUFFLE -> StemwiseIconRole.PLAYER_SHUFFLE
    PlayerSurfaceIcon.PREVIOUS -> StemwiseIconRole.PLAYER_PREVIOUS
    PlayerSurfaceIcon.PLAY -> StemwiseIconRole.PLAYER_PLAY
    PlayerSurfaceIcon.PAUSE -> StemwiseIconRole.PLAYER_PAUSE
    PlayerSurfaceIcon.NEXT -> StemwiseIconRole.PLAYER_NEXT
    PlayerSurfaceIcon.REPEAT -> StemwiseIconRole.PLAYER_REPEAT_ALL
    PlayerSurfaceIcon.REPEAT_ONE -> StemwiseIconRole.PLAYER_REPEAT_ONE
    PlayerSurfaceIcon.ORIGINAL -> StemwiseIconRole.PLAYER_SOURCE_ORIGINAL
    PlayerSurfaceIcon.STEMS -> StemwiseIconRole.PLAYER_SOURCE_STEMS
    PlayerSurfaceIcon.DOWNLOAD -> StemwiseIconRole.TASKS_DOWNLOAD
    PlayerSurfaceIcon.STEM_VOCALS -> StemwiseIconRole.PLAYER_STEM_VOCALS
    PlayerSurfaceIcon.STEM_DRUMS -> StemwiseIconRole.PLAYER_STEM_DRUMS
    PlayerSurfaceIcon.STEM_BASS -> StemwiseIconRole.PLAYER_STEM_BASS
    PlayerSurfaceIcon.STEM_GUITAR -> StemwiseIconRole.PLAYER_STEM_GUITAR
    PlayerSurfaceIcon.STEM_PIANO -> StemwiseIconRole.PLAYER_STEM_PIANO
    PlayerSurfaceIcon.STEM_CHOIR -> StemwiseIconRole.PLAYER_STEM_CHOIR
    PlayerSurfaceIcon.STEM_KICK -> StemwiseIconRole.PLAYER_STEM_KICK
    PlayerSurfaceIcon.STEM_SNARE -> StemwiseIconRole.PLAYER_STEM_SNARE
    PlayerSurfaceIcon.STEM_TOMS -> StemwiseIconRole.PLAYER_STEM_TOMS
    PlayerSurfaceIcon.STEM_CYMBALS -> StemwiseIconRole.PLAYER_STEM_CYMBALS
    PlayerSurfaceIcon.STEM_PERCUSSION -> StemwiseIconRole.PLAYER_STEM_PERCUSSION
    PlayerSurfaceIcon.STEM_BELLS -> StemwiseIconRole.PLAYER_STEM_BELLS
    PlayerSurfaceIcon.STEM_STRINGS -> StemwiseIconRole.PLAYER_STEM_STRINGS
    PlayerSurfaceIcon.STEM_WOODWIND -> StemwiseIconRole.PLAYER_STEM_WOODWIND
    PlayerSurfaceIcon.STEM_BRASS -> StemwiseIconRole.PLAYER_STEM_BRASS
    PlayerSurfaceIcon.STEM_CROWD -> StemwiseIconRole.PLAYER_STEM_CROWD
    PlayerSurfaceIcon.STEM_OTHER -> StemwiseIconRole.PLAYER_STEM_OTHER
    PlayerSurfaceIcon.MUTE -> StemwiseIconRole.PLAYER_STEM_MUTE
    PlayerSurfaceIcon.SOLO -> StemwiseIconRole.PLAYER_STEM_SOLO
    PlayerSurfaceIcon.LOOP_AB -> StemwiseIconRole.PLAYER_TOOL_LOOP_AB
    PlayerSurfaceIcon.TUNE -> StemwiseIconRole.PLAYER_STEM_PIANO
    PlayerSurfaceIcon.LYRICS -> StemwiseIconRole.PLAYER_TOOL_LYRICS
    PlayerSurfaceIcon.METRONOME -> StemwiseIconRole.PLAYER_TOOL_METRONOME
    PlayerSurfaceIcon.MISSING_ARTWORK -> StemwiseIconRole.LIBRARY_MISSING_ARTWORK_DISC
}

/**
 * Allocation-free native adapter over the exact same generated role catalog used by Compose.
 * [PlayerSurfaceView] retains sole frame and pointer ownership.
 */
internal class PlayerSurfaceIconAtlas {
    private val glyphs = StemwiseNativeGlyphAtlas()

    fun draw(canvas: Canvas, role: StemwiseIconRole, centerX: Float, centerY: Float, size: Float, color: Int) {
        glyphs.draw(canvas, role, centerX, centerY, size, color)
    }

    fun draw(
        canvas: Canvas,
        icon: PlayerSurfaceIcon,
        centerX: Float,
        centerY: Float,
        size: Float,
        color: Int,
        maskFilter: MaskFilter? = null
    ) {
        glyphs.draw(
            canvas = canvas,
            role = icon.catalogRole(),
            centerX = centerX,
            centerY = centerY,
            size = size,
            color = color,
            maskFilter = maskFilter
        )
    }
}
