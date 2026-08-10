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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thundercrest.thundercrestgame.core.Art
import com.thundercrest.thundercrestgame.core.GameStore

private data class CollItem(val name: String, val art: ImageBitmap, val unlockAt: Int)

@Composable
fun CollectionScreen(onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val completed = GameStore.completedCount()

    val artifacts = listOf(
        CollItem("Zeus's Bolt", Art.crestFragment, 0),
        CollItem("Laurel Wreath", Art.laurel, 2),
        CollItem("Olympus Bowl", Art.bowl, 4),
        CollItem("Shield Fragment", Art.artifactFragment, 6),
        CollItem("Sacred Altar", Art.altar, 8),
        CollItem("Zeus Emblem", Art.emblem, 12),
    )
    val gems = listOf(
        CollItem("Ruby", Art.gem(com.thundercrest.thundercrestgame.game.GemColor.RUBY), 0),
        CollItem("Sapphire", Art.gem(com.thundercrest.thundercrestgame.game.GemColor.SAPPHIRE), 0),
        CollItem("Emerald", Art.gem(com.thundercrest.thundercrestgame.game.GemColor.EMERALD), 10),
        CollItem("Amethyst", Art.gem(com.thundercrest.thundercrestgame.game.GemColor.AMETHYST), 20),
        CollItem("Thunder Crystal", Art.thunderCrystal, 30),
        CollItem("Energy Sphere", Art.energySphere, 40),
    )
    val decor = listOf(
        CollItem("Sky Island", Art.skyIsland, 5),
        CollItem("Final Crystal", Art.finalCrystal, 25),
        CollItem("Zeus", Art.zeus, 45),
    )

    val items = when (tab) { 0 -> artifacts; 1 -> gems; else -> decor }

    OlympusBackground(Art.menuBg, dim = 0.5f) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            TopBar(title = "Olympus Collection", onBack = onBack)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TabChip("Artifacts", tab == 0) { tab = 0 }
                TabChip("Gems", tab == 1) { tab = 1 }
                TabChip("Decorations", tab == 2) { tab = 2 }
            }
            Spacer(Modifier.height(14.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items.size) { idx ->
                    val item = items[idx]
                    val unlocked = completed >= item.unlockAt
                    CollectionCard(item, unlocked)
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) Palette.gold else Palette.deepBlue.copy(alpha = 0.8f))
            .border(2.dp, Palette.gold, RoundedCornerShape(12.dp))
            .then(clickable { onClick() }),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (active) Palette.night else Palette.goldLight,
            fontFamily = Cinzel, fontWeight = FontWeight.Bold, fontSize = 13.sp,
        )
    }
}

@Composable
private fun CollectionCard(item: CollItem, unlocked: Boolean) {
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.deepBlue.copy(alpha = 0.75f))
            .border(2.dp, if (unlocked) Palette.gold else Color(0xFF3A4560), RoundedCornerShape(16.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(78.dp), contentAlignment = Alignment.Center) {
            SpriteImage(item.art, Modifier.fillMaxSize().alpha(if (unlocked) 1f else 0.12f))
            if (!unlocked) {
                Text("\uD83D\uDD12", fontSize = 26.sp, color = Color.White)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (unlocked) item.name else "Locked",
            color = if (unlocked) Palette.goldLight else Color(0xFF8590A8),
            fontFamily = Cinzel, fontWeight = FontWeight.Bold, fontSize = 11.sp,
            textAlign = TextAlign.Center,
        )
        if (!unlocked) {
            Text(
                "${item.unlockAt} levels",
                color = Palette.sky, fontFamily = Marcellus, fontSize = 10.sp,
            )
        }
    }
}
