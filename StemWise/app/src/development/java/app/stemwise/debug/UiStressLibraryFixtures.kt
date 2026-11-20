package app.stemwise.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.ui.LibraryScreen
import app.stemwise.ui.SongInfoDialog
import java.io.File

@Composable
internal fun UiStressLibrarySurface(mode: UiStressDataMode) {
    val context = LocalContext.current
    var songs by remember(mode) { mutableStateOf(uiStressSongs(mode, context)) }
    var selected by remember(mode) { mutableStateOf<String?>(null) }
    var details by remember(mode) { mutableStateOf<Song?>(null) }
    LibraryScreen(songs = songs, onSongClick = { selected = it.id; true },
        onSongManage = { details = it }, onSongDelete = { target -> songs = songs.filterNot { it.id == target.id } },
        onSongDeleteStems = {}, onSongPrepareParts = {}, currentSongId = selected,
        playlistName = if (mode == UiStressDataMode.WORST || mode == UiStressDataMode.LARGE)
            "نور الهدى — The complete live recordings, alternate takes & remastered concert collection (1998–2026)"
            else "Practice Library",
        playlistConfigured = true, syncing = false, syncError = null, ambientMotionEnabled = false)
    details?.let { song ->
        SongInfoDialog(song = song, onDismiss = { details = null }, onCopyVideoId = {},
            onDeleteSong = { songs = songs.filterNot { it.id == song.id }; details = null }, onDeleteStems = {})
    }
}

internal fun uiStressSongs(mode: UiStressDataMode, context: Context): List<Song> {
    val artwork = uiStressArtworkSources(context)
    val titles = listOf(
        "A Night at the Royal Albert Hall — Complete Concert, Encore & Rehearsal (25th Anniversary Remastered Edition)",
        "نور الهدى — يا صاحبي (Live 2026) / دل کی آواز",
        "Đặng Thị Ngọc Hân — Người ở đừng về / เสียงจากบ้าน",
        "👩🏽‍💻 Midnight session · Live from the studio 🎻",
        "J",
        "<b>Love & Peace</b> — Acoustic **Live** &amp; Unplugged",
        "Live at the Northern Lights Festival — Original Recording",
        "Live at the Northern Lights Festival — Original Recording",
        "IMG_20250914_183022_HDR_portrait_edited_edited — Final rehearsal",
        "Twelve-hour overnight radio session — full recording"
    )
    val artists = listOf("Aleksandra Wiśniewska-Kowalczyk feat. Christopher Alexander Montgomery III",
        "نور الهدى عبد الرحمن", "Đặng Thị Ngọc Hân", "👩🏽‍💻 Priya", "Jo", "Seán O'Brien-Ó Súilleabháin",
        "王秀英", "Ólafur Darri Ólafsson", "María José de la Cruz y Fernández", "Konstantin Oberhauser-Wettstein")
    val count = when (mode) {
        UiStressDataMode.EMPTY -> 0
        UiStressDataMode.ONE -> 1
        UiStressDataMode.DEMO -> 3
        UiStressDataMode.WORST -> titles.size
        UiStressDataMode.LARGE -> 1_000
    }
    return List(count) { index ->
        val variant = if (mode == UiStressDataMode.ONE) 4 else index % titles.size
        val worst = mode == UiStressDataMode.WORST || mode == UiStressDataMode.LARGE
        Song(id = "ui-stress-$index", videoId = "abcdefghijk",
            title = if (worst || mode == UiStressDataMode.ONE) titles[variant] else listOf("Morning Light", "Home", "After Rain")[index],
            artist = if (worst || mode == UiStressDataMode.ONE) artists[variant] else "Studio Ensemble",
            album = if (variant == 4) "" else if (worst)
                "The complete concert recordings — Deluxe edition, volume 12 [Remastered from the original tapes]" else "Practice sessions",
            durationMs = when (variant) { 4 -> 0; 9 -> 43_200_000; else -> 239_000L + index },
            state = if (worst && variant == 8) SongState.NEEDS_ATTENTION else SongState.READY,
            accent = Color(0xFF8C6DFF), artworkGlyph = "♪", hasStems = worst && variant % 2 == 0,
            artworkSource = when (variant) {
                0, 1, 2 -> artwork[variant]
                3 -> File(context.cacheDir, "ui-stress-missing-cover.png").absolutePath
                else -> null
            },
            sourceCodec = if (variant % 2 == 0) "flac" else "opus",
            year = if (variant == 4) "" else "2026",
            canonicalUrl = "https://example.test/recordings/complete-concert/alternate-takes/final-mix-$index",
            additionalInfo = if (worst) mapOf(
                "Source file" to "Concert_recording_Royal_Albert_Hall_25th_anniversary_remastered_disc_2_track_17_v12_final.flac",
                "Description" to "Recorded live with the original orchestra, choir and guest soloists. ".repeat(8)) else emptyMap())
    }
}

/** Feed real PNG files through the production loaders, which do not accept resource URIs. */
internal fun uiStressArtworkSources(context: Context): List<String> {
    val directory = File(context.cacheDir, "ui-stress-artwork").apply { check(isDirectory || mkdirs()) }
    return listOf(
        Triple(app.stemwise.R.drawable.ui_stress_wide, 4_000, 200),
        Triple(app.stemwise.R.drawable.ui_stress_tall, 200, 4_000),
        Triple(app.stemwise.R.drawable.ui_stress_transparent, 200, 200)
    ).mapIndexed { index, (resource, width, height) ->
        File(directory, "$index.png").apply {
            if (!isFile) {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    checkNotNull(context.getDrawable(resource)).apply {
                        setBounds(0, 0, width, height)
                        draw(Canvas(bitmap))
                    }
                    outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { bitmap.recycle() }
            }
        }.absolutePath
    }
}
