package com.thundercrest.thundercrestgame.volt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Colours + typography shared by all gray-flow Compose screens.
 *
 * Deliberately a mini theme rather than a full Material palette —
 * we only need three surfaces (background night, gold, ember) and the
 * primary text style used across loading / offline / invite.
 */
object VoltPalette {
    val Night = Color(0xFF140A08)
    val NightSecondary = Color(0xFF2A120C)

    val GoldStart = Color(0xFFFFE08A)
    val GoldEnd = Color(0xFFE0A020)
    val GoldDeep = Color(0xFFA85C10)

    val EmberStart = Color(0xFFFF6A3D)
    val EmberEnd = Color(0xFFC62828)

    val TextPrimary = Color(0xFFF5F1DC)
    val TextMuted = Color(0xFFB8C4D8)
}

@Composable
fun VoltTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        background = VoltPalette.Night,
        surface = VoltPalette.NightSecondary,
        primary = VoltPalette.GoldStart,
        onPrimary = Color.White,
        onBackground = VoltPalette.TextPrimary,
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = MaterialTheme.typography,
    ) {
        CompositionLocalProvider(
            LocalTextStyle provides TextStyle(
                color = VoltPalette.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            ),
        ) { content() }
    }
}

/** Gold→ember crest brush used by every primary button (Accept / Retry). */
fun crestBrush(): Brush = Brush.verticalGradient(
    colors = listOf(
        VoltPalette.GoldStart,
        VoltPalette.GoldEnd,
        VoltPalette.EmberStart,
    ),
)

/**
 * Deeper maroon/gold Skip pill — still a real button (pitfalls §9),
 * contrast kept high so it stays readable on bright cloud artwork.
 */
fun mutedCrestBrush(): Brush = Brush.verticalGradient(
    colors = listOf(
        Color(0xFFD4A24A),
        Color(0xFF8B3A1A),
        Color(0xFF5A1E10),
    ),
)

/**
 * Label with a soft dark shadow so it reads against the button
 * gradient regardless of the background artwork.
 */
val VoltButtonTextStyle: TextStyle = TextStyle(
    color = Color.White,
    fontSize = 18.sp,
    fontWeight = FontWeight.Bold,
    lineHeight = 18.sp,   // guards against baseline drift (pitfalls §10)
    letterSpacing = 0.4.sp,
    shadow = Shadow(
        color = Color(0x99001730),
        offset = Offset(0f, 2f),
        blurRadius = 4f,
    ),
)

@Composable
fun VoltSpinnerOverlay(size: Dp = 32.dp) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size + 24.dp)
                .background(Color(0x66000000), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(size),
                color = Color.White,
                strokeWidth = 3.dp,
            )
        }
    }
}

@Composable
fun VoltLoadingCaption() {
    val phase by com.thundercrest.thundercrestgame.volt.ui.tick.rememberVoltDots()
    val dots = when (phase) { 0 -> "" ; 1 -> "." ; 2 -> ". ." ; else -> ". . ." }
    Text(
        text = "Loading $dots",
        style = TextStyle(
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp,
            shadow = Shadow(
                color = Color(0xB3000000),
                offset = Offset(0f, 2f),
                blurRadius = 6f,
            ),
        ),
    )
}
