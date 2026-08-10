package com.thundercrest.thundercrestgame.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thundercrest.thundercrestgame.core.Art
import com.thundercrest.thundercrestgame.core.GameStore

@Composable
fun CrestScreen(onBack: () -> Unit) {
    val fragments = GameStore.crestFragments()
    OlympusBackground(Art.stormyBg, dim = 0.5f) {
        Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            TopBar(title = "Thunder Crest", onBack = onBack)
            Spacer(Modifier.height(10.dp))
            Box(contentAlignment = Alignment.Center) {
                SpriteImage(Art.crest, Modifier.fillMaxWidth(0.9f).height(210.dp))
            }
            Text(
                "Progress  $fragments / 12",
                color = Palette.goldLight, fontFamily = Cinzel, fontWeight = FontWeight.Bold, fontSize = 18.sp,
            )
            Text(
                "Recover all fragments to fully restore the Crest of Zeus.",
                color = Palette.textLight, fontFamily = Marcellus, fontSize = 13.sp,
                textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 8.dp),
            )
            Spacer(Modifier.height(6.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(count = 12) { i ->
                    val got = i < fragments
                    Box(
                        Modifier
                            .size(70.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Palette.deepBlue.copy(alpha = 0.7f))
                            .border(2.dp, if (got) Palette.gold else Color(0xFF3A4560), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (got) {
                            SpriteImage(Art.crestFragment, Modifier.fillMaxSize().padding(6.dp))
                        } else {
                            Text("?", color = Color(0xFF5A6580), fontFamily = Cinzel, fontWeight = FontWeight.Black, fontSize = 22.sp)
                        }
                    }
                }
            }
        }
    }
}
