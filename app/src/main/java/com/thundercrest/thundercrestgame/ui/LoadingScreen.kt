package com.thundercrest.thundercrestgame.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thundercrest.thundercrestgame.core.Art
import com.thundercrest.thundercrestgame.core.Audio
import com.thundercrest.thundercrestgame.game.LevelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loading screen. The progress bar is bound to *real* work: decoding every bundled
 * image, generating & validating all 50 levels, and initialising audio. The bar can
 * therefore never run ahead of the app — it fills left-to-right exactly as loading
 * actually progresses.
 */
@Composable
fun LoadingScreen(onDone: () -> Unit) {
    var real by remember { mutableFloatStateOf(0f) }
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp

    LaunchedEffect(Unit) {
        val imagePaths = Art.preloadPaths
        val levelCount = LevelRepository.totalLevels
        val total = imagePaths.size + levelCount + 1
        var done = 0

        fun step() {
            done++
            real = done.toFloat() / total
        }

        // 1) Decode all bundled art.
        for (p in imagePaths) {
            withContext(Dispatchers.Default) { runCatching { Art.load(p) } }
            step()
        }
        // 2) Generate every level (guaranteed solvable by the solver).
        for (i in 0 until levelCount) {
            withContext(Dispatchers.Default) { LevelRepository.generateAt(i) }
            step()
        }
        // 3) Initialise the audio engine.
        withContext(Dispatchers.Default) { runCatching { Audio.startMusic() } }
        step()

        real = 1f
        onDone()
    }

    // Displayed value eases toward the real value but is clamped so it can never
    // overtake actual progress.
    val eased by animateFloatAsState(real, tween(260, easing = LinearEasing), label = "prog")
    val shown = minOf(eased, real)

    val bg = if (landscape) Art.loadingLandscape else Art.loadingPortrait

    Box(Modifier.fillMaxSize().background(Palette.night)) {
        Image(
            bitmap = bg,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.55f))
                )
            )
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 34.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LoadingLabel()
            Box(Modifier.height(14.dp))
            ProgressBar(shown)
            Box(Modifier.height(8.dp))
            Text(
                "${(shown * 100).toInt()}%",
                color = Palette.goldLight,
                fontFamily = Cinzel,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
        }
    }
}

@Composable
private fun LoadingLabel() {
    val transition = rememberInfiniteTransition(label = "dots")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3.99f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "dotphase",
    )
    val dots = ".".repeat(phase.toInt())
    Text(
        "Loading$dots",
        color = Palette.textLight,
        fontFamily = Cinzel,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
    )
}

@Composable
private fun ProgressBar(progress: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(Color(0xFF0A1330).copy(alpha = 0.85f))
            .border(2.dp, Palette.gold.copy(alpha = 0.9f), RoundedCornerShape(13.dp))
            .padding(3.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(11.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xFF3C7BEA), Color(0xFF63D9FF), Color(0xFFFFE9A8))
                    )
                )
        )
    }
}
