package app.stemwise.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule

/**
 * Explicit lifecycle owner for standard rehearsal Compose tests.
 *
 * The custom rehearsal runner does not guarantee that the implicit compose rule has launched its
 * host before [setContent]. Launching the manifest-provided test ComponentActivity explicitly
 * keeps every real owner mounted under the rule before a semantics or pixel query can run.
 */
// ponytail: retain the suite's current dispatcher; migrate all timing expectations with v2.
@Suppress("DEPRECATION")
internal fun createStemwiseComposeRule() = createAndroidComposeRule<ComponentActivity>()
