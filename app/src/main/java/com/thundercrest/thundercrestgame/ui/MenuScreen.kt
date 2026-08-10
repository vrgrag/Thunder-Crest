package com.thundercrest.thundercrestgame.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thundercrest.thundercrestgame.core.Art
import com.thundercrest.thundercrestgame.core.GameStore

@Composable
fun MenuScreen(
    onPlay: () -> Unit,
    onSettings: () -> Unit,
    onCollection: () -> Unit,
    onCrest: () -> Unit,
) {
    OlympusBackground(Art.menuBg, dim = 0.3f) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            SpriteImage(Art.gameName, Modifier.fillMaxWidth(0.82f).height(180.dp))

            Spacer(Modifier.height(6.dp))
            // Recovered crest preview.
            Box(contentAlignment = Alignment.Center) {
                SpriteImage(Art.crest, Modifier.fillMaxWidth(0.62f).height(150.dp))
            }
            Text(
                "Crest fragments  ${GameStore.crestFragments()}/12",
                color = Palette.goldLight,
                fontFamily = Cinzel,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )

            Spacer(Modifier.weight(1f))

            GoldButton("Start Game", Modifier.fillMaxWidth(0.8f)) { onPlay() }
            Spacer(Modifier.height(22.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                CircleMenuButton("Settings") { onSettings() }
                CircleMenuButton("Crest") { onCrest() }
                CircleMenuButton("Collection") { onCollection() }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
fun CircleMenuButton(label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(66.dp)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(listOf(Palette.panelLight, Palette.deepBlue))
                )
                .border(2.dp, Palette.gold, CircleShape)
                .then(clickable { onClick() }),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label.first().toString(),
                color = Palette.goldLight,
                fontFamily = Cinzel,
                fontWeight = FontWeight.Black,
                fontSize = 26.sp,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            color = Color.White,
            fontFamily = Cinzel,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
        )
    }
}
