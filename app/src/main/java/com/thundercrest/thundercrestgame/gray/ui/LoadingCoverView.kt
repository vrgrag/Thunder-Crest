package com.thundercrest.thundercrestgame.gray.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp

/**
 * A full-screen, opaque loading cover used by [com.thundercrest.thundercrestgame
 * .WebLinkActivity] to hide the WebView's native error page during redirect
 * recovery and between page navigations. The background is intentionally
 * opaque so no green-robot / white-flash ever peeks through.
 */
fun LoadingCoverView(ctx: Context): ComposeView = ComposeView(ctx).apply {
    setContent { LoadingCover() }
}

@Composable
private fun LoadingCover() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000)),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(56.dp),
            color = Color(0xFFE6B84D),
            strokeWidth = 4.dp,
        )
    }
}
