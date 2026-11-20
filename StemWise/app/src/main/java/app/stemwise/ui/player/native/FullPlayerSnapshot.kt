package app.stemwise.ui.player.nativeplayer

import app.stemwise.ui.player.surface.PLAYER_SURFACE_MAX_MIXER_LANES
import app.stemwise.ui.player.surface.PlayerSurfaceMixerPacket
import app.stemwise.ui.player.surface.PlayerSurfaceOwnerKey
import app.stemwise.ui.player.surface.PlayerSurfaceSourceMode
import app.stemwise.ui.player.surface.PlayerSurfaceSourcePacket
import app.stemwise.ui.player.surface.PlayerSurfaceTimelinePacket
import app.stemwise.ui.player.surface.PlayerSurfaceTool
import app.stemwise.ui.player.surface.PlayerSurfaceTransportPacket
import app.stemwise.ui.design.generated.StemwiseIconRole
import java.util.Collections
import kotlin.math.abs

/** Owner-independent visual entry. A maximum-size catalog is reusable across generations. */
internal data class FullPlayerCatalogSong(
    val queueKey: Long,
    val songId: String,
    val mediaRevision: String,
    val title: String,
    val artist: String,
    val artworkSource: String?,
    val accentArgb: Int,
    val isFlac: Boolean = false
) {
    init {
        require(songId.isNotBlank())
        require(title.isNotBlank())
    }
}

/**
 * Stable library catalog. Neither this packet nor its entries carry a player-owner generation.
 * Selecting another song therefore changes only the selection/owner packets, not all entries.
 */
internal class FullPlayerCatalogPacket(
    val revision: Long,
    songs: List<FullPlayerCatalogSong>
) : AbstractList<FullPlayerCatalogSong>() {
    private val entries = Collections.unmodifiableList(songs.toList())
    private val byQueueKey = entries.associateBy(FullPlayerCatalogSong::queueKey)
    private val indexBySongId = entries.withIndex().associate { it.value.songId to it.index }

    override val size: Int get() = entries.size
    override fun get(index: Int): FullPlayerCatalogSong = entries[index]
    fun indexOfSong(id: String): Int? = indexBySongId[id]

    init {
        require(revision >= 0L)
        require(entries.isNotEmpty())
        require(byQueueKey.size == entries.size) { "Catalog queue keys must be unique" }
        require(indexBySongId.size == entries.size) { "Catalog song IDs must be unique" }
    }

    fun songAt(index: Int): FullPlayerCatalogSong = entries[index]

    fun songForQueueKey(queueKey: Long): FullPlayerCatalogSong? = byQueueKey[queueKey]

    fun songForId(songId: String): FullPlayerCatalogSong? = indexBySongId[songId]?.let(entries::get)

    fun songs(): List<FullPlayerCatalogSong> = entries
}

/** Stable logical order. Current selection is deliberately a separate O(1) packet. */
internal class FullPlayerQueuePacket(
    val revision: Long,
    itemKeys: LongArray
) {
    val membership = app.stemwise.ui.player.surface.PlayerSurfaceQueueMembership(itemKeys)
    private val indexByKey = itemKeys.withIndex().associate { it.value to it.index }

    val itemCount: Int get() = membership.size

    init {
        require(revision >= 0L)
        require(itemCount > 0)
        require(indexByKey.size == itemCount) { "Queue keys must be unique" }
    }

    fun itemKeyAt(index: Int): Long = membership.itemKeyAt(index)
    fun indexOfKey(key: Long): Int? = indexByKey[key]
    fun copyItemKeys(): LongArray = membership.copyItemKeys()
}

internal data class FullPlayerSelectionPacket(
    val revision: Long,
    val currentIndex: Int
) {
    init {
        require(revision >= 0L)
        require(currentIndex >= 0)
    }
}

internal data class FullPlayerSection(
    val startMs: Long,
    val endMs: Long,
    val label: String
) {
    init {
        require(startMs >= 0L)
        require(endMs > startMs)
        require(label.isNotBlank())
    }
}

/** Stable coarse waveform/section payload for exactly one media revision. */
internal class FullPlayerWaveformPacket(
    val revision: Long,
    val mediaRevision: String,
    peaks: FloatArray,
    sections: List<FullPlayerSection>,
    beatTimesMs: LongArray = longArrayOf()
) {
    private val waveformPeaks = peaks.copyOf()
    private val songSections = Collections.unmodifiableList(sections.toList())
    private val beats = beatTimesMs.copyOf()

    val peakCount: Int get() = waveformPeaks.size
    val sectionCount: Int get() = songSections.size

    init {
        require(revision >= 0L)
        require(waveformPeaks.all { it.isFinite() && it in 0f..1f })
        require(beats.all { it >= 0L })
        for (index in 1 until beats.size) require(beats[index] > beats[index - 1])
        var previousEnd = 0L
        songSections.forEachIndexed { index, section ->
            require(index == 0 || section.startMs >= previousEnd) {
                "Sections must be ordered and non-overlapping"
            }
            previousEnd = section.endMs
        }
    }

    fun peakAt(index: Int): Float = waveformPeaks[index]


    fun copyPeaks(): FloatArray = waveformPeaks.copyOf()

    fun sections(): List<FullPlayerSection> = songSections

    /** Source-time pulse follows seeks, loops and tempo without running another clock or DSP. */
    fun beatPulseAt(positionMs: Long): Float? {
        if (beats.isEmpty()) return null
        val found = beats.binarySearch(positionMs)
        val index = if (found >= 0) found else -found - 2
        if (index < 0) return 0f
        val interval = if (index < beats.lastIndex) beats[index + 1] - beats[index] else 400L
        val decayMs = minOf(180L, interval / 2).coerceAtLeast(1L)
        val remaining = (1f - (positionMs - beats[index]).toFloat() / decayMs).coerceIn(0f, 1f)
        return remaining * remaining
    }
}

/** Optional moving 5 ms window. It is separate so loading it never recopies the coarse waveform. */
internal class FullPlayerFineWindowPacket(
    val revision: Long,
    val mediaRevision: String,
    val sourceRevision: Long,
    val mixerRevision: Long,
    val startMs: Long,
    val frameDurationMs: Long,
    peaks: FloatArray
) {
    private val windowPeaks = peaks.copyOf()

    val peakCount: Int get() = windowPeaks.size

    init {
        require(revision >= 0L)
        require(sourceRevision >= 0L && mixerRevision >= 0L)
        require(startMs >= 0L)
        require(frameDurationMs > 0L)
        require(windowPeaks.all { it.isFinite() && it in 0f..1f })
    }

    fun peakAt(index: Int): Float = windowPeaks[index]

    fun copyPeaks(): FloatArray = windowPeaks.copyOf()
}

internal enum class FullPlayerStemRole(val icon: StemwiseIconRole) {
    VOCALS(StemwiseIconRole.PLAYER_STEM_VOCALS),
    LEAD_VOCALS(StemwiseIconRole.PLAYER_STEM_LEAD_VOCALS),
    BACKING_VOCALS(StemwiseIconRole.PLAYER_STEM_BACKING_VOCALS),
    MALE_VOCALS(StemwiseIconRole.PLAYER_STEM_MALE_VOCALS),
    FEMALE_VOCALS(StemwiseIconRole.PLAYER_STEM_FEMALE_VOCALS),
    CHOIR(StemwiseIconRole.PLAYER_STEM_CHOIR),
    SOPRANO_VOCALS(StemwiseIconRole.PLAYER_STEM_SOPRANO_VOCALS),
    ALTO_VOCALS(StemwiseIconRole.PLAYER_STEM_ALTO_VOCALS),
    TENOR_VOCALS(StemwiseIconRole.PLAYER_STEM_TENOR_VOCALS),
    BASS_VOCALS(StemwiseIconRole.PLAYER_STEM_BASS_VOCALS),
    DRUMS(StemwiseIconRole.PLAYER_STEM_DRUMS),
    KICK(StemwiseIconRole.PLAYER_STEM_KICK),
    SNARE(StemwiseIconRole.PLAYER_STEM_SNARE),
    TOMS(StemwiseIconRole.PLAYER_STEM_TOMS),
    CYMBALS(StemwiseIconRole.PLAYER_STEM_CYMBALS),
    HI_HAT(StemwiseIconRole.PLAYER_STEM_HI_HAT),
    RIDE(StemwiseIconRole.PLAYER_STEM_RIDE),
    CRASH(StemwiseIconRole.PLAYER_STEM_CRASH),
    PERCUSSION(StemwiseIconRole.PLAYER_STEM_PERCUSSION),
    TAMBOURINE(StemwiseIconRole.PLAYER_STEM_TAMBOURINE),
    MARIMBA(StemwiseIconRole.PLAYER_STEM_MARIMBA),
    GLOCKENSPIEL(StemwiseIconRole.PLAYER_STEM_GLOCKENSPIEL),
    TIMPANI(StemwiseIconRole.PLAYER_STEM_TIMPANI),
    TRIANGLE(StemwiseIconRole.PLAYER_STEM_TRIANGLE),
    CONGAS(StemwiseIconRole.PLAYER_STEM_CONGAS),
    BELLS(StemwiseIconRole.PLAYER_STEM_BELLS),
    WIND_CHIMES(StemwiseIconRole.PLAYER_STEM_WIND_CHIMES),
    XYLOPHONE(StemwiseIconRole.PLAYER_STEM_XYLOPHONE),
    VIBRAPHONE(StemwiseIconRole.PLAYER_STEM_VIBRAPHONE),
    METAL_BARS(StemwiseIconRole.PLAYER_STEM_METAL_BARS),
    CLAP(StemwiseIconRole.PLAYER_STEM_CLAP),
    COWBELL(StemwiseIconRole.PLAYER_STEM_COWBELL),
    BASS(StemwiseIconRole.PLAYER_STEM_BASS),
    GUITAR(StemwiseIconRole.PLAYER_STEM_GUITAR),
    ACOUSTIC_GUITAR(StemwiseIconRole.PLAYER_STEM_ACOUSTIC_GUITAR),
    ELECTRIC_GUITAR(StemwiseIconRole.PLAYER_STEM_ELECTRIC_GUITAR),
    LEAD_GUITAR(StemwiseIconRole.PLAYER_STEM_LEAD_GUITAR),
    RHYTHM_GUITAR(StemwiseIconRole.PLAYER_STEM_RHYTHM_GUITAR),
    PLUCKED_STRINGS(StemwiseIconRole.PLAYER_STEM_PLUCKED_STRINGS),
    HARP(StemwiseIconRole.PLAYER_STEM_HARP),
    MANDOLIN(StemwiseIconRole.PLAYER_STEM_MANDOLIN),
    BANJO(StemwiseIconRole.PLAYER_STEM_BANJO),
    SITAR(StemwiseIconRole.PLAYER_STEM_SITAR),
    UKULELE(StemwiseIconRole.PLAYER_STEM_UKULELE),
    DOBRO(StemwiseIconRole.PLAYER_STEM_DOBRO),
    STRINGS(StemwiseIconRole.PLAYER_STEM_STRINGS),
    VIOLIN(StemwiseIconRole.PLAYER_STEM_VIOLIN),
    VIOLA(StemwiseIconRole.PLAYER_STEM_VIOLA),
    CELLO(StemwiseIconRole.PLAYER_STEM_CELLO),
    DOUBLE_BASS(StemwiseIconRole.PLAYER_STEM_DOUBLE_BASS),
    KEYS(StemwiseIconRole.PLAYER_STEM_KEYS),
    PIANO(StemwiseIconRole.PLAYER_STEM_PIANO),
    DIGITAL_PIANO(StemwiseIconRole.PLAYER_STEM_DIGITAL_PIANO),
    ORGAN(StemwiseIconRole.PLAYER_STEM_ORGAN),
    SYNTH(StemwiseIconRole.PLAYER_STEM_SYNTH),
    HARPSICHORD(StemwiseIconRole.PLAYER_STEM_HARPSICHORD),
    ACCORDION(StemwiseIconRole.PLAYER_STEM_ACCORDION),
    RHODES(StemwiseIconRole.PLAYER_STEM_RHODES),
    CLAVINET(StemwiseIconRole.PLAYER_STEM_CLAVINET),
    MELLOTRON(StemwiseIconRole.PLAYER_STEM_MELLOTRON),
    CELESTA(StemwiseIconRole.PLAYER_STEM_CELESTA),
    WIND(StemwiseIconRole.PLAYER_STEM_WIND),
    BRASS(StemwiseIconRole.PLAYER_STEM_BRASS),
    WOODWIND(StemwiseIconRole.PLAYER_STEM_WOODWIND),
    SAXOPHONE(StemwiseIconRole.PLAYER_STEM_SAXOPHONE),
    FLUTE(StemwiseIconRole.PLAYER_STEM_FLUTE),
    TRUMPET(StemwiseIconRole.PLAYER_STEM_TRUMPET),
    TROMBONE(StemwiseIconRole.PLAYER_STEM_TROMBONE),
    OBOE(StemwiseIconRole.PLAYER_STEM_OBOE),
    CLARINET(StemwiseIconRole.PLAYER_STEM_CLARINET),
    FRENCH_HORN(StemwiseIconRole.PLAYER_STEM_FRENCH_HORN),
    HARMONICA(StemwiseIconRole.PLAYER_STEM_HARMONICA),
    TUBA(StemwiseIconRole.PLAYER_STEM_TUBA),
    BASSOON(StemwiseIconRole.PLAYER_STEM_BASSOON),
    BAGPIPES(StemwiseIconRole.PLAYER_STEM_BAGPIPES),
    WHISTLE(StemwiseIconRole.PLAYER_STEM_WHISTLE),
    CROWD(StemwiseIconRole.PLAYER_STEM_CROWD),
    REMAINING(StemwiseIconRole.PLAYER_STEM_REMAINING),
    OTHER(StemwiseIconRole.PLAYER_STEM_OTHER)
}

internal data class FullPlayerMixerLaneVisual(
    val laneKey: Int,
    val role: FullPlayerStemRole,
    val label: String,
    val colorArgb: Int
) {
    init {
        require(label.isNotBlank())
    }
}

/** Source phase, installed mixer package and its visual topology are one indivisible component. */
internal class FullPlayerSourceMixerPacket(
    val revision: Long,
    val source: PlayerSurfaceSourcePacket,
    val mixer: PlayerSurfaceMixerPacket,
    lanes: List<FullPlayerMixerLaneVisual>) {
    private val laneVisuals = Collections.unmodifiableList(lanes.toList())

    val laneCount: Int get() = laneVisuals.size

    init {
        require(revision >= 0L)
        require(laneVisuals.size <= PLAYER_SURFACE_MAX_MIXER_LANES)
    }

    fun laneAt(index: Int): FullPlayerMixerLaneVisual = laneVisuals[index]

    fun lanes(): List<FullPlayerMixerLaneVisual> = laneVisuals
}

internal data class FullPlayerToolActivity(
    val loopActive: Boolean,
    val tuneActive: Boolean,
    val lyricsAvailable: Boolean,
    val metronomeActive: Boolean,
    val metronomeAvailable: Boolean,
    val speedActive: Boolean = false,
    /** Configuration capability; actual PCM readiness remains in loopPreparation. */
    val loopAvailable: Boolean = true,
    val loopPreparation: app.stemwise.playback.LoopPreparationState = app.stemwise.playback.LoopPreparationState()
)

internal sealed interface FullPlayerDetailState {
    val tool: PlayerSurfaceTool

    data object None : FullPlayerDetailState {
        override val tool: PlayerSurfaceTool = PlayerSurfaceTool.NONE
    }

    data class Loop(
        val durationMs: Long,
        val loopStartMs: Long?,
        val loopEndMs: Long?
    ) : FullPlayerDetailState {
        override val tool: PlayerSurfaceTool = PlayerSurfaceTool.LOOP

        init {
            require(durationMs >= 0L)
        }
    }

    data class Tune(
        val originalKey: String?,
        val tempo: Float,
        val semitoneShift: Int
    ) : FullPlayerDetailState {
        override val tool: PlayerSurfaceTool = PlayerSurfaceTool.TUNE
    }

    data class Lyrics(
        val mediaRevision: String,
        val contentRevision: Long,
        val trackCount: Int
    ) : FullPlayerDetailState {
        override val tool: PlayerSurfaceTool = PlayerSurfaceTool.LYRICS

        init {
            require(contentRevision >= 0L)
            require(trackCount >= 0)
        }
    }

    data class Metronome(
        val enabled: Boolean,
        val bpm: Float,
        val subdivision: Int,
        val numerator: Int,
        val denominator: Int,
        val volume: Float,
        val timingAvailable: Boolean,
        val tempo: Float = 1f
    ) : FullPlayerDetailState {
        override val tool: PlayerSurfaceTool = PlayerSurfaceTool.METRONOME
    }
}

/** Selection, active indicators and matching detail content are adopted as one component. */
internal data class FullPlayerToolDetailPacket(
    val revision: Long,
    val selectedTool: PlayerSurfaceTool,
    val activity: FullPlayerToolActivity,
    val detail: FullPlayerDetailState
) {
    init {
        require(revision >= 0L)
    }
}

internal enum class FullPlayerStatusKind { NONE, NOTICE, PROGRESS, ERROR }

internal data class FullPlayerStatusPacket(
    val revision: Long,
    val kind: FullPlayerStatusKind = FullPlayerStatusKind.NONE,
    val primaryText: String? = null,
    val secondaryText: String? = null,
    /** Exact acquisition completion for [FullPlayerStatusKind.PROGRESS]. */
    val progress: Float? = null
) {
    init {
        require(revision >= 0L)
        require(progress == null || progress.isFinite())
    }
}

/** One complete structural publication. Position and route progress use separate frame providers. */
internal data class FullPlayerSnapshot(
    val revision: Long,
    val owner: PlayerSurfaceOwnerKey,
    /**
     * Latest accepted visual-selection intent.
     *
     * The complete controls/timeline/source profile remains owned by [owner] and [selection].
     * During rapid navigation this ID can be ahead of that hydrated profile, allowing the native
     * pager to distinguish an expected intermediate owner from a genuinely superseding route.
     */
    val visualSelectionSongId: String = owner.songId,
    val catalog: FullPlayerCatalogPacket,
    val queue: FullPlayerQueuePacket,
    val selection: FullPlayerSelectionPacket,
    val timeline: PlayerSurfaceTimelinePacket,
    val waveform: FullPlayerWaveformPacket,
    val fineWindow: FullPlayerFineWindowPacket?,
    val sourceMixer: FullPlayerSourceMixerPacket,
    val transport: PlayerSurfaceTransportPacket,
    val toolDetail: FullPlayerToolDetailPacket,
    val status: FullPlayerStatusPacket
) {
    init {
        require(revision >= 0L)
    }
}

internal enum class FullPlayerSnapshotIssue {
    SNAPSHOT_REVISION_PRECEDES_COMPONENT,
    SELECTION_OUTSIDE_QUEUE,
    QUEUE_ENTRY_MISSING_FROM_CATALOG,
    OWNER_DOES_NOT_MATCH_QUEUE_SELECTION,
    VISUAL_SELECTION_MISSING_FROM_QUEUE,
    OWNER_MEDIA_REVISION_MISMATCH,
    WAVEFORM_OWNER_MISMATCH,
    WAVEFORM_REVISION_MISMATCH,
    FINE_WINDOW_OWNER_MISMATCH,
    SOURCE_MIXER_COMPONENT_REVISION_MISMATCH,
    SOURCE_MIXER_LANE_COUNT_MISMATCH,
    SOURCE_MIXER_LANE_KEY_MISMATCH,
    ORIGINAL_SOURCE_EXPOSES_MIXER,
    STEM_SOURCE_NOT_AVAILABLE,
    TOOL_DETAIL_MISMATCH,
    LOOP_DETAIL_INVALID,
    TUNE_DETAIL_INVALID,
    LYRICS_DETAIL_OWNER_MISMATCH,
    METRONOME_DETAIL_INVALID,
    TOOL_ACTIVITY_MISMATCH,
    STATUS_CONTENT_MISMATCH
}

internal object FullPlayerSnapshotValidator {
    private val metronomeDenominators = setOf(1, 2, 4, 8, 16, 32)

    /** [previouslyValidated] must be a snapshot that already passed this validator. */
    fun validate(
        snapshot: FullPlayerSnapshot,
        previouslyValidated: FullPlayerSnapshot? = null
    ): FullPlayerSnapshotIssue? {
        if (snapshot.catalog.revision > snapshot.revision ||
            snapshot.queue.revision > snapshot.revision ||
            snapshot.selection.revision > snapshot.revision ||
            snapshot.timeline.revision > snapshot.revision ||
            snapshot.waveform.revision > snapshot.revision ||
            (snapshot.fineWindow?.revision ?: 0L) > snapshot.revision ||
            snapshot.sourceMixer.revision > snapshot.revision ||
            snapshot.transport.revision > snapshot.revision ||
            snapshot.toolDetail.revision > snapshot.revision ||
            snapshot.status.revision > snapshot.revision
        ) {
            return FullPlayerSnapshotIssue.SNAPSHOT_REVISION_PRECEDES_COMPONENT
        }

        val selectedIndex = snapshot.selection.currentIndex
        if (selectedIndex !in 0 until snapshot.queue.itemCount) {
            return FullPlayerSnapshotIssue.SELECTION_OUTSIDE_QUEUE
        }
        val sameMembership = previouslyValidated?.catalog === snapshot.catalog &&
            previouslyValidated.queue === snapshot.queue
        if (!sameMembership) {
            var queueIndex = 0
            while (queueIndex < snapshot.queue.itemCount) {
                if (snapshot.catalog.songForQueueKey(snapshot.queue.itemKeyAt(queueIndex)) == null) {
                    return FullPlayerSnapshotIssue.QUEUE_ENTRY_MISSING_FROM_CATALOG
                }
                queueIndex++
            }
        }
        val selected = snapshot.catalog.songForQueueKey(snapshot.queue.itemKeyAt(selectedIndex))
            ?: return FullPlayerSnapshotIssue.QUEUE_ENTRY_MISSING_FROM_CATALOG
        if (selected.songId != snapshot.owner.songId) {
            return FullPlayerSnapshotIssue.OWNER_DOES_NOT_MATCH_QUEUE_SELECTION
        }
        val visualSelection = snapshot.catalog.songForId(snapshot.visualSelectionSongId)
            ?: return FullPlayerSnapshotIssue.VISUAL_SELECTION_MISSING_FROM_QUEUE
        if (snapshot.queue.indexOfKey(visualSelection.queueKey) == null) {
            return FullPlayerSnapshotIssue.VISUAL_SELECTION_MISSING_FROM_QUEUE
        }
        if (selected.mediaRevision != snapshot.owner.mediaRevision) {
            return FullPlayerSnapshotIssue.OWNER_MEDIA_REVISION_MISMATCH
        }
        if (snapshot.waveform.mediaRevision != snapshot.owner.mediaRevision) {
            return FullPlayerSnapshotIssue.WAVEFORM_OWNER_MISMATCH
        }
        if (snapshot.timeline.waveformRevision != snapshot.waveform.revision) {
            return FullPlayerSnapshotIssue.WAVEFORM_REVISION_MISMATCH
        }
        if (snapshot.fineWindow?.let {
                it.mediaRevision != snapshot.owner.mediaRevision ||
                    it.sourceRevision != snapshot.sourceMixer.source.revision ||
                    it.mixerRevision != snapshot.sourceMixer.mixer.revision
            } == true) {
            return FullPlayerSnapshotIssue.FINE_WINDOW_OWNER_MISMATCH
        }

        validateSourceMixer(snapshot.sourceMixer)?.let { return it }
        validateToolDetail(snapshot.owner, snapshot.timeline, snapshot.toolDetail)?.let { return it }
        validateStatus(snapshot.status)?.let { return it }
        return null
    }

    private fun validateSourceMixer(
        packet: FullPlayerSourceMixerPacket
    ): FullPlayerSnapshotIssue? {
        if (
            packet.source.revision > packet.revision ||
            packet.mixer.revision > packet.revision ||
            packet.mixer.packageRevision > packet.revision
        ) {
            return FullPlayerSnapshotIssue.SOURCE_MIXER_COMPONENT_REVISION_MISMATCH
        }
        if (packet.laneCount != packet.mixer.laneCount) {
            return FullPlayerSnapshotIssue.SOURCE_MIXER_LANE_COUNT_MISMATCH
        }
        var lane = 0
        while (lane < packet.laneCount) {
            if (packet.laneAt(lane).laneKey != packet.mixer.laneKeyAt(lane)) {
                return FullPlayerSnapshotIssue.SOURCE_MIXER_LANE_KEY_MISMATCH
            }
            lane++
        }
        if (
            packet.source.presentedMode == PlayerSurfaceSourceMode.ORIGINAL &&
            packet.laneCount != 0
        ) {
            return FullPlayerSnapshotIssue.ORIGINAL_SOURCE_EXPOSES_MIXER
        }
        if (
            packet.source.presentedMode == PlayerSurfaceSourceMode.STEMS &&
            !packet.source.stemsAvailable
        ) {
            return FullPlayerSnapshotIssue.STEM_SOURCE_NOT_AVAILABLE
        }
        return null
    }

    private fun validateToolDetail(
        owner: PlayerSurfaceOwnerKey,
        timeline: PlayerSurfaceTimelinePacket,
        packet: FullPlayerToolDetailPacket
    ): FullPlayerSnapshotIssue? {
        val detail = packet.detail
        if (packet.selectedTool != detail.tool) {
            return FullPlayerSnapshotIssue.TOOL_DETAIL_MISMATCH
        }
        when (detail) {
            FullPlayerDetailState.None -> Unit
            is FullPlayerDetailState.Loop -> {
                if (!packet.activity.loopAvailable) {
                    return FullPlayerSnapshotIssue.LOOP_DETAIL_INVALID
                }
                if (!validRuntimeLoop(detail.loopStartMs, detail.loopEndMs, detail.durationMs)) {
                    return FullPlayerSnapshotIssue.LOOP_DETAIL_INVALID
                }
                if (
                    detail.durationMs != timeline.durationMs ||
                    detail.loopStartMs != timeline.loopStartMs.takeIf { it >= 0L } ||
                    detail.loopEndMs != timeline.loopEndMs.takeIf { it >= 0L }
                ) return FullPlayerSnapshotIssue.LOOP_DETAIL_INVALID
                val active = detail.loopStartMs != null && detail.loopEndMs != null
                if (packet.activity.loopActive != active) {
                    return FullPlayerSnapshotIssue.TOOL_ACTIVITY_MISMATCH
                }
            }
            is FullPlayerDetailState.Tune -> {
                if (!detail.tempo.isFinite() || detail.tempo !in .5f..2f ||
                    detail.semitoneShift !in -12..12
                ) return FullPlayerSnapshotIssue.TUNE_DETAIL_INVALID
                val active = detail.semitoneShift != 0
                if (packet.activity.tuneActive != active) {
                    return FullPlayerSnapshotIssue.TOOL_ACTIVITY_MISMATCH
                }
            }
            is FullPlayerDetailState.Lyrics -> {
                if (detail.mediaRevision != owner.mediaRevision) {
                    return FullPlayerSnapshotIssue.LYRICS_DETAIL_OWNER_MISMATCH
                }
                if (packet.activity.lyricsAvailable != (detail.trackCount > 0)) {
                    return FullPlayerSnapshotIssue.TOOL_ACTIVITY_MISMATCH
                }
            }
            is FullPlayerDetailState.Metronome -> {
                if (!detail.bpm.isFinite() || detail.bpm <= 0f ||
                    detail.subdivision !in 1..4 || detail.numerator !in 1..32 ||
                    detail.denominator !in metronomeDenominators ||
                    !detail.volume.isFinite() || detail.volume !in 0f..1f ||
                    !detail.tempo.isFinite() || detail.tempo !in .5f..2f ||
                    detail.timingAvailable != packet.activity.metronomeAvailable
                ) return FullPlayerSnapshotIssue.METRONOME_DETAIL_INVALID
                if (packet.activity.metronomeActive != detail.enabled ||
                    packet.activity.speedActive != app.stemwise.ui.tuneTempoActive(detail.tempo)) {
                    return FullPlayerSnapshotIssue.TOOL_ACTIVITY_MISMATCH
                }
            }
        }
        if (packet.activity.metronomeActive && !packet.activity.metronomeAvailable) {
            return FullPlayerSnapshotIssue.TOOL_ACTIVITY_MISMATCH
        }
        if (packet.activity.loopActive && !packet.activity.loopAvailable) {
            return FullPlayerSnapshotIssue.TOOL_ACTIVITY_MISMATCH
        }
        return null
    }

    /** Setting A before B is a valid in-progress runtime loop. */
    private fun validRuntimeLoop(start: Long?, end: Long?, durationMs: Long): Boolean = when {
        start == null && end == null -> true
        start == null -> false
        start !in 0L..durationMs -> false
        end == null -> true
        end !in 0L..durationMs -> false
        else -> end > start
    }

    private fun validateStatus(status: FullPlayerStatusPacket): FullPlayerSnapshotIssue? {
        val primary = status.primaryText?.takeIf(String::isNotBlank)
        return when {
            status.kind == FullPlayerStatusKind.NONE &&
                (primary != null || !status.secondaryText.isNullOrBlank() || status.progress != null) ->
                FullPlayerSnapshotIssue.STATUS_CONTENT_MISMATCH
            status.kind != FullPlayerStatusKind.NONE && primary == null ->
                FullPlayerSnapshotIssue.STATUS_CONTENT_MISMATCH
            status.kind == FullPlayerStatusKind.PROGRESS &&
                (status.progress == null || status.progress !in 0f..1f) ->
                FullPlayerSnapshotIssue.STATUS_CONTENT_MISMATCH
            status.kind != FullPlayerStatusKind.PROGRESS && status.progress != null ->
                FullPlayerSnapshotIssue.STATUS_CONTENT_MISMATCH
            else -> null
        }
    }
}
