package com.thundercrest.thundercrestgame.core

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.thundercrest.thundercrestgame.game.GemColor

/**
 * Central image loader. Decodes WebP art from the APK's assets folder and caches
 * the resulting [ImageBitmap]s. Everything is bundled, so it works fully offline.
 */
object Art {
    private lateinit var assets: AssetManager
    private val cache = HashMap<String, ImageBitmap>()

    fun init(am: AssetManager) {
        assets = am
    }

    fun load(path: String): ImageBitmap {
        cache[path]?.let { return it }
        val bmp = assets.open(path).use { BitmapFactory.decodeStream(it) }
        val img = bmp.asImageBitmap()
        cache[path] = img
        return img
    }

    fun sprite(name: String): ImageBitmap = load("sprites/$name.webp")
    fun bg(name: String): ImageBitmap = load("bg/$name.webp")

    // ---- Named art used across the game -------------------------------------

    fun gem(color: GemColor): ImageBitmap = sprite(
        when (color) {
            GemColor.RUBY -> "Ruby_Sapphire_Emerald_Amethyst_Set_1"
            GemColor.SAPPHIRE -> "Ruby_Sapphire_Emerald_Amethyst_Set_2"
            GemColor.EMERALD -> "Ruby_Sapphire_Emerald_Amethyst_Set_3"
            GemColor.AMETHYST -> "Ruby_Sapphire_Emerald_Amethyst_Set_4"
        }
    )

    val node get() = sprite("Thunder_Nodes_Set_1")
    val movingBlock get() = sprite("Moving_Blocks_Set_1")
    val wallBlock get() = sprite("Stone_Blocks_Set_2")
    val portal get() = sprite("Stone_Portal")

    val gameName get() = sprite("Game_Name")
    val crest get() = sprite("Great_Thunder_Crest")
    val crestFragment get() = sprite("Thunder_Crest_Fragment")
    val zeus get() = sprite("Zeus_Main_Character")
    val laurel get() = sprite("Golden_Laurel_Wreath")
    val emblem get() = sprite("Central_Zeus_Emblem")
    val altar get() = sprite("Sacred_Olympus_Altar")
    val skyIsland get() = sprite("Sky_Island")
    val finalCrystal get() = sprite("Final_Olympus_Crystal")
    val energySphere get() = sprite("Sacred_Energy_Sphere")
    val bowl get() = sprite("Golden_Olympus_Bowl")
    val artifactFragment get() = sprite("Golden_Artifact_Fragment")
    val thunderCrystal get() = sprite("Thunder_Crystal")

    // Loading + menu backgrounds.
    val loadingPortrait get() = bg("Aegis_Portrait_Boot")
    val loadingLandscape get() = bg("Aegis_Landscape_Boot")
    val menuBg get() = bg("Olympus_Background_asset")
    val mapBg get() = bg("Cloud_Island_Background_asset")
    val arenaBg get() = bg("Celestial_Olympus_Arena_Background_asset")
    val victoryBg get() = bg("Zeus_Sanctuary_Background_asset")
    val templeBg get() = bg("Golden_Temple_Background_asset")
    val stormyBg get() = bg("Stormy_Olympus_Sky_Background_asset")

    /** Paths eagerly decoded during the loading screen so the bar tracks real work. */
    val preloadPaths: List<String> = listOf(
        "bg/Aegis_Portrait_Boot.webp",
        "bg/Aegis_Landscape_Boot.webp",
        "bg/Olympus_Background_asset.webp",
        "bg/Cloud_Island_Background_asset.webp",
        "bg/Celestial_Olympus_Arena_Background_asset.webp",
        "bg/Zeus_Sanctuary_Background_asset.webp",
        "bg/Golden_Temple_Background_asset.webp",
        "bg/Stormy_Olympus_Sky_Background_asset.webp",
        "sprites/Game_Name.webp",
        "sprites/Great_Thunder_Crest.webp",
        "sprites/Thunder_Crest_Fragment.webp",
        "sprites/Zeus_Main_Character.webp",
        "sprites/Golden_Laurel_Wreath.webp",
        "sprites/Central_Zeus_Emblem.webp",
        "sprites/Sacred_Olympus_Altar.webp",
        "sprites/Sky_Island.webp",
        "sprites/Final_Olympus_Crystal.webp",
        "sprites/Sacred_Energy_Sphere.webp",
        "sprites/Golden_Olympus_Bowl.webp",
        "sprites/Golden_Artifact_Fragment.webp",
        "sprites/Thunder_Crystal.webp",
        "sprites/Ruby_Sapphire_Emerald_Amethyst_Set_1.webp",
        "sprites/Ruby_Sapphire_Emerald_Amethyst_Set_2.webp",
        "sprites/Ruby_Sapphire_Emerald_Amethyst_Set_3.webp",
        "sprites/Ruby_Sapphire_Emerald_Amethyst_Set_4.webp",
        "sprites/Thunder_Nodes_Set_1.webp",
        "sprites/Moving_Blocks_Set_1.webp",
        "sprites/Stone_Blocks_Set_2.webp",
        "sprites/Stone_Portal.webp",
    )
}
