package com.thundercrest.thundercrestgame.volt.ui

import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.thundercrest.thundercrestgame.R

/**
 * Splash / loading screen owned by [com.thundercrest.thundercrestgame.volt.VoltHubActivity].
 *
 * Layout follows guide §"Screen Layout: VoltSplashScreen":
 *  - full-bleed background art, portrait / landscape swap
 *  - "Loading . . ." caption cycling every 300 ms
 *  - progress bar 0 → 1 driven from the parent
 *
 * Non-interactive by design — do not attach clickable modifiers.
 */
@Composable
fun VoltSplashScreen(progress: Float) {
    val cfg = LocalConfiguration.current
    val landscape = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
    val bg = if (landscape) R.drawable.volt_splash_land else R.drawable.volt_splash_port

    BoxWithConstraints(Modifier.fillMaxSize().background(VoltPalette.Night)) {
        val screenW = maxWidth
        val screenH = maxHeight

        Image(
            painter = painterResource(bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )

        val captionBottomPct = if (landscape) 0.20f else 0.22f
        val barBottomPct = if (landscape) 0.10f else 0.14f
        val barWidth = if (landscape) screenW * 0.60f else screenW - 64.dp

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = screenH * captionBottomPct),
        ) {
            VoltLoadingCaption()
        }

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = screenH * barBottomPct)
                .width(barWidth)
                .height(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Color(0x55000000)),
        ) {
            val animated by animateFloatAsState(
                targetValue = progress.coerceIn(0f, 1f),
                animationSpec = tween(durationMillis = 250),
                label = "loadingProgress",
            )
            Box(
                Modifier
                    .fillMaxWidth(fraction = animated)
                    .height(18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(crestBrush()),
            )
        }
    }
}
