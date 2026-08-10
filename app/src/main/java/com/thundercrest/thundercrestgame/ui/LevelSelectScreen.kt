package com.thundercrest.thundercrestgame.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
fun LevelSelectScreen(chapter: Int, onBack: () -> Unit, onLevel: (Int) -> Unit) {
    OlympusBackground(Art.templeBg, dim = 0.5f) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            TopBar(title = "${chapter}. ${CHAPTER_INFO[chapter - 1].title}", onBack = onBack)
            Spacer(Modifier.height(12.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(count = LEVELS_PER_CHAPTER) { index ->
                    val id = (chapter - 1) * LEVELS_PER_CHAPTER + index
                    val unlocked = GameStore.isUnlocked(chapter, index)
                    val stars = GameStore.starsFor(id)
                    LevelTile(number = index + 1, stars = stars, unlocked = unlocked) {
                        if (unlocked) onLevel(index)
                    }
                }
            }
        }
    }
}

@Composable
private fun LevelTile(number: Int, stars: Int, unlocked: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .aspectRatio(0.86f)
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.verticalGradient(
                    if (unlocked) listOf(Palette.panelLight, Palette.deepBlue)
                    else listOf(Color(0xFF232C44), Color(0xFF151C30))
                )
            )
            .border(2.dp, if (unlocked) Palette.gold else Color(0xFF3A4560), RoundedCornerShape(14.dp))
            .alpha(if (unlocked) 1f else 0.6f)
            .then(clickable(enabled = unlocked) { onClick() }),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (unlocked) "$number" else "\uD83D\uDD12",
                color = if (unlocked) Palette.goldLight else Color(0xFF8590A8),
                fontFamily = Cinzel,
                fontWeight = FontWeight.Black,
                fontSize = 24.sp,
            )
            Spacer(Modifier.height(4.dp))
            if (unlocked) StarsRow(count = stars, starSize = 12)
        }
    }
}
