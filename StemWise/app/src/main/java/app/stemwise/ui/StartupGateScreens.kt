package app.stemwise.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import app.stemwise.ui.theme.Coral
import app.stemwise.ui.theme.PrimaryPurple
import app.stemwise.ui.theme.PrimaryBright
import app.stemwise.ui.theme.Outline
import app.stemwise.ui.theme.OutlineStrong
import app.stemwise.ui.theme.Surface
import app.stemwise.ui.theme.SurfaceLow
import app.stemwise.ui.theme.SplashBackground
import app.stemwise.ui.theme.StemwisePalette
import app.stemwise.ui.theme.TextPrimary
import app.stemwise.ui.theme.TextSecondary
import app.stemwise.ui.design.LocalStemwiseMotion
import app.stemwise.ui.design.StemwiseMotionEvent
import app.stemwise.ui.design.AuraAnchor
import app.stemwise.ui.design.StemwiseButton
import app.stemwise.ui.design.stemWiseInteractionAura
import app.stemwise.ui.design.semanticSpec
import kotlinx.coroutines.launch

@Composable
internal fun LibraryStartupGate() {
    // Android's splash window is the sole launch-logo owner. Keeping the Compose gate visually
    // empty prevents a second mark appearing beneath the platform icon during its exit fade.
    Box(Modifier.fillMaxSize().background(SplashBackground))
}

@Composable
internal fun LibraryOpeningGate(step: String) {
    val motion = LocalStemwiseMotion.current
    PageBackground {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (motion.enabled) {
                CircularProgressIndicator(
                    modifier = Modifier.size(34.dp),
                    color = PrimaryPurple,
                    strokeWidth = 3.dp
                )
            } else {
                androidx.compose.material3.Icon(
                    painter = androidx.compose.ui.res.painterResource(app.stemwise.R.drawable.ic_splash_mark),
                    contentDescription = null, modifier = Modifier.size(34.dp),
                    tint = androidx.compose.ui.graphics.Color.Unspecified
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_opening_library),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.fillMaxWidth(),
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                step,
                color = TextSecondary,
                lineHeight = 21.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
internal fun LibraryRecoveryGate(
    code: String,
    message: String,
    onPrimaryAction: (() -> Unit)?,
    onNotNow: () -> Unit
) {
    val presentation = libraryRecoveryPresentation(code, message)
    val primaryAction = onPrimaryAction.takeUnless { presentation.restartRequired }
    val motion = LocalStemwiseMotion.current
    PageBackground {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding()
        ) {
            val viewportWidthDp = maxWidth.value
            val viewportHeightDp = maxHeight.value
            val horizontalMargin = recoveryGateHorizontalMarginDp(viewportWidthDp).dp
            val stackActions = LocalDensity.current.fontScale >= 1.3f
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalMargin)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        top = recoveryGateTopPaddingDp(viewportHeightDp).dp,
                        bottom = 28.dp
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                GateAuraMark(
                    icon = StemwiseIcons.Error,
                    contentDescription = "StemWise recovery error",
                    tint = Coral,
                    auraColor = Coral,
                    markSizeDp = 124f,
                    iconSizeDp = 64f
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    text = androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_stemwise_needs_attention),
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().semantics { heading() }
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = presentation.summary,
                    color = TextSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(46.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Surface)
                        .border(1.dp, OutlineStrong, RoundedCornerShape(20.dp))
                        .semantics(mergeDescendants = true) { }
                        .padding(horizontal = 20.dp, vertical = 18.dp)
                        .heightIn(min = 154.dp)
                ) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_recovery_details),
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = presentation.reason,
                        color = Coral,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_no_music_or_metadata_was_deleted),
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = presentation.nextStep,
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                    AnimatedVisibility(
                        visible = presentation.operationalNotice != null,
                        enter = fadeIn(
                            motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)
                        ) + androidx.compose.animation.expandVertically(
                            motion.semanticSpec(
                                StemwiseMotionEvent.STATUS_CHANGE,
                                spatial = true
                            )
                        ),
                        exit = fadeOut(
                            motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)
                        ) + androidx.compose.animation.shrinkVertically(
                            motion.semanticSpec(
                                StemwiseMotionEvent.STATUS_CHANGE,
                                spatial = true
                            )
                        )
                    ) {
                        Column {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = presentation.operationalNotice.orEmpty(),
                                color = Coral,
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
                Spacer(Modifier.height(42.dp))
                if (primaryAction == null) {
                    GateTextActionButton("Close StemWise", onNotNow, modifier = Modifier.fillMaxWidth())
                } else if (stackActions) {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        GateTextActionButton("Not now", onNotNow)
                        GateIconActionButton(
                            label = presentation.actionLabel,
                            icon = StemwiseIcons.Refresh,
                            emphasized = true,
                            labelFontSizeSp = 14,
                            contentStartInsetDp = RECOVERY_ACTION_ICON_START_INSET_DP,
                            onClick = primaryAction
                        )
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        GateTextActionButton(
                            label = "Not now",
                            onClick = onNotNow,
                            modifier = Modifier.weight(1f)
                        )
                        GateIconActionButton(
                            label = presentation.actionLabel,
                            icon = StemwiseIcons.Refresh,
                            emphasized = true,
                            labelFontSizeSp = 14,
                            contentStartInsetDp = RECOVERY_ACTION_ICON_START_INSET_DP,
                            onClick = primaryAction,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun LibraryRootGate(
    errorCode: String?,
    operationalError: String?,
    onChoose: () -> Unit
) {
    val presentation = libraryRootGatePresentation(errorCode, operationalError)
    val motion = LocalStemwiseMotion.current
    PageBackground {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding()
        ) {
            val horizontalMargin = startupGateHorizontalMarginDp(maxWidth.value).dp
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalMargin)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        top = startupGateTopPaddingDp(maxHeight.value).dp,
                        bottom = 96.dp
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                GateAuraMark(
                    icon = StemwiseIcons.FolderOpen,
                    contentDescription = "Library folder",
                    tint = PrimaryBright,
                    auraColor = PrimaryPurple,
                    markSizeDp = 140f,
                    iconSizeDp = 72f
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = presentation.heading,
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary,
                    fontSize = 26.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().semantics { heading() }
                )
                AnimatedContent(
                    targetState = presentation.operationalError,
                    transitionSpec = {
                        fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)).togetherWith(
                            fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE))
                        )
                    },
                    label = "library-root-operational-error"
                ) { error ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (error != null) {
                            Spacer(Modifier.height(14.dp))
                            Text(
                                text = error,
                                color = Coral,
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                        Spacer(Modifier.height(if (error == null) 56.dp else 32.dp))
                    }
                }
                GateIconActionButton(
                    label = presentation.actionLabel,
                    icon = StemwiseIcons.FolderOpen,
                    emphasized = false,
                    contentStartInsetDp = STARTUP_ACTION_ICON_START_INSET_DP,
                    onClick = onChoose,
                    modifier = Modifier.fillMaxWidth()
                )

            }
            Text(
                text = androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_stemwise),
                color = PrimaryPurple,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp)
            )
        }
    }
}

internal fun startupGateHorizontalMarginDp(viewportWidthDp: Float): Float = when {
    viewportWidthDp <= 360f -> 16f
    viewportWidthDp < 480f -> 32f
    else -> ((viewportWidthDp - 480f) * .5f) + 32f
}

internal fun recoveryGateHorizontalMarginDp(viewportWidthDp: Float): Float = when {
    viewportWidthDp <= 360f -> 16f
    viewportWidthDp < 480f -> 28f
    else -> ((viewportWidthDp - 480f) * .5f) + 28f
}

internal fun startupGateTopPaddingDp(viewportHeightDp: Float): Float =
    if (viewportHeightDp < 820f) 40f else 82f

internal fun recoveryGateTopPaddingDp(viewportHeightDp: Float): Float =
    if (viewportHeightDp < 820f) 36f else 104f

internal const val STARTUP_ACTION_ICON_START_INSET_DP = 72f
internal const val RECOVERY_ACTION_ICON_START_INSET_DP = 30f
internal const val GATE_ACTION_ICON_SIZE_DP = 24f
internal const val GATE_ACTION_ICON_LABEL_GAP_DP = 12f

@Composable
internal fun GateAuraMark(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    auraColor: Color,
    markSizeDp: Float,
    iconSizeDp: Float
) {
    Box(
        Modifier
            .size(markSizeDp.dp)
            .semantics { this.contentDescription = contentDescription }
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to auraColor.copy(alpha = .34f),
                            .38f to auraColor.copy(alpha = .19f),
                            .72f to auraColor.copy(alpha = .07f),
                            1f to Color.Transparent
                        ),
                        center = center,
                        radius = size.minDimension * .5f
                    )
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(iconSizeDp.dp)
        )
    }
}

@Composable
internal fun GateIconActionButton(
    label: String,
    enabled: Boolean = true,
    icon: ImageVector,
    emphasized: Boolean,
    labelFontSizeSp: Int = 15,
    contentStartInsetDp: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val motion = LocalStemwiseMotion.current
    val shape = remember { RoundedCornerShape(18.dp) }
    val selectedBrush = remember {
        Brush.linearGradient(
            listOf(
                StemwisePalette.selectedLibraryRowGradientStart,
                StemwisePalette.selectedLibraryRowGradientEnd
            )
        )
    }
    val auraModifier = modifier.stemWiseInteractionAura(
        enabled = enabled,
        selected = false,
        interactionSource = interactionSource,
        anchor = AuraAnchor.VisibleControl,
        reducedMotion = !motion.enabled,
    )
    val decoratedModifier = if (emphasized) {
        auraModifier.background(selectedBrush, shape)
    } else {
        auraModifier.background(SurfaceLow, shape)
    }
    StemwiseButton(
        enabled = enabled,
        onClick = onClick,
        modifier = decoratedModifier.heightIn(min = if (emphasized) 54.dp else 56.dp),
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = TextPrimary
        ),
        border = BorderStroke(if (emphasized) 1.5.dp else 1.5.dp, PrimaryPurple),
        contentPadding = PaddingValues(0.dp),
        interactionSource = interactionSource,
        paintInteractionAura = false
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = contentStartInsetDp.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                Box(
                    Modifier
                        .size(GATE_ACTION_ICON_SIZE_DP.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(GATE_ACTION_ICON_SIZE_DP.dp),
                        tint = PrimaryBright
                    )
                }
                Spacer(Modifier.width(GATE_ACTION_ICON_LABEL_GAP_DP.dp))
                Text(
                    label,
                    modifier = Modifier.stemwiseMarquee(),
                    fontSize = labelFontSizeSp.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
internal fun GateTextActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = remember { RoundedCornerShape(18.dp) }
    StemwiseButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 54.dp),
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Surface,
            contentColor = TextSecondary
        ),
        border = BorderStroke(1.dp, Outline)
    ) {
        Text(
            label,
            modifier = Modifier.stemwiseMarquee(),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}
