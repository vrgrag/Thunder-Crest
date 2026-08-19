package com.thundercrest.thundercrestgame.gray.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thundercrest.thundercrestgame.R
import com.thundercrest.thundercrestgame.gray.GrayArt
import kotlinx.coroutines.delay

private val Gold = Color(0xFFE6B84D)
private val GoldOutline = Color(0xFFFFE9A8)
private val DeepNavy = Color(0xFF0B1A3A)
private val TextIvory = Color(0xFFF3ECD8)

/**
 * Loading screen — shows the orientation-matched background art, a gold
 * circular spinner in the exact centre, and a horizontal progress bar at
 * the bottom that advances through real pipeline milestones (same pattern
 * as OlympusSurge / IgnitionActivity).
 *
 * Navigation fires only from [onReady], which is invoked ~360 ms after
 * [progress] first reaches 1.0 — giving the final bar-fill animation time
 * to complete so the user always sees the screen before the transition.
 *
 * @param progress  Pipeline fraction 0..1. Caller pushes checkpoints:
 *                  0.15 boot · 0.30 online gate · 0.70 attribution ·
 *                  0.92 verdict · 1.0 hand-off.
 * @param stageLabel Human-readable status below the bar (optional).
 * @param onReady   Fires once, ~360 ms after [progress] >= 1.0. Call
 *                  startActivity / finish from here.
 */
@Composable
fun LoadingScreen(
    progress: Float = 0f,
    stageLabel: String? = null,
    onReady: (() -> Unit)? = null,
) {
    val finished = progress >= 1f

    // Fires once when the caller pushes progress to 1.0 (or above).
    // A short beat lets the final bar animation visibly land before
    // the activity swap takes effect.
    LaunchedEffect(finished) {
        if (finished && onReady != null) {
            delay(360)
            onReady()
        }
    }

    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 260),
        label = "loadProgress",
    )

    BoxWithConstraints(Modifier.fillMaxSize().background(DeepNavy)) {
        val landscape = maxWidth > maxHeight
        val ctx = LocalContext.current
        val bg = GrayArt.loading(ctx, landscape = landscape)

        Image(
            bitmap = bg,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )

        // No centered spinner — the horizontal progress bar at the bottom
        // already conveys "loading". A second circular indicator competing
        // with the branded artwork looks noisy and out of place.

        // Progress bar + percentage pinned to the bottom.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 32.dp)
                .padding(bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "${(animated * 100).toInt()}%",
                color = TextIvory.copy(alpha = 0.75f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))
            SplashProgressBar(animated)
        }
    }
}

/** Horizontal track that fills left-to-right with a gold gradient. */
@Composable
private fun SplashProgressBar(fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0x55000000)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(7.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xFFB8892A), Gold, GoldOutline),
                    )
                ),
        )
    }
}

/**
 * "No internet" screen. The single Retry button sits at the bottom of the
 * screen with a small gap from the edge regardless of orientation.
 */
@Composable
fun NoWifiScreen(onRetry: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(DeepNavy)) {
        val landscape = maxWidth > maxHeight
        val screenH = maxHeight
        val ctx = LocalContext.current
        val bg = GrayArt.noWifi(ctx, landscape = landscape)

        Image(
            bitmap = bg,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = (screenH * if (landscape) 0.10f else 0.08f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            OutlinedGoldButton(
                text = stringResource(R.string.offline_retry),
                modifier = Modifier.fillMaxWidth(if (landscape) 0.32f else 0.70f),
                onClick = onRetry,
            )
        }
    }
}

/**
 * Push-permission prompt shown once before the WebView takes over.
 *
 *  * Portrait: buttons stack vertically, both same gold fill and same width.
 *  * Landscape: buttons sit side-by-side. Both use a fixed pixel width
 *    computed from [BoxWithConstraints.maxWidth] so they are guaranteed to be
 *    identical regardless of Row's residual-space math. The Row is centred
 *    on the screen via [Arrangement.Center].
 *  * Both buttons share the same gold fill — text alone differentiates them.
 */
@Composable
fun PushPromptScreen(onAccept: () -> Unit, onSkip: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(DeepNavy)) {
        val landscape = maxWidth > maxHeight
        val screenH = maxHeight
        val screenW = maxWidth
        val ctx = LocalContext.current
        val bg = GrayArt.notif(ctx, landscape = landscape)

        Image(
            bitmap = bg,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            if (landscape) {
                // Compute explicit button width from the actual screen width so
                // both buttons are pixel-perfect equal. fillMaxWidth(fraction) inside
                // a Row gives the second button a SMALLER fraction because it measures
                // against the remaining space — using a fixed Dp avoids this.
                val btnWidth = screenW * 0.34f
                Row(
                    modifier = Modifier
                        .wrapContentWidth()
                        .padding(bottom = (screenH * 0.08f)),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedGoldButton(
                        text = stringResource(R.string.push_prompt_yes),
                        modifier = Modifier.width(btnWidth),
                        onClick = onAccept,
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedGoldButton(
                        text = stringResource(R.string.push_prompt_skip),
                        modifier = Modifier.width(btnWidth),
                        onClick = onSkip,
                    )
                }
            } else {
                // Portrait: both buttons stacked, same width and same gold fill.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = (screenH * 0.06f)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    OutlinedGoldButton(
                        text = stringResource(R.string.push_prompt_yes),
                        modifier = Modifier.fillMaxWidth(0.70f),
                        onClick = onAccept,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedGoldButton(
                        text = stringResource(R.string.push_prompt_skip),
                        modifier = Modifier.fillMaxWidth(0.70f),
                        onClick = onSkip,
                    )
                }
            }
        }
    }
}

// --- shared bits -------------------------------------------------------------

/**
 * Button used across the No-Wi-Fi and push-prompt screens.
 * Width and position are controlled by [modifier] from the caller — this
 * keeps the component layout-agnostic and lets landscape/portrait containers
 * pass exact pixel-equal sizes without fighting Row's residual-space math.
 */
@Composable
private fun OutlinedGoldButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(54.dp)
            .clip(RoundedCornerShape(27.dp))
            .background(Gold)
            .border(BorderStroke(2.dp, GoldOutline), RoundedCornerShape(27.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = DeepNavy,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CenterSpinner() {
    val transition = rememberInfiniteTransition(label = "spinner")
    val rot by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rot",
    )
    Box(
        modifier = Modifier
            .width(56.dp)
            .height(56.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Simple 3/4-arc spinner via two overlapping rounded boxes; keeps
        // the file free of Canvas boilerplate and avoids extra deps.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0x33FFFFFF)),
        )
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 6f
            drawArc(
                color = Gold,
                startAngle = rot,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(stroke, stroke),
                size = androidx.compose.ui.geometry.Size(size.width - stroke * 2, size.height - stroke * 2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
            )
        }
    }
}
