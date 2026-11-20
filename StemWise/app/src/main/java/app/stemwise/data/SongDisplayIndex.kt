package app.stemwise.data

import app.stemwise.model.Song
import java.util.concurrent.ConcurrentHashMap

/** Root-lock-owned ordering; its monitor also covers exceptional unavailable-root clears. */
internal class SongDisplayIndex(private val comparisonObserver: (() -> Unit)? = null) {
    private data class Entry(val song: Song, val title: String, val artist: String)
    private val entries = ConcurrentHashMap<String, Entry>()
    private val displayOrder = compareBy<Entry>(Entry::title).thenBy(Entry::artist).thenBy { it.song.id }
    private val order = Comparator<Entry> { left, right ->
        comparisonObserver?.invoke()
        displayOrder.compare(left, right)
    }
    private val ordered = java.util.TreeSet(order)

    operator fun get(id: String): Song? = entries[id]?.song
    val values: List<Song> get() = entries.values.map(Entry::song)
    val keys: Set<String> get() = entries.keys

    @Synchronized fun upsert(song: Song): Boolean {
        val previous = entries[song.id]
        if (previous?.song == song) return false
        val entry = Entry(song, song.title.lowercase(), song.artist.lowercase())
        if (previous != null) ordered.remove(previous)
        ordered.add(entry)
        entries[song.id] = entry
        return true
    }

    @Synchronized fun remove(id: String): Boolean {
        val previous = entries.remove(id) ?: return false
        ordered.remove(previous)
        return true
    }

    @Synchronized fun clear() {
        entries.clear()
        ordered.clear()
    }

    @Synchronized fun replaceAll(songs: List<Song>) {
        clear()
        songs.forEach(::upsert)
    }

    /** Publication copies retained order; later index changes cannot mutate a prior snapshot. */
    @Synchronized fun snapshot(): List<Song> = ordered.map(Entry::song)
}
