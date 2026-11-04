package com.reeltrak.app.features.splash.ui

import android.view.animation.OvershootInterpolator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reeltrak.app.R
import com.reeltrak.app.core.ui.theme.SoraFontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Re:Focus Official Brand Startup Splash Animation.
 *
 * Sequence:
 * 1. 0ms: "by FOCRAMEX" footer is stationary and visible from the very first frame.
 * 2. 0–480ms: Crescent Moon Clock at top right reveals with graceful scale & fade (480ms).
 * 3. 160–680ms: Leaping figure sweeps upward along reach trajectory with smooth overshoot inertia (520ms).
 * 4. 540–880ms: Re:Focus wordmark fades & slides in horizontally (340ms).
 * 5. 880–1950ms (~1.07s): Complete official logo + stationary "by FOCRAMEX" hold statically.
 * 6. 1950ms: Calls onSplashFinished() to transition cleanly into the main app over 300ms fade.
 *
 * Visual Layout & Positioning:
 * - Center Logo: Offset slightly upward by -24dp for refined optical balance.
 * - Footer "by FOCRAMEX": Raised noticeably upward (80dp bottom padding from navigation bar).
 * - Typography: Harmonized geometric typography matching Re:Focus brand system.
 */
@Composable
fun ReFocusSplashScreen(
    onSplashFinished: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color(0xFFFCF4E9),
    contentColor: Color = Color(0xFF302516)
) {
    val density = LocalDensity.current
    val wordmarkInitialOffsetPx = with(density) { 20.dp.toPx() }
    val figureInitialOffsetYPx = with(density) { 16.dp.toPx() }
    val figureInitialOffsetXPx = with(density) { (-10).dp.toPx() }

    val clockAlpha = remember { Animatable(0f) }
    val clockScale = remember { Animatable(0.75f) }

    val figureAlpha = remember { Animatable(0f) }
    val figureOffsetY = remember { Animatable(figureInitialOffsetYPx) }
    val figureOffsetX = remember { Animatable(figureInitialOffsetXPx) }
    val figureScale = remember { Animatable(0.92f) }

    val wordmarkAlpha = remember { Animatable(0f) }
    val wordmarkOffset = remember { Animatable(wordmarkInitialOffsetPx) }

    val overshootInterpolator = remember { OvershootInterpolator(1.15f) }

    LaunchedEffect(Unit) {
        // 1. Clock Reveal at top right
        launch {
            clockAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing)
            )
        }
        launch {
            clockScale.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing)
            )
        }

        // 2. Leaping Figure sweep into reaching pose
        launch {
            delay(160)
            launch {
                figureAlpha.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
                )
            }
            launch {
                figureOffsetY.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(
                        durationMillis = 520,
                        easing = { fraction -> overshootInterpolator.getInterpolation(fraction) }
                    )
                )
            }
            launch {
                figureOffsetX.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(
                        durationMillis = 520,
                        easing = { fraction -> overshootInterpolator.getInterpolation(fraction) }
                    )
                )
            }
            launch {
                figureScale.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = 520,
                        easing = { fraction -> overshootInterpolator.getInterpolation(fraction) }
                    )
                )
            }
        }

        // 3. Wordmark Fade & Slide In (540ms - 880ms)
        launch {
            delay(540)
            launch {
                wordmarkAlpha.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 340, easing = FastOutSlowInEasing)
                )
            }
            launch {
                wordmarkOffset.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 340, easing = FastOutSlowInEasing)
                )
            }
        }

        // 4. Hold finished branding statically until 1950ms
        delay(1950)
        onSplashFinished()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        // Center: Re:Focus Logo & Wordmark (raised by -24dp for optical vertical balance)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .offset(y = (-24).dp)
        ) {
            // Official Re:Focus Dynamic Logo Icon
            val iconSize = 72.dp
            Box(
                modifier = Modifier.size(iconSize),
                contentAlignment = Alignment.Center
            ) {
                // Clock layer (reveals at top right)
                Image(
                    painter = painterResource(id = R.drawable.ic_refocus_clock),
                    contentDescription = null,
                    colorFilter = if (contentColor == Color(0xFF302516)) null else ColorFilter.tint(contentColor),
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = clockAlpha.value
                            scaleX = clockScale.value
                            scaleY = clockScale.value
                            transformOrigin = TransformOrigin(0.85f, 0.15f)
                        }
                )

                // Figure layer (sweeps dynamically up into reaching pose)
                Image(
                    painter = painterResource(id = R.drawable.ic_refocus_figure),
                    contentDescription = null,
                    colorFilter = if (contentColor == Color(0xFF302516)) null else ColorFilter.tint(contentColor),
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = figureAlpha.value
                            translationX = figureOffsetX.value
                            translationY = figureOffsetY.value
                            scaleX = figureScale.value
                            scaleY = figureScale.value
                        }
                )
            }

            // Wordmark: Re:Focus brand typography mask
            val wordmarkHeight = 22.dp
            Image(
                painter = painterResource(id = R.drawable.ic_refocus_wordmark),
                contentDescription = "Re:Focus",
                colorFilter = ColorFilter.tint(contentColor),
                modifier = Modifier
                    .padding(start = 12.dp)
                    .height(wordmarkHeight)
                    .graphicsLayer {
                        translationX = wordmarkOffset.value
                        alpha = wordmarkAlpha.value
                    }
            )
        }

        // Bottom: "by FOCRAMEX" Subtitle
        Text(
            text = buildAnnotatedString {
                withStyle(
                    SpanStyle(
                        color = contentColor.copy(alpha = 0.45f),
                        fontFamily = SoraFontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 11.sp,
                        letterSpacing = 1.2.sp
                    )
                ) {
                    append("by ")
                }
                withStyle(
                    SpanStyle(
                        color = contentColor.copy(alpha = 0.85f),
                        fontFamily = SoraFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        letterSpacing = 3.sp
                    )
                ) {
                    append("FOCRAMEX")
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 80.dp)
        )
    }
}
