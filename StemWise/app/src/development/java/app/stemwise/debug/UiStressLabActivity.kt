package app.stemwise.debug

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import app.stemwise.ui.StemwiseEnglishChrome
import app.stemwise.ui.design.LocalStemwiseMotion
import app.stemwise.ui.design.StemwiseMotionSpec
import app.stemwise.ui.theme.StemwiseTheme

internal enum class UiStressDataMode(val label: String) {
    DEMO("Demo data"), WORST("Worst case"), EMPTY("Empty"), ONE("One"), LARGE("1,000 rows")
}

internal enum class UiStressSurface { Library, Player, Activity, Settings }

/** Development-only prop fixtures. No selected Library, transfers, files or credentials are mutated. */
class UiStressLabActivity : ComponentActivity() {
    private var launchIntent by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        launchIntent = intent
        val preferences = getSharedPreferences("ui-stress-lab", MODE_PRIVATE)
        fun saved(key: String, fallback: String): String = launchIntent?.getStringExtra(key)
            ?: preferences.getString(key, fallback) ?: fallback
        setContent {
            val initialSurface = UiStressSurface.entries.firstOrNull { it.name == saved("surface", "Library") }
                ?: UiStressSurface.Library
            val initialMode = UiStressDataMode.entries.firstOrNull { it.name == saved("data", "DEMO") }
                ?: UiStressDataMode.DEMO
            val initialWidth = saved("width", "432").toIntOrNull()?.takeIf { it in setOf(0, 320, 432) } ?: 432
            val initialScale = saved("textScale", "1").toFloatOrNull()?.takeIf { it == 1f || it == 2f } ?: 1f
            var surface by rememberSaveable(launchIntent) { mutableStateOf(initialSurface) }
            var mode by rememberSaveable(launchIntent) { mutableStateOf(initialMode) }
            var width by rememberSaveable(launchIntent) { mutableIntStateOf(initialWidth) }
            var textScale by rememberSaveable(launchIntent) { mutableFloatStateOf(initialScale) }
            SideEffect {
                preferences.edit().putString("surface", surface.name).putString("data", mode.name)
                    .putString("width", width.toString()).putString("textScale", textScale.toString()).apply()
            }
            val clipboard = LocalClipboard.current
            val emptyClipboard = remember(clipboard) {
                object : Clipboard by clipboard {
                    override suspend fun getClipEntry(): ClipEntry? = null
                    override suspend fun setClipEntry(clipEntry: ClipEntry?) = Unit
                }
            }
            StemwiseEnglishChrome {
                StemwiseTheme {
                    CompositionLocalProvider(LocalClipboard provides emptyClipboard,
                        LocalContentColor provides MaterialTheme.colorScheme.onBackground,
                        LocalStemwiseMotion provides StemwiseMotionSpec(enabled = false, ambientMotionEnabled = false)) {
                        Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
                            LabBar {
                                UiStressSurface.entries.forEach { item ->
                                    LabChoice(item.name, item == surface) { surface = item }
                                }
                                LabChoice("320 dp", width == 320) { width = 320 }
                                LabChoice("432 dp", width == 432) { width = 432 }
                                LabChoice("Wide", width == 0) { width = 0 }
                                LabChoice("Text 1×", textScale == 1f) { textScale = 1f }
                                LabChoice("Text 2×", textScale == 2f) { textScale = 2f }
                            }
                            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                                val contentWidth = if (width == 0) maxWidth else minOf(width.dp, maxWidth)
                                val density = LocalDensity.current.density
                                CompositionLocalProvider(LocalDensity provides Density(density, textScale)) {
                                    Box(Modifier.width(contentWidth).fillMaxHeight().clipToBounds()
                                        .background(Color.Black).testTag("ui-stress-content")) {
                                        // Native dataset changes rebind the mounted player, like normal song changes.
                                        key(surface, width, textScale) {
                                            when (surface) {
                                                UiStressSurface.Library -> key(mode) { UiStressLibrarySurface(mode) }
                                                UiStressSurface.Player -> UiStressPlayerSurface(mode)
                                                UiStressSurface.Activity -> UiStressActivitySurface(mode)
                                                UiStressSurface.Settings -> UiStressSettingsSurface(mode)
                                            }
                                        }
                                    }
                                }
                            }
                            LabBar {
                                UiStressDataMode.entries.forEach { item ->
                                    LabChoice(item.label, item == mode) { mode = item }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchIntent = intent
    }

    // Keep the normal launch/registry boundary while cancelling external browser and file actions.
    override fun startActivity(intent: Intent, options: Bundle?) = Unit

    @Deprecated("Development fixture boundary for legacy ActivityResult contracts")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        Handler(Looper.getMainLooper()).post { super.onActivityResult(requestCode, RESULT_CANCELED, null) }
    }
}

@Composable
private fun LabBar(content: @Composable RowScope.() -> Unit) {
    val density = LocalDensity.current.density
    CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
        Row(Modifier.fillMaxWidth().background(Color(0xFF292929)).horizontalScroll(rememberScrollState())
            .padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable
private fun LabChoice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp))
        .background(if (selected) Color.White else Color.Transparent)
        .selectable(selected, role = Role.RadioButton, onClick = onSelect)
        .padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 12.sp, color = if (selected) Color.Black else Color.White, maxLines = 1)
    }
}
