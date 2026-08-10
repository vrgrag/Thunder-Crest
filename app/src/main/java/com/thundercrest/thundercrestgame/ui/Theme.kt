package com.thundercrest.thundercrestgame.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.thundercrest.thundercrestgame.R

object Palette {
    val gold = Color(0xFFE9C25A)
    val goldLight = Color(0xFFFFE9A8)
    val goldDark = Color(0xFF9A6E1E)
    val deepBlue = Color(0xFF0C1E44)
    val night = Color(0xFF071022)
    val panel = Color(0xFF12224A)
    val panelLight = Color(0xFF1C356B)
    val sky = Color(0xFF6FA8DC)
    val cyan = Color(0xFF63D9FF)
    val ruby = Color(0xFFE0453B)
    val sapphire = Color(0xFF3C7BEA)
    val emerald = Color(0xFF33B55E)
    val amethyst = Color(0xFF9B54D6)
    val textLight = Color(0xFFF3ECD8)
}

val goldBrush = Brush.verticalGradient(
    listOf(Color(0xFFFFF0BE), Color(0xFFE9C25A), Color(0xFFB07E22))
)

val Cinzel = FontFamily(
    Font(R.font.cinzel_regular, FontWeight.Normal),
    Font(R.font.cinzel_bold, FontWeight.Bold),
    Font(R.font.cinzel_black, FontWeight.Black),
)

val Marcellus = FontFamily(Font(R.font.marcellus_regular, FontWeight.Normal))

private val typography = Typography(
    displayLarge = TextStyle(fontFamily = Cinzel, fontWeight = FontWeight.Black, fontSize = 44.sp),
    headlineMedium = TextStyle(fontFamily = Cinzel, fontWeight = FontWeight.Bold, fontSize = 26.sp),
    titleLarge = TextStyle(fontFamily = Cinzel, fontWeight = FontWeight.Bold, fontSize = 20.sp),
    bodyLarge = TextStyle(fontFamily = Marcellus, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = Marcellus, fontSize = 14.sp),
    labelLarge = TextStyle(fontFamily = Cinzel, fontWeight = FontWeight.Bold, fontSize = 16.sp),
)

@Composable
fun ThunderCrestTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Palette.gold,
        secondary = Palette.cyan,
        background = Palette.night,
        surface = Palette.panel,
        onPrimary = Palette.night,
        onBackground = Palette.textLight,
        onSurface = Palette.textLight,
    )
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
