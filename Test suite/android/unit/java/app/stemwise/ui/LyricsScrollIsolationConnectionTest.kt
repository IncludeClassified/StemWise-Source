package app.stemwise.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsScrollIsolationConnectionTest {
    @Test
    fun sheetMovesOnlyForUserRemainderAndReturnsBeforeListScrolls() = runBlocking {
        var offset = 0f
        var settledVelocity: Float? = null
        val connection = LyricsSheetScrollConnection(
            offset = { offset },
            drag = { offset += it },
            settle = { settledVelocity = it }
        )
        assertEquals(Offset.Zero, connection.onPreScroll(Offset(3f, -50f), NestedScrollSource.UserInput))
        connection.onPostScroll(Offset.Zero, Offset(3f, 80f), NestedScrollSource.SideEffect)
        assertEquals(0f, offset, 0f)
        connection.onPostScroll(Offset.Zero, Offset(3f, 80f), NestedScrollSource.UserInput)
        assertEquals(80f, offset, 0f)
        assertEquals(Offset(0f, -80f), connection.onPreScroll(Offset(3f, -120f), NestedScrollSource.UserInput))
        assertEquals(0f, offset, 0f)
        connection.onPostScroll(Offset.Zero, Offset(0f, 90f), NestedScrollSource.UserInput)
        assertEquals(Velocity(0f, 400f), connection.onPreFling(Velocity(20f, 400f)))
        assertEquals(400f, settledVelocity!!, 0f)
    }

    @Test
    fun postScrollConsumesOnlyTheVerticalRemainder() {
        val consumed = LyricsScrollIsolationConnection.onPostScroll(
            consumed = Offset(x = 4f, y = -80f),
            available = Offset(x = 19f, y = -37f),
            source = NestedScrollSource.UserInput
        )

        assertEquals(0f, consumed.x, 0f)
        assertEquals(-37f, consumed.y, 0f)
    }

    @Test
    fun sideEffectRemainderCannotMoveThePlayerEither() {
        val consumed = LyricsScrollIsolationConnection.onPostScroll(
            consumed = Offset.Zero,
            available = Offset(x = -11f, y = 23f),
            source = NestedScrollSource.SideEffect
        )

        assertEquals(0f, consumed.x, 0f)
        assertEquals(23f, consumed.y, 0f)
    }

    @Test
    fun postFlingConsumesOnlyTheUnusedVerticalVelocity() = runBlocking {
        val consumed = LyricsScrollIsolationConnection.onPostFling(
            consumed = Velocity(x = 0f, y = -2_400f),
            available = Velocity(x = 700f, y = -1_100f)
        )

        assertEquals(0f, consumed.x, 0f)
        assertEquals(-1_100f, consumed.y, 0f)
    }

}
