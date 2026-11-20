package app.stemwise.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * StemWise's current application chrome is authored in English and has
 * fixed physical control placement. Song metadata still performs normal Unicode bidi shaping
 * inside those fixed columns; only a future explicit app-RTL contract may reverse the chrome.
 */
@Composable
internal fun StemwiseEnglishChrome(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
}
