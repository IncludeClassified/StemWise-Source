package app.stemwise.ui

import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class StemwiseAlertDialogSourcePolicyTest {
    @Test fun permanentDeleteKeepsCancelPhysicallyFirstInEveryLocale() {
        val expected = listOf(StemwisePermanentDeleteAction.CANCEL, StemwisePermanentDeleteAction.DELETE)
        assertEquals(expected, stemwisePermanentDeletePhysicalOrder(LayoutDirection.Ltr))
        assertEquals(expected, stemwisePermanentDeletePhysicalOrder(LayoutDirection.Rtl))
    }

    @Test fun dialogBodyFitsItsWindowWithTheAuthoredWidthCap() {
        assertEquals(20f, stemwiseDialogHorizontalMarginDp(360f), 0f)
        assertEquals(320f, stemwiseDialogBodyWidthDp(360f), 0f)
        assertEquals(392f, stemwiseDialogBodyWidthDp(432f), 0f)
    }

    @Test fun closeFirstRejectsBackendAdmissionWhileItsActualSettlementIsHeld() = runBlocking {
        kotlinx.coroutines.withTimeout(2_000L) {
            val gate = StemwiseDialogCloseGate()
            val closing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var dismissals = 0
            var starts = 0
            assertTrue(gate.accept())
            val close = launch {
                gate.finish(animateClose = { closing.complete(Unit); release.await() },
                    afterClose = { dismissals++ })
            }
            try {
                closing.await()
                repeat(2) { if (gate.beginBusyAction()) starts++ }
                assertEquals(0, starts)
                assertEquals(0, dismissals)
            } finally { release.complete(Unit); close.join() }
            assertEquals(1, dismissals)
        }
    }

    @Test
    fun closingAdmissionRejectsBusyAndNonClosingActionsEvenThroughOldCallbacks() {
        val gate = StemwiseDialogCloseGate()
        var actions = 0
        assertTrue(gate.accept())
        assertFalse(gate.beginBusyAction())
        assertFalse(gate.runIfOpen { actions++ })
        gate.endBusyAction()
        assertFalse(gate.accept())
        assertEquals(0, actions)
    }

    @Test
    fun busyAdmissionBlocksCloseAndReopensOnlyWhenItsOperationSettles() {
        val gate = StemwiseDialogCloseGate()
        assertTrue(gate.beginBusyAction())
        assertFalse(gate.beginBusyAction())
        assertFalse(gate.accept())
        assertFalse(gate.runIfOpen { error("Busy action was admitted") })
        gate.endBusyAction()
        var immediate = false
        assertTrue(gate.runIfOpen { immediate = true })
        assertTrue(immediate)
        assertTrue(gate.accept())
        gate.endBusyAction()
        assertFalse(gate.beginBusyAction())
    }

    @Test
    fun `accepted backend action starts once and cancellation still settles the close`() = runBlocking {
        val gate = StemwiseDialogCloseGate()
        var starts = 0
        assertFalse(gate.accept { false })
        assertTrue(gate.accept { starts += 1; true })
        assertFalse(gate.accept { starts += 1; true })
        assertEquals(1, starts)

        val animationStarted = CompletableDeferred<Unit>()
        var settlements = 0
        val close = launch {
            gate.finish(
                animateClose = {
                    animationStarted.complete(Unit)
                    awaitCancellation()
                },
                afterClose = { settlements += 1 }
            )
        }
        animationStarted.await()
        close.cancelAndJoin()
        assertEquals(1, settlements)
    }

}
