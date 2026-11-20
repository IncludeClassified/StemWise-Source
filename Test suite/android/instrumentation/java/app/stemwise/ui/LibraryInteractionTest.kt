package app.stemwise.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.semantics.SemanticsActions
import app.stemwise.data.SelectedLibraryContract
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.ui.theme.StemwiseTheme
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LibraryInteractionTest {
    @get:Rule
    val composeRule = createStemwiseComposeRule()

    @Test fun searchAndActivityRecoveryFollowEmptyPopulatedAndRefreshStates() {
        var songs by mutableStateOf(emptyList<Song>())
        var error by mutableStateOf<String?>(null)
        val waiting = mutableStateOf(false)
        var inspected: String? = null
        var additions = 0
        var recoveries = 0
        composeRule.setContent {
            StemwiseTheme {
                LibraryScreen(songs, onSongClick = { true }, onOpenActivity = { additions++ },
                    onSongManage = { inspected = it.id }, waitingForNetwork = waiting.value,
                    onSetupAction = { recoveries++ }, playlistName = "Optional playlist",
                    playlistConfigured = false, syncing = false, syncError = error)
            }
        }
        composeRule.onNodeWithContentDescription("Search title, artist, or album").assertIsDisplayed()
        composeRule.onNodeWithTag("library-add-song").assertDoesNotExist()
        composeRule.onNodeWithText("Open Activity").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Your library is empty.").assertExists()
        composeRule.runOnIdle { error = "Private diagnostic detail" }
        composeRule.onNodeWithText("Could not refresh the library").assertExists()
        composeRule.onNodeWithText("Private diagnostic detail").assertDoesNotExist()
        composeRule.onNodeWithText("Review settings").performClick()
        composeRule.runOnIdle {
            assertEquals(1, additions); assertEquals(1, recoveries)
            error = null
            waiting.value = true
        }
        composeRule.onNodeWithText(app.stemwise.network.WAITING_FOR_UNMETERED_NETWORK).assertExists()
        composeRule.onNodeWithText("Could not refresh the library").assertDoesNotExist()
        composeRule.onNodeWithText("Open Activity").assertDoesNotExist()
        composeRule.runOnIdle {
            waiting.value = false
            songs = listOf(Song("visible", "visible", "Visible", "StemWise", 60_000L,
                SongState.NEEDS_ATTENTION, Color.Cyan, artworkGlyph = "V"))
        }
        composeRule.onNodeWithTag("library-add-song").assertDoesNotExist()
        composeRule.onNodeWithText("Open Activity").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, additions) }
        composeRule.onNodeWithTag("library-song:visible").assertIsDisplayed()
        composeRule.onNodeWithText("This song needs attention.").assertExists()
        composeRule.onNodeWithText("View issue").performClick()
        composeRule.runOnIdle { assertEquals("visible", inspected) }
    }

    @Test fun populatedLibraryKeepsCreationActionsInActivity() {
        val song = Song("local-song", "local-song", "Local song", "StemWise", 60_000L,
            SongState.READY, Color.Cyan, artworkGlyph = "L")
        var additions = 0
        var setups = 0
        composeRule.setContent {
            StemwiseTheme {
                LibraryScreen(listOf(song), onSongClick = { true }, onOpenActivity = { additions++ },
                    onSetupAction = { setups++ }, playlistName = "Local Library", playlistConfigured = false,
                    syncing = false, syncError = null)
            }
        }
        composeRule.onNodeWithText("Get a complete song from your PC, or import its StemWise ZIP.")
            .assertDoesNotExist()
        composeRule.onNodeWithText("Add a song").assertDoesNotExist()
        composeRule.onNodeWithTag("library-add-song").assertDoesNotExist()
        composeRule.onNodeWithText("Open Activity").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(0, additions)
            assertEquals(0, setups)
        }
    }

    @Test fun overflowKeepsTheReviewedSongAcrossRefreshAndNeverStartsPlayback() {
        val first = Song("menu-first", "first", "First", "StemWise", 60_000L,
            SongState.READY, Color.Cyan, hasStems = true, artworkGlyph = "F")
        val second = first.copy(id = "menu-second", title = "Second")
        var songs by mutableStateOf(listOf(first, second))
        val parts = mutableListOf<String>()
        val deletes = mutableListOf<String>()
        var plays = 0
        composeRule.setContent {
            StemwiseTheme {
                LibraryScreen(songs, onSongClick = { plays++; true }, onSongPrepareParts = { parts += it.id },
                    onSongDelete = { deletes += it.id }, playlistName = "Current", syncing = false, syncError = null)
            }
        }
        composeRule.onNodeWithTag("library-song:${first.id}").assertIsDisplayed()
        composeRule.onNodeWithTag("library-song-menu:${first.id}").performTouchInput { click() }
        composeRule.runOnIdle { songs = listOf(second, first.copy(title = "Refreshed First")) }
        composeRule.onNodeWithText("Choose parts").performClick()
        composeRule.runOnIdle { assertEquals(listOf(first.id), parts); assertEquals(0, plays) }
        composeRule.onNodeWithTag("library-song:${first.id}").assertIsDisplayed()
        composeRule.onNodeWithTag("library-song-menu:${first.id}").performClick()
        composeRule.runOnIdle { songs = listOf(second) }
        composeRule.onNodeWithText("Delete song with everything").assertDoesNotExist()
        composeRule.runOnIdle { assertTrue(deletes.isEmpty()); assertEquals(0, plays) }
    }

    @Test fun noPlaylistLibraryRestoresTheSameSongAndPartialPixelOffsetAfterRecreation() {
        var songs by mutableStateOf((0..80).map { index ->
            Song("restore-$index", "restore-$index", "Restore $index", "Fixture", 60_000L,
                SongState.READY, Color.Cyan, artworkGlyph = "R")
        })
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            StemwiseTheme {
                LibraryScreen(songs, onSongClick = { true }, playlistName = "Local Library",
                    playlistConfigured = false, syncing = false, syncError = null,
                    ambientMotionEnabled = false)
            }
        }
        val list = composeRule.onNodeWithContentDescription("Song library")
        list.performScrollToKey("restore-40")
        list.performSemanticsAction(SemanticsActions.ScrollBy) { scroll -> assertTrue(scroll(0f, 19f)) }
        val row = composeRule.onNodeWithTag("library-song:restore-40")
        val before = row.getUnclippedBoundsInRoot().top.value
        // An insertion defeats restoration by numerical LazyColumn index alone.
        composeRule.runOnIdle { songs = listOf(songs.first().copy(id = "new-before-restored-song")) + songs }
        restoration.emulateSavedInstanceStateRestore()
        row.assertIsDisplayed()
        assertEquals(before, row.getUnclippedBoundsInRoot().top.value, 1f)
        composeRule.onNodeWithText("Open Activity").assertDoesNotExist()
    }

    @Test fun queryAndStableSongAnchorSurviveDestinationReentryWithANewMatchingSong() {
        var songs by mutableStateOf((0..80).map { index ->
            Song("anchor-$index", "anchor-$index", "Anchor $index", "Alpha", 60_000L,
                SongState.READY, Color.Cyan, artworkGlyph = "A")
        })
        var visible by mutableStateOf(true)
        composeRule.setContent {
            val destinations = rememberSaveableStateHolder()
            StemwiseTheme {
                if (visible) destinations.SaveableStateProvider("library") {
                    LibraryScreen(songs, onSongClick = { true }, playlistName = "Current", syncing = false, syncError = null)
                }
            }
        }
        val search = composeRule.onNodeWithContentDescription("Search title, artist, or album")
        search.performClick().performTextInput("alpha")
        composeRule.onNodeWithContentDescription("Song library").performScrollToKey("anchor-40")
        val before = composeRule.onNodeWithTag("library-song:anchor-40").fetchSemanticsNode().boundsInRoot.top
        composeRule.runOnIdle { visible = false }
        composeRule.runOnIdle { songs = listOf(songs.first().copy(id = "new-matching-song")) + songs; visible = true }
        search.assertTextContains("alpha")
        composeRule.onNodeWithTag("library-song:anchor-40").assertIsDisplayed()
        val after = composeRule.onNodeWithTag("library-song:anchor-40").fetchSemanticsNode().boundsInRoot.top
        assertEquals(before, after, 1f)
    }

    @Test fun keyboardActivatesAndInspectsTheSameFocusedLibraryRow() {
        var plays = 0
        var details = 0
        val song = Song("keyboard-song", "keyboard-song", "Keyboard song", "StemWise",
            60_000L, SongState.READY, Color.Cyan, artworkGlyph = "K")
        composeRule.setContent {
            StemwiseTheme {
                LibraryScreen(songs = listOf(song), onSongClick = { plays++; true },
                    onSongManage = { details++ }, playlistName = "Current",
                    syncing = false, syncError = null)
            }
        }
        val row = composeRule.onNodeWithTag("library-song:${song.id}")
        row.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        row.assertIsFocused().performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        composeRule.runOnIdle { assertEquals(1, plays); assertEquals(0, details) }
        row.assertIsFocused().performKeyInput { keyDown(Key.Menu); keyUp(Key.Menu) }
        composeRule.runOnIdle { assertEquals(1, plays); assertEquals(0, details) }
        composeRule.onNodeWithText("Song details").performClick()
        composeRule.runOnIdle { assertEquals(1, plays); assertEquals(1, details) }
    }

    @Test
    fun heroActionsDispatchOnceAndDisappearWithoutSongs() {
        var songs by mutableStateOf(listOf(Song("hero-song", "hero-song", "Hero song", "StemWise",
            60_000L, SongState.READY, Color.Cyan, artworkGlyph = "H")))
        var plays = 0
        var shuffles = 0
        composeRule.setContent {
            StemwiseTheme {
                LibraryScreen(songs = songs, onSongClick = { true },
                    onPlayAll = { plays++ }, onShuffle = { shuffles++ },
                    playlistName = "Current", syncing = false, syncError = null)
            }
        }
        val play = composeRule.onNodeWithText("Play")
        val shuffle = composeRule.onNodeWithText("Shuffle")
        play.assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, plays); assertEquals(0, shuffles) }
        shuffle.assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, plays); assertEquals(1, shuffles) }
        composeRule.runOnIdle { songs = emptyList() }
        play.assertDoesNotExist()
        shuffle.assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, plays); assertEquals(1, shuffles) }
    }

    @Test
    fun playlistSetupUsesOneCompactAccessibleProgressDescription() {
        composeRule.setContent {
            StemwiseTheme {
                LibraryScreen(
                    songs = emptyList(),
                    onSongClick = { true },
                    playlistName = "",
                    playlistConfigured = false,
                    syncing = false,
                    syncError = null
                )
            }
        }

        composeRule.onNodeWithContentDescription(
            "Setup progress: Folder complete, Import current, Selected parts optional"
        ).assertExists()
    }

    @Test
    fun searchKeepsFocusAfterTheFirstCharacters() {
        composeRule.setContent {
            StemwiseTheme {
                LibraryScreen(
                    songs = listOf(
                        Song(
                            id = "search-song",
                            videoId = "search-video",
                            title = "Orbit",
                            artist = "Alpha Artist",
                            album = "Night",
                            durationMs = 60_000L,
                            state = SongState.READY,
                            accent = Color.Cyan,
                            artworkGlyph = "O"
                        )
                    ),
                    onSongClick = { true },
                    playlistName = "Test playlist",
                    syncing = false,
                    syncError = null
                )
            }
        }

        val search = composeRule.onNodeWithContentDescription(
            "Search title, artist, or album"
        )
        search.performClick()
        search.performTextInput("alpha")

        search.assertIsFocused()
    }

    @Test
    fun noSearchMatchesExplainsTheConditionAndClearsOnlyTheQuery() {
        composeRule.setContent {
            StemwiseTheme {
                LibraryScreen(
                    songs = listOf(
                        Song(
                            id = "no-result-song",
                            videoId = "no-result-video",
                            title = "Orbit",
                            artist = "Alpha Artist",
                            album = "Night",
                            durationMs = 60_000L,
                            state = SongState.READY,
                            accent = Color.Cyan,
                            artworkGlyph = "O"
                        )
                    ),
                    onSongClick = { true },
                    playlistName = "Test playlist",
                    syncing = false,
                    syncError = null
                )
            }
        }

        composeRule.onNodeWithContentDescription("Search title, artist, or album")
            .performClick()
            .performTextInput("unmatched")

        composeRule.onNodeWithContentDescription("No songs match this search.").assertExists()
        composeRule.onNodeWithTag(LIBRARY_SEARCH_CANVAS_MARKER_TAG).assertExists()
        composeRule.onNodeWithText("No matching songs").assertDoesNotExist()
        composeRule.onNodeWithText("Try a title, artist, or album.").assertDoesNotExist()
        composeRule.onNodeWithText("Clear search").performClick()
        composeRule.onNodeWithContentDescription("Search title, artist, or album").assertTextContains("")
        composeRule.onNodeWithTag("library-song:no-result-song").assertExists()
    }

    @Test
    fun searchResultKeepsDistinctHighlightedTitleArtistAndAlbumLines() {
        composeRule.setContent {
            StemwiseTheme {
                LibraryScreen(
                    songs = listOf(
                        Song(
                            id = "three-line-search-song",
                            videoId = "three-line-search-video",
                            title = "Starlight Symphony",
                            artist = "Aura and Echo",
                            album = "Nebula Starfields",
                            durationMs = 60_000L,
                            state = SongState.READY,
                            accent = Color.Cyan,
                            artworkGlyph = "S"
                        )
                    ),
                    onSongClick = { true },
                    playlistName = "Test playlist",
                    syncing = false,
                    syncError = null
                )
            }
        }

        composeRule.onNodeWithContentDescription("Search title, artist, or album")
            .performClick()
            .performTextInput("star")

        composeRule.onNodeWithText("Starlight Symphony").assertIsDisplayed()
        composeRule.onNodeWithText("Aura and Echo").assertIsDisplayed()
        composeRule.onNodeWithText("Album · Nebula Starfields").assertIsDisplayed()
    }

    @Test
    fun searchBoundsStayStableWhenTheClearActionAppears() {
        composeRule.setContent {
            StemwiseTheme {
                LibraryScreen(
                    songs = listOf(
                        Song(
                            id = "stable-search",
                            videoId = "stable-search-video",
                            title = "Orbit",
                            artist = "Alpha Artist",
                            durationMs = 60_000L,
                            state = SongState.READY,
                            accent = Color.Cyan,
                            artworkGlyph = "O"
                        )
                    ),
                    onSongClick = { true },
                    playlistName = "Test playlist",
                    syncing = false,
                    syncError = null
                )
            }
        }

        val search = composeRule.onNodeWithContentDescription(
            "Search title, artist, or album"
        )
        val before = search.fetchSemanticsNode().boundsInRoot
        search.performClick()
        search.performTextInput("alpha")
        composeRule.waitForIdle()
        val after = search.fetchSemanticsNode().boundsInRoot

        assertEquals(before.width, after.width, .5f)
        assertEquals(before.height, after.height, .5f)
    }

    @Test
    fun verticalDragBeginningInsideSearchCollapsesTheLibraryHero() {
        val songs = (0 until 80).map { index ->
            Song(
                id = "search-drag-$index",
                videoId = "search-drag-video-$index",
                title = "Search Drag Song $index",
                artist = "Scale Artist",
                durationMs = 60_000L,
                state = SongState.READY,
                accent = Color.Cyan,
                artworkGlyph = "S"
            )
        }
        composeRule.setContent {
            StemwiseTheme {
                LibraryScreen(
                    songs = songs,
                    onSongClick = { true },
                    playlistName = "Search drag library",
                    syncing = false,
                    syncError = null
                )
            }
        }

        composeRule.onNodeWithTag("library-search-field").performTouchInput {
            swipe(
                start = Offset(width * .5f, height * .8f),
                end = Offset(width * .5f, -2_000f),
                durationMillis = 300L
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Play").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Search title, artist, or album").assertIsDisplayed()
        composeRule.onNodeWithTag("library-add-song").assertDoesNotExist()
    }

    @Test
    fun restoredSearchAndScrollReleaseOpeningWithoutComposingTheHero() {
        val restoration = StateRestorationTester(composeRule)
        val songs = (0 until 80).map { index ->
            Song(
                id = "restore-$index",
                videoId = "restore-video-$index",
                title = "Restore Song $index",
                artist = "Scale Artist",
                durationMs = 60_000L,
                state = SongState.READY,
                accent = Color.Cyan,
                artworkGlyph = "S"
            )
        }
        var openingReports = 0
        restoration.setContent {
            StemwiseTheme {
                LibraryScreen(
                    songs = songs,
                    onSongClick = { true },
                    playlistName = "Restore library",
                    syncing = false,
                    syncError = null,
                    onOpeningArtworkSettled = { openingReports++ },
                    ambientMotionEnabled = false
                )
            }
        }
        composeRule.runOnIdle { assertEquals(1, openingReports) }
        val search = composeRule.onNodeWithContentDescription("Search title, artist, or album")
        search.performTextInput("Restore")
        composeRule.runOnIdle { openingReports = 0 }
        restoration.emulateSavedInstanceStateRestore()
        search.assertTextContains("Restore")
        composeRule.onNodeWithText("Play").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, openingReports) }

        search.performTextReplacement("")
        composeRule.onNodeWithContentDescription("Song library").performScrollToKey("restore-20")
        composeRule.onNodeWithText("Play").assertDoesNotExist()
        composeRule.runOnIdle { openingReports = 0 }
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag("library-song:restore-20").assertIsDisplayed()
        composeRule.onNodeWithText("Play").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, openingReports) }
    }

    @Test
    fun shortVerticalDragMovesRowsWhileHeroFades() {
        val songs = (0 until 24).map { index ->
            Song(
                id = "scroll-$index",
                videoId = "scroll-video-$index",
                title = "Scroll Song $index",
                artist = "Scale Artist",
                durationMs = 60_000L,
                state = SongState.READY,
                accent = Color.Cyan,
                artworkGlyph = "S"
            )
        }
        composeRule.setContent {
            StemwiseTheme {
                LibraryScreen(
                    songs = songs,
                    onSongClick = { true },
                    playlistName = "Scroll library",
                    syncing = false,
                    syncError = null
                )
            }
        }

        val firstRow = composeRule.onNodeWithTag("library-song:scroll-0")
        val beforeTop = firstRow.fetchSemanticsNode().boundsInRoot.top
        composeRule.onNodeWithContentDescription("Song library").performTouchInput {
            val startY = height * .78f
            swipe(
                start = Offset(width * .5f, startY),
                end = Offset(width * .5f, startY - height * .12f),
                durationMillis = 250L
            )
        }
        composeRule.waitForIdle()
        val afterTop = firstRow.fetchSemanticsNode().boundsInRoot.top

        assertTrue("Library chrome blocked the short list drag", afterTop < beforeTop - 4f)
    }

    @Test
    fun hardwareBackClosesAndClearsSearchBeforeLeavingTheHost() {
        var dispatcher: OnBackPressedDispatcher? = null
        composeRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            StemwiseTheme {
                LibraryScreen(
                    songs = listOf(
                        Song(
                            id = "back-search",
                            videoId = "back-search-video",
                            title = "Orbit",
                            artist = "Alpha Artist",
                            durationMs = 60_000L,
                            state = SongState.READY,
                            accent = Color.Cyan,
                            artworkGlyph = "O"
                        )
                    ),
                    onSongClick = { true },
                    playlistName = "Test playlist",
                    syncing = false,
                    syncError = null
                )
            }
        }

        val search = composeRule.onNodeWithContentDescription(
            "Search title, artist, or album"
        )
        search.performClick()
        search.performTextInput("alpha")
        search.assertIsFocused()
        composeRule.runOnIdle { requireNotNull(dispatcher).onBackPressed() }

        search.assertIsNotFocused()
        composeRule.onNodeWithContentDescription("Clear search").assertDoesNotExist()
    }

    @Test
    fun selectingAFilteredSongReleasesSearchFocusForThePlayer() {
        var selectedId: String? = null
        val song = Song(
            id = "filtered-song",
            videoId = "filtered-video",
            title = "Orbit",
            artist = "Alpha Artist",
            album = "Night",
            durationMs = 60_000L,
            state = SongState.READY,
            accent = Color.Cyan,
            artworkGlyph = "O"
        )
        composeRule.setContent {
            StemwiseTheme {
                LibraryScreen(
                    songs = listOf(song),
                    onSongClick = { selectedId = it.id; true },
                    playlistName = "Test playlist",
                    syncing = false,
                    syncError = null
                )
            }
        }

        val search = composeRule.onNodeWithContentDescription(
            "Search title, artist, or album"
        )
        search.performClick()
        search.performTextInput("alpha")
        composeRule.onNodeWithContentDescription("Orbit, Alpha Artist, Night, 1:00").performClick()

        search.assertIsNotFocused()
        composeRule.runOnIdle { assertEquals("filtered-song", selectedId) }
    }

    @Test
    fun delayedSongSelectionNeverClearsANewSearch() {
        val song = Song(
            id = "selection-search", videoId = "selection-search", title = "Orbit",
            artist = "Alpha Artist", durationMs = 60_000L, state = SongState.READY,
            accent = Color.Cyan, artworkGlyph = "O"
        )
        composeRule.setContent {
            StemwiseTheme {
                LibraryScreen(
                    songs = listOf(song), onSongClick = { true }, playlistName = "Test",
                    syncing = false, syncError = null
                )
            }
        }
        val search = composeRule.onNodeWithContentDescription("Search title, artist, or album")
        search.performClick()
        search.performTextInput("alpha")
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithContentDescription("Orbit, Alpha Artist, 1:00")
                .fetchSemanticsNodes().size == 1
        }
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onNodeWithContentDescription("Orbit, Alpha Artist, 1:00")
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
            composeRule.mainClock.advanceTimeBy(96L)
            composeRule.onNodeWithContentDescription("Orbit, Alpha Artist, 1:00")
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
            composeRule.mainClock.advanceTimeBy(96L)
            // The first tap's deadline has elapsed, but the latest accepted tap still owns it.
            search.assertTextContains("alpha")
            composeRule.mainClock.advanceTimeBy(LIBRARY_SELECTION_HANDOFF_MS + 1_000L)
            search.assertTextContains("alpha")
            search.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            search.performTextReplacement("new search")
            composeRule.mainClock.advanceTimeBy(LIBRARY_SELECTION_HANDOFF_MS + 1_000L)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()
        search.assertTextContains("new search")
        search.assertIsFocused()
    }

    @Test
    fun longPressOpensInformationWithoutPlayingTheSong() {
        var playCount = 0
        var informationCount = 0
        val song = Song(
            id = "managed-song",
            videoId = "managed-video",
            title = "Northbound",
            artist = "StemWise",
            durationMs = 60_000L,
            state = SongState.READY,
            accent = Color.Cyan,
            hasStems = true,
            artworkGlyph = "N"
        )
        composeRule.setContent {
            StemwiseTheme {
                LibraryScreen(
                    songs = listOf(song),
                    onSongClick = { playCount += 1; true },
                    onSongManage = { informationCount += 1 },
                    playlistName = "Test playlist",
                    syncing = false,
                    syncError = null
                )
            }
        }

        val row = composeRule.onNodeWithContentDescription(
            "Northbound, StemWise, 1:00"
        )
        row.assertIsDisplayed()
        // Wide windows center the capped Library viewport; its edges still belong to the row.
        val viewport = composeRule.onNodeWithContentDescription("Song library").fetchSemanticsNode().boundsInRoot
        val rowBounds = row.fetchSemanticsNode().boundsInRoot
        assertEquals("The row must accept touches at the Library viewport's left edge",
            viewport.left, rowBounds.left, 1f)
        assertEquals("The row must accept touches at the Library viewport's right edge",
            viewport.right, rowBounds.right, 1f)
        row.performTouchInput { down(Offset(2f, centerY)) }
        composeRule.mainClock.advanceTimeBy(650L)

        composeRule.runOnIdle {
            assertEquals(0, playCount)
            assertEquals(0, informationCount)
        }
        row.performTouchInput { up() }
        composeRule.onNodeWithText("Song details").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(0, playCount)
            assertEquals(1, informationCount)
        }
    }

    @Test
    fun currentSongBarsMoveOnlyWhilePlayingAndUncovered() {
        val song = Song(
            id = "current-song",
            videoId = "current-video",
            title = "Held Note",
            artist = "StemWise",
            durationMs = 60_000L,
            state = SongState.READY,
            accent = Color.Cyan,
            artworkGlyph = "H"
        )
        var audioPlaying by mutableStateOf(false)
        var uncovered by mutableStateOf(true)
        composeRule.setContent {
            StemwiseTheme {
                LibraryScreen(
                    songs = listOf(song),
                    currentSongId = song.id,
                    currentSongPlaying = audioPlaying,
                    ambientMotionEnabled = uncovered,
                    onSongClick = { true },
                    playlistName = "Test playlist",
                    syncing = false,
                    syncError = null
                )
            }
        }

        composeRule.onNodeWithContentDescription(
            "Current song, Held Note, StemWise, 1:00"
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(
            "library-playing-bars:${song.id}",
            useUnmergedTree = true
        ).assertIsDisplayed()

        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread { audioPlaying = true }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(
            "Now playing, Held Note, StemWise, 1:00"
        ).assertExists()
        composeRule.onNodeWithTag(
            "library-playing-bars:${song.id}",
            useUnmergedTree = true
        ).assertIsDisplayed()

        fun pixels(): IntArray {
            val image = composeRule.onNodeWithTag("library-playing-bars:${song.id}",
                useUnmergedTree = true).captureToImage().toPixelMap()
            return IntArray(image.width * image.height) { index ->
                image[index % image.width, index / image.width].toArgb()
            }
        }
        val playing = pixels()
        composeRule.mainClock.advanceTimeBy(300L)
        assertFalse("Playing bars did not animate", playing.contentEquals(pixels()))
        composeRule.runOnUiThread { uncovered = false }
        composeRule.mainClock.advanceTimeByFrame()
        val covered = pixels()
        composeRule.mainClock.advanceTimeBy(300L)
        assertArrayEquals("Covered Library kept animating", covered, pixels())
        composeRule.runOnUiThread { uncovered = true }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(300L)
        assertFalse("Uncovered Library did not resume animation", covered.contentEquals(pixels()))
        composeRule.runOnUiThread { audioPlaying = false }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(
            "Current song, Held Note, StemWise, 1:00"
        ).assertExists()
        composeRule.onNodeWithTag(
            "library-playing-bars:${song.id}",
            useUnmergedTree = true
        ).assertIsDisplayed()
        val paused = pixels()
        composeRule.mainClock.advanceTimeBy(300L)
        assertArrayEquals("Paused bars kept animating", paused, pixels())
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun maximumSongLibrarySurvivesRapidPhysicalScrollAndStableIdSelection() {
        val maximum = 8_192
        val targetIndex = maximum - 1
        val songs = (0 until maximum).map { index ->
            Song(
                id = "scale-$index",
                videoId = "scale-video-$index",
                title = "Scale Song $index",
                artist = "Scale Artist",
                durationMs = 60_000L,
                state = SongState.READY,
                accent = Color.Cyan,
                artworkGlyph = "S"
            )
        }
        var selectedId: String? = null
        composeRule.setContent {
            StemwiseTheme {
                LibraryScreen(
                    songs = songs,
                    onSongClick = { selectedId = it.id; true },
                    playlistName = "$maximum-song library",
                    syncing = false,
                    syncError = null
                )
            }
        }

        val library = composeRule.onNodeWithContentDescription("Song library")
        library.performTouchInput {
            repeat(96) {
                swipe(
                    start = Offset(width * .55f, height * .82f),
                    end = Offset(width * .55f, height * .18f),
                    durationMillis = 32L,
                )
            }
            repeat(16) { index ->
                val upward = index % 2 == 0
                swipe(
                    start = Offset(width * .55f, height * if (upward) .82f else .18f),
                    end = Offset(width * .55f, height * if (upward) .18f else .82f),
                    durationMillis = 32L,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("library-song:scale-0").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(null, selectedId) }
        composeRule.onNodeWithContentDescription(
            "Search title, artist, or album"
        ).assertIsDisplayed()

        val targetDescription = "Scale Song $targetIndex, Scale Artist, 1:00"
        library.performScrollToKey("scale-$targetIndex")
        composeRule.onNodeWithContentDescription(targetDescription).performClick()

        composeRule.onNodeWithContentDescription(
            "Search title, artist, or album"
        ).assertIsDisplayed()

        composeRule.runOnIdle { assertEquals("scale-$targetIndex", selectedId) }
    }
}
