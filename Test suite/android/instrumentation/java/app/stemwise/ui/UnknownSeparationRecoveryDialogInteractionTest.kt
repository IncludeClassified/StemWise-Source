package app.stemwise.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import app.stemwise.data.RootAuthority
import app.stemwise.integration.SeparationRecoveryReview
import app.stemwise.ui.design.LocalStemwiseMotion
import app.stemwise.ui.design.StemwiseMotionSpec
import app.stemwise.ui.theme.StemwiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class UnknownSeparationRecoveryDialogInteractionTest {
    @get:Rule val compose = createStemwiseComposeRule()

    @Test fun dismissedRecoveryActionsCannotReviewOrAdoptEvenThroughRetiredCallbacks() {
        data class Offer(val id: Int, val motion: Boolean)
        val offer = mutableStateOf<Offer?>(null)
        val reviews = mutableListOf<Int>()
        val adoptions = mutableListOf<Int>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        compose.setContent {
            offer.value?.let { current -> key(current.id) {
                CompositionLocalProvider(LocalStemwiseMotion provides StemwiseMotionSpec(current.motion, false)) {
                    StemwiseTheme { UnknownSeparationRecoveryDialog(
                        jobId = "job-${current.id}", expectedRoot = ROOT, scope = scope,
                        onDismiss = { offer.value = null },
                        onReview = { remote ->
                            assertEquals("remote", remote)
                            reviews += current.id
                            Result.success(review("job-${current.id}"))
                        },
                        onRecover = { adoptions += current.id; Result.success(Unit) },
                        onRecovered = { error("Dismissed recovery cannot complete") }) }
                }
            } }
        }
        val retired = mutableListOf<() -> Boolean>()
        var id = 0
        try {
            for (motion in listOf(true, false)) for (reviewed in listOf(false, true)) {
                val current = Offer(++id, motion)
                compose.runOnIdle { offer.value = current }
                compose.onNode(hasSetTextAction()).performTextInput("remote")
                if (reviewed) compose.onNodeWithText("Review job").performClick()
                val confirm = compose.onNodeWithText(if (reviewed) "Confirm source and recover" else "Review job")
                    .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
                val cancel = compose.onNodeWithText(compose.activity.getString(app.stemwise.R.string.ui_cancel_2))
                    .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
                compose.mainClock.autoAdvance = false
                try {
                    compose.runOnIdle {
                        retired.forEach { it() }
                        cancel(); confirm(); confirm()
                        assertEquals(if (reviewed) 1 else 0, reviews.count { it == current.id })
                        assertEquals(0, adoptions.size)
                    }
                    compose.mainClock.advanceTimeBy(1_000L)
                    compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog)).assertCountEquals(0)
                    retired += confirm
                } finally { compose.mainClock.autoAdvance = true }
            }
            compose.runOnIdle {
                retired.forEach { it() }
                assertEquals(2, reviews.size)
                assertTrue(adoptions.isEmpty())
            }
        } finally { scope.cancel() }
    }

    @Test fun busyRecoveryAdmitsOnceRetriesFailureAndSurvivesDialogDisposal() {
        val visible = mutableStateOf(true)
        val reviewRelease = CompletableDeferred<Unit>()
        val failureRelease = CompletableDeferred<Unit>()
        val successRelease = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val acceptedReview = review("job")
        var reviews = 0
        var adoptions = 0
        var recovered = 0
        compose.setContent { StemwiseTheme {
            if (visible.value) UnknownSeparationRecoveryDialog(
                jobId = "job", expectedRoot = ROOT, scope = scope,
                onDismiss = { visible.value = false },
                onReview = {
                    reviews++
                    if (reviews == 1) {
                        withTimeout(5_000L) { reviewRelease.await() }
                        Result.failure(IllegalStateException("Review fixture failure"))
                    } else Result.success(acceptedReview)
                },
                onRecover = {
                    assertSame(acceptedReview, it)
                    adoptions++
                    if (adoptions == 1) {
                        withTimeout(5_000L) { failureRelease.await() }
                        Result.failure(IllegalStateException("Recovery fixture failure"))
                    } else {
                        withTimeout(5_000L) { successRelease.await() }
                        Result.success(Unit)
                    }
                },
                onRecovered = { recovered++; visible.value = false })
        } }
        try {
            compose.onNode(hasSetTextAction()).performTextInput("remote")
            val reviewAction = compose.onNodeWithText("Review job")
                .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            val cancel = compose.onNodeWithText(compose.activity.getString(app.stemwise.R.string.ui_cancel_2))
                .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            compose.runOnIdle { reviewAction(); reviewAction(); cancel(); assertEquals(1, reviews) }
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog)).assertCountEquals(1)
            compose.runOnIdle { reviewRelease.complete(Unit) }
            compose.onNodeWithText("Review fixture failure", substring = true).assertExists()
            compose.onNodeWithText("Review job").assertIsEnabled().performClick()
            val recoverAction = compose.onNodeWithText("Confirm source and recover")
                .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            compose.runOnIdle { recoverAction(); recoverAction(); cancel(); assertEquals(1, adoptions) }
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog)).assertCountEquals(1)
            compose.runOnIdle { failureRelease.complete(Unit) }
            compose.onNodeWithText("Recovery fixture failure", substring = true).assertExists()
            compose.onNodeWithText("Confirm source and recover").assertIsEnabled().performClick()
            compose.runOnIdle { assertEquals(2, adoptions); visible.value = false }
            compose.waitForIdle()
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog)).assertCountEquals(0)
            compose.runOnIdle { successRelease.complete(Unit) }
            compose.waitUntil(5_000L) { compose.runOnIdle { recovered == 1 } }
            compose.runOnIdle {
                recoverAction(); recoverAction()
                assertEquals(2, reviews)
                assertEquals(2, adoptions)
                assertEquals(1, recovered)
            }
        } finally {
            reviewRelease.complete(Unit)
            failureRelease.complete(Unit)
            successRelease.complete(Unit)
            scope.cancel()
        }
    }

    private fun review(jobId: String) = SeparationRecoveryReview(
        ROOT, jobId, "remote", "source", "a".repeat(64), 1L, 2L, 0, "b".repeat(64),
        "Fixture song", "Original", "Frozen model", null, null
    )

    private companion object {
        val ROOT = RootAuthority("fixture-library", "content://fixture/tree/library", 1L)
    }
}
