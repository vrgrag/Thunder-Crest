package com.thundercrest.thundercrestgame.gray.ui

import android.content.Context
import androidx.compose.ui.platform.ComposeView
import com.thundercrest.thundercrestgame.ui.ThunderCrestTheme

/**
 * Creates a [ComposeView] that renders [NoWifiScreen], suitable for insertion
 * into a plain Android View hierarchy (e.g. in [WebLinkActivity]).
 */
fun OfflineView(ctx: Context, onRetry: () -> Unit): ComposeView =
    ComposeView(ctx).apply {
        setContent {
            ThunderCrestTheme {
                NoWifiScreen(onRetry = onRetry)
            }
        }
    }
