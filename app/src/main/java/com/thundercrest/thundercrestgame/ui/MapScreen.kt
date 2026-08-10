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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thundercrest.thundercrestgame.core.Art
import com.thundercrest.thundercrestgame.core.GameStore
import com.thundercrest.thundercrestgame.game.CHAPTER_INFO
import com.thundercrest.thundercrestgame.game.LEVELS_PER_CHAPTER

@Composable
fun MapScreen(onBack: () -> Unit, onChapter: (Int) -> Unit, onCrest: () -> Unit) {
    OlympusBackground(Art.mapBg, dim = 0.4f) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            TopBar(title = "Olympus Map", onBack = onBack) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${GameStore.crestFragments()}/12",
                        color = Palette.goldLight,
                        fontFamily = Cinzel,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                    Spacer(Modifier.size(8.dp))
                    Box(Modifier.size(38.dp).then(clickable { onCrest() })) {
                        SpriteImage(Art.crestFragment, Modifier.fillMaxSize())
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(CHAPTER_INFO) { info ->
                    val unlocked = GameStore.chapterUnlocked(info.number)
                    ChapterCard(
                        number = info.number,
                        title = info.title,
                        subtitle = info.subtitle,
                        stars = GameStore.chapterStars(info.number),
                        maxStars = LEVELS_PER_CHAPTER * 3,
                        unlocked = unlocked,
                        onClick = { if (unlocked) onChapter(info.number) },
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun ChapterCard(
    number: Int,
    title: String,
    subtitle: String,
    stars: Int,
    maxStars: Int,
    unlocked: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(Palette.panelLight.copy(alpha = 0.95f), Palette.deepBlue.copy(alpha = 0.95f))
                )
            )
            .border(2.dp, if (unlocked) Palette.gold else Color(0xFF3A4560), RoundedCornerShape(20.dp))
            .alpha(if (unlocked) 1f else 0.55f)
            .then(clickable(enabled = unlocked) { onClick() })
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(12.dp))
                    .background(Palette.deepBlue).border(2.dp, Palette.gold, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (unlocked) "$number" else "\uD83D\uDD12",
                    color = Palette.goldLight, fontFamily = Cinzel, fontWeight = FontWeight.Black, fontSize = 22.sp,
                )
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontFamily = Cinzel, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(subtitle, color = Palette.sky, fontFamily = Marcellus, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "\u2605 $stars / $maxStars",
                    color = Palette.goldLight, fontFamily = Cinzel, fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
fun TopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                .background(Palette.deepBlue.copy(alpha = 0.85f))
                .border(2.dp, Palette.gold, RoundedCornerShape(12.dp))
                .then(clickable { onBack() }),
            contentAlignment = Alignment.Center,
        ) {
            Text("\u2039", color = Palette.goldLight, fontFamily = Cinzel, fontWeight = FontWeight.Black, fontSize = 28.sp)
        }
        Spacer(Modifier.size(12.dp))
        Text(title, color = Color.White, fontFamily = Cinzel, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.weight(1f))
        actions()
    }
}
