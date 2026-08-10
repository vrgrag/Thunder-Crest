package com.thundercrest.thundercrestgame.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thundercrest.thundercrestgame.core.Audio
import com.thundercrest.thundercrestgame.core.Sfx
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun OlympusBackground(img: ImageBitmap, dim: Float = 0.35f, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Palette.night)) {
        Image(
            bitmap = img,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha = dim * 0.6f),
                        Color.Black.copy(alpha = dim),
                        Color.Black.copy(alpha = dim * 1.4f),
                    )
                )
            )
        )
        Box(Modifier.fillMaxSize().systemBarsPadding()) { content() }
    }
}

@Composable
fun SpriteImage(img: ImageBitmap, modifier: Modifier = Modifier) {
    Image(bitmap = img, contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier)
}

/** A tap wrapper that plays the UI click sound. */
@Composable
fun clickable(enabled: Boolean = true, sound: Sfx = Sfx.CLICK, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return Modifier.clickable(interaction, indication = null, enabled = enabled) {
        Audio.play(sound)
        onClick()
    }
}

@Composable
fun GoldButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(58.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (enabled) goldBrush
                else Brush.verticalGradient(listOf(Color(0xFF5B647A), Color(0xFF39435C)))
            )
            .border(2.dp, Palette.goldLight.copy(alpha = if (enabled) 0.9f else 0.3f), RoundedCornerShape(16.dp))
            .then(clickable(enabled = enabled) { onClick() }),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(),
            color = if (enabled) Palette.night else Color(0xFFB9C0D0),
            fontFamily = Cinzel,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Palette.panelLight.copy(alpha = 0.96f), Palette.deepBlue.copy(alpha = 0.98f))
                )
            )
            .border(2.dp, Palette.gold.copy(alpha = 0.8f), RoundedCornerShape(22.dp))
            .padding(18.dp),
    ) { content() }
}

@Composable
fun StarsRow(count: Int, starSize: Int = 28, max: Int = 3) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until max) {
            Canvas(Modifier.size(starSize.dp)) {
                drawStar(this, filled = i < count)
            }
        }
    }
}

private fun drawStar(scope: DrawScope, filled: Boolean) {
    val cx = scope.size.width / 2f
    val cy = scope.size.height / 2f
    val outer = scope.size.minDimension / 2f * 0.95f
    val inner = outer * 0.45f
    val path = Path()
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) outer else inner
        val a = Math.toRadians((-90 + i * 36).toDouble())
        val x = cx + r * cos(a).toFloat()
        val y = cy + r * sin(a).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    if (filled) {
        scope.drawPath(path, Brush.verticalGradient(listOf(Color(0xFFFFF0BE), Color(0xFFE9C25A), Color(0xFFB07E22))))
    } else {
        scope.drawPath(path, Color(0xFF2A3557))
    }
}
