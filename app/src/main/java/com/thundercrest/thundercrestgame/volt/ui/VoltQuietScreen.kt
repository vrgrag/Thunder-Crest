package com.thundercrest.thundercrestgame.volt.ui

import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.thundercrest.thundercrestgame.R

/**
 * No-connection screen shown when [com.thundercrest.thundercrestgame.volt.pipe.VoltNet]
 * reports the device is offline. Layout is pure "background artwork
 * + single Retry button" — see guide §"Screen Layout: VoltQuietScreen".
 *
 * ⚠️ **No safe-area / systemBars padding.** The artwork already
 *   reserves margin — insets on notched devices push the button off
 *   the geometric center of the card and it reads as skew.
 *
 * The button is anchored to the card the artwork paints via
 * [VoltArtMetrics] rather than to a percentage of screen height, so
 * it tracks the card when Crop reframes the bitmap.
 */
@Composable
fun VoltQuietScreen(
    busy: Boolean = false,
    onRetry: () -> Unit,
) {
    val cfg = LocalConfiguration.current
    val landscape = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
    val bg = if (landscape) R.drawable.volt_quiet_land else R.drawable.volt_quiet_port
    val card = if (landscape) VoltArtMetrics.OfflineLandscape else VoltArtMetrics.OfflinePortrait

    BoxWithConstraints(Modifier.fillMaxSize().background(VoltPalette.Night)) {
        val screenW = maxWidth
        val band = card.projectInto(maxWidth, maxHeight)

        Image(
            painter = painterResource(bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )

        val btnWidth = if (landscape) screenW * 0.30f else screenW * 0.55f
        val btnHeight = 54.dp

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = band.offsetForOffline(btnHeight))
                .width(btnWidth)
                .height(btnHeight),
        ) {
            VoltPillButton(
                label = "Retry",
                busy = busy,
                brush = crestBrush(),
                onTap = onRetry,
            )
        }
    }
}

/**
 * Reusable button pill with press-scale animation.
 *
 * @param busy — when true, replaces the label with a spinner in the
 *   same box (never resize the button — pitfalls §16).
 */
@Composable
internal fun VoltPillButton(
    label: String,
    brush: androidx.compose.ui.graphics.Brush,
    onTap: () -> Unit,
    busy: Boolean = false,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = tween(90),
        label = "pillScale",
    )
    Box(
        Modifier
            .fillMaxSize()
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .background(brush)
            .pointerInput(busy) {
                if (busy) return@pointerInput
                detectTapGestures(
                    onPress = { off ->
                        pressed = true
                        val released = tryAwaitRelease()
                        pressed = false
                        if (released) onTap()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = Color.White,
                strokeWidth = 2.5.dp,
            )
        } else {
            Text(label, style = VoltButtonTextStyle)
        }
    }
}
