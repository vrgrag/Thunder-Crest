package com.thundercrest.thundercrestgame.volt.ui

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.thundercrest.thundercrestgame.R

/**
 * Push-permission promo screen. Two buttons (Accept + Skip), both real
 * gradient pills — never a subdued text link (pitfalls §9).
 *
 * ⚠️ **NO safe-area / systemBars padding on either orientation** —
 *   the artwork already reserves margin. Insets on notched devices
 *   shift the button block off the card's centre line.
 *
 * The vertical position comes from [VoltArtMetrics], not from a
 * percentage of screen height, so the buttons stay pinned under the
 * painted card on aspect ratios the artwork was not drawn for.
 */
@Composable
fun VoltPermitScreen(
    onAccept: () -> Unit,
    onSkip: () -> Unit,
) {
    val cfg = LocalConfiguration.current
    val landscape = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
    val bg = if (landscape) R.drawable.volt_permit_land else R.drawable.volt_permit_port
    val card = if (landscape) VoltArtMetrics.InviteLandscape else VoltArtMetrics.InvitePortrait

    BoxWithConstraints(Modifier.fillMaxSize().background(VoltPalette.Night)) {
        val screenW = maxWidth
        val band = card.projectInto(maxWidth, maxHeight)

        Image(
            painter = painterResource(bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )

        if (landscape) {
            // Side by side: stacked pills plus their gap would eat a
            // third of the available height and crowd the card above.
            val btnHeight = 52.dp
            val btnWidth = screenW * 0.26f
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = band.offsetForInvite(btnHeight)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Box(Modifier.width(btnWidth).height(btnHeight)) {
                    VoltPillButton(label = "Accept", brush = crestBrush(), onTap = onAccept)
                }
                Box(Modifier.width(btnWidth).height(btnHeight)) {
                    VoltPillButton(label = "Skip", brush = mutedCrestBrush(), onTap = onSkip)
                }
            }
        } else {
            val acceptHeight = 56.dp
            val skipHeight = 50.dp
            val spacing = 12.dp
            val btnWidth = screenW * 0.7f
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = band.offsetForInvite(acceptHeight + spacing + skipHeight))
                    .width(btnWidth),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing),
            ) {
                Box(Modifier.width(btnWidth).height(acceptHeight)) {
                    VoltPillButton(label = "Accept", brush = crestBrush(), onTap = onAccept)
                }
                Box(Modifier.width(btnWidth).height(skipHeight)) {
                    VoltPillButton(label = "Skip", brush = mutedCrestBrush(), onTap = onSkip)
                }
            }
        }
    }
}
