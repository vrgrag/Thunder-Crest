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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thundercrest.thundercrestgame.WebActivity
import com.thundercrest.thundercrestgame.core.Art
import com.thundercrest.thundercrestgame.core.GameStore

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val sound by GameStore.soundVolume
    val music by GameStore.musicVolume
    val vibrate by GameStore.vibrate
    val graphics by GameStore.graphics

    OlympusBackground(Art.templeBg, dim = 0.55f) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            TopBar(title = "Settings", onBack = onBack)
            Spacer(Modifier.height(14.dp))
            Panel(Modifier.fillMaxWidth()) {
                Column {
                    SectionTitle("Audio")
                    SliderRow("Sound", sound) { GameStore.setSound(it) }
                    SliderRow("Music", music) { GameStore.setMusic(it) }

                    Spacer(Modifier.height(14.dp))
                    SectionTitle("Gameplay")
                    ToggleRow("Vibration", vibrate) { GameStore.setVibrate(it) }
                    Spacer(Modifier.height(10.dp))
                    Text("Graphics quality", color = Palette.textLight, fontFamily = Cinzel, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("Low", "Medium", "High").forEachIndexed { i, label ->
                            QualityChip(label, graphics == i) { GameStore.setGraphics(i) }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    SectionTitle("Language")
                    Text("English", color = Palette.goldLight, fontFamily = Cinzel, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            Panel(Modifier.fillMaxWidth()) {
                Column {
                    SectionTitle("About")
                    LinkRow("Privacy Policy") { WebActivity.open(context, WebActivity.PRIVACY) }
                    Spacer(Modifier.height(10.dp))
                    LinkRow("Support") { WebActivity.open(context, WebActivity.SUPPORT) }
                    Spacer(Modifier.height(10.dp))
                    Text("Version 1.0.0", color = Palette.sky, fontFamily = Marcellus, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Palette.goldLight, fontFamily = Cinzel, fontWeight = FontWeight.Black, fontSize = 16.sp)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SliderRow(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.textLight, fontFamily = Cinzel, fontSize = 14.sp, modifier = Modifier.width(72.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = Palette.goldLight,
                activeTrackColor = Palette.gold,
                inactiveTrackColor = Palette.deepBlue,
            ),
        )
        Text("${(value * 100).toInt()}%", color = Palette.goldLight, fontFamily = Cinzel, fontSize = 13.sp, modifier = Modifier.width(44.dp))
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.textLight, fontFamily = Cinzel, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Box(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (value) Palette.gold else Palette.deepBlue)
                .border(2.dp, Palette.gold, RoundedCornerShape(10.dp))
                .then(clickable { onChange(!value) })
                .padding(horizontal = 18.dp, vertical = 8.dp),
        ) {
            Text(
                if (value) "ON" else "OFF",
                color = if (value) Palette.night else Palette.goldLight,
                fontFamily = Cinzel, fontWeight = FontWeight.Bold, fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.QualityChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) Palette.gold else Palette.deepBlue.copy(alpha = 0.8f))
            .border(2.dp, Palette.gold, RoundedCornerShape(10.dp))
            .then(clickable { onClick() }),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (active) Palette.night else Palette.goldLight, fontFamily = Cinzel, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.deepBlue.copy(alpha = 0.6f))
            .border(1.dp, Palette.gold.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .then(clickable { onClick() })
            .padding(14.dp),
    ) {
        Text("$label  \u203A", color = Palette.goldLight, fontFamily = Cinzel, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}
