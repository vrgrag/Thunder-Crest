package com.thundercrest.thundercrestgame.gray

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Loads the gray-part background images (loading / notifications / no-wifi in
 * two orientations) from `assets/gray/` and caches them.
 */
object GrayArt {
    private val cache = HashMap<String, ImageBitmap>()

    fun load(ctx: Context, name: String): ImageBitmap {
        cache[name]?.let { return it }
        val bmp = ctx.assets.open("gray/$name").use { BitmapFactory.decodeStream(it) }
        return bmp.asImageBitmap().also { cache[name] = it }
    }

    fun loading(ctx: Context, landscape: Boolean): ImageBitmap =
        load(ctx, if (landscape) "Aegis_Landscape_Boot.webp" else "Aegis_Portrait_Boot.webp")

    fun notif(ctx: Context, landscape: Boolean): ImageBitmap =
        load(ctx, if (landscape) "Herald_Landscape_Bell.webp" else "Herald_Portrait_Bell.webp")

    fun noWifi(ctx: Context, landscape: Boolean): ImageBitmap =
        load(ctx, if (landscape) "Tempest_Landscape_Void.webp" else "Tempest_Portrait_Void.webp")
}
