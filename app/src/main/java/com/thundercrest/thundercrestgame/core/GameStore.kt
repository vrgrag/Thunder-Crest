package com.thundercrest.thundercrestgame.core

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.thundercrest.thundercrestgame.game.CHAPTER_COUNT
import com.thundercrest.thundercrestgame.game.LEVELS_PER_CHAPTER

/**
 * Persistent player progress and settings, backed by SharedPreferences. Exposes
 * Compose state so screens recompose when values change. Fully local (offline).
 */
object GameStore {
    private lateinit var prefs: SharedPreferences

    val soundVolume = mutableStateOf(0.8f)
    val musicVolume = mutableStateOf(0.6f)
    val vibrate = mutableStateOf(true)
    val graphics = mutableStateOf(2) // 0 low, 1 medium, 2 high
    val language = mutableStateOf(0) // 0 English (only)

    /** levelId -> stars (1..3). Absent = not completed. */
    val stars: SnapshotStateMap<Int, Int> = mutableStateMapOf()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("thunder_crest", Context.MODE_PRIVATE)
        soundVolume.value = prefs.getFloat("sound", 0.8f)
        musicVolume.value = prefs.getFloat("music", 0.6f)
        vibrate.value = prefs.getBoolean("vibrate", true)
        graphics.value = prefs.getInt("graphics", 2)
        val total = CHAPTER_COUNT * LEVELS_PER_CHAPTER
        for (id in 0 until total) {
            val s = prefs.getInt("stars_$id", 0)
            if (s > 0) stars[id] = s
        }
    }

    fun setSound(v: Float) {
        soundVolume.value = v
        prefs.edit().putFloat("sound", v).apply()
    }

    fun setMusic(v: Float) {
        musicVolume.value = v
        prefs.edit().putFloat("music", v).apply()
        Audio.applyMusicVolume()
    }

    fun setVibrate(v: Boolean) {
        vibrate.value = v
        prefs.edit().putBoolean("vibrate", v).apply()
    }

    fun setGraphics(v: Int) {
        graphics.value = v
        prefs.edit().putInt("graphics", v).apply()
    }

    fun recordResult(levelId: Int, earned: Int) {
        val prev = stars[levelId] ?: 0
        if (earned > prev) {
            stars[levelId] = earned
            prefs.edit().putInt("stars_$levelId", earned).apply()
        } else if (prev == 0 && earned == 0) {
            // Mark as attempted-complete with at least 1 star.
            stars[levelId] = 1
            prefs.edit().putInt("stars_$levelId", 1).apply()
        }
    }

    fun isCompleted(levelId: Int): Boolean = (stars[levelId] ?: 0) > 0

    fun starsFor(levelId: Int): Int = stars[levelId] ?: 0

    /** First level is always open; each subsequent unlocks when the previous is done. */
    fun isUnlocked(chapter: Int, indexInChapter: Int): Boolean {
        val id = (chapter - 1) * LEVELS_PER_CHAPTER + indexInChapter
        if (id == 0) return true
        return isCompleted(id - 1)
    }

    fun chapterUnlocked(chapter: Int): Boolean {
        if (chapter <= 1) return true
        val prevLast = (chapter - 1) * LEVELS_PER_CHAPTER - 1
        return isCompleted(prevLast)
    }

    fun totalStars(): Int = stars.values.sum()

    fun completedCount(): Int = stars.size

    fun chapterStars(chapter: Int): Int {
        var sum = 0
        for (i in 0 until LEVELS_PER_CHAPTER) {
            sum += starsFor((chapter - 1) * LEVELS_PER_CHAPTER + i)
        }
        return sum
    }

    /** Crest fragments recovered (0..12), one per completed level up to 12. */
    fun crestFragments(): Int = completedCount().coerceAtMost(12)
}
