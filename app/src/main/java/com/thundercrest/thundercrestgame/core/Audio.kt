package com.thundercrest.thundercrestgame.core

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** All sound effects, keyed by a short name. */
enum class Sfx(val file: String) {
    CLICK("Button_Click_asset.mp3"),
    MENU_OPEN("Menu_Open_asset.mp3"),
    MENU_CLOSE("Menu_Close_asset.mp3"),
    REWARD("Reward_Collection_asset.mp3"),
    GEM_MOVE("Gemstone_Movement_asset.mp3"),
    GEM_PLACE("Gemstone_Placement_asset.mp3"),
    ROTATE("Platform_Rotation_asset.mp3"),
    NODE("Thunder_Node_Activation_asset.mp3"),
    CHAIN("Lightning_Chain_Reaction_asset.mp3"),
    BARRIER("Barrier_Destroy_asset.mp3"),
    PORTAL("Portal_Activation_asset.mp3"),
    ALTAR("Altar_Activation_asset.mp3"),
    LEVEL_COMPLETE("Level_Complete_asset.mp3"),
    LEVEL_START("Level_Start_asset.mp3"),
}

/**
 * Owns a [SoundPool] for short SFX and a looping [MediaPlayer] for ambient music.
 * Volumes are read live from [GameStore] so the settings sliders take effect at once.
 */
object Audio {
    private lateinit var pool: SoundPool
    private val ids = HashMap<Sfx, Int>()
    private var loaded = false
    private var music: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        pool = SoundPool.Builder().setMaxStreams(6).setAudioAttributes(attrs).build()
        val am = appContext.assets
        for (sfx in Sfx.entries) {
            val afd = am.openFd("audio/${sfx.file}")
            ids[sfx] = pool.load(afd, 1)
            afd.close()
        }
        loaded = true
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    fun play(sfx: Sfx) {
        if (!loaded) return
        val v = GameStore.soundVolume.value
        if (v <= 0f) return
        ids[sfx]?.let { pool.play(it, v, v, 1, 0, 1f) }
    }

    fun vibrateTick() {
        if (!GameStore.vibrate.value) return
        val vib = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(20)
            }
        } catch (_: Throwable) {
            // Vibration is a non-essential nicety; never let it crash gameplay.
        }
    }

    fun startMusic() {
        val vol = GameStore.musicVolume.value
        if (music == null) {
            try {
                val afd = appContext.assets.openFd("audio/${Sfx.CHAIN.file}")
                // Ambient storm loop.
                val ambient = appContext.assets.openFd("audio/Thunder_Storm_Ambient_asset.mp3")
                afd.close()
                music = MediaPlayer().apply {
                    setDataSource(ambient.fileDescriptor, ambient.startOffset, ambient.length)
                    ambient.close()
                    isLooping = true
                    prepare()
                }
            } catch (_: Throwable) {
                music = null
            }
        }
        music?.setVolume(vol, vol)
        if (vol > 0f) music?.let { if (!it.isPlaying) it.start() }
    }

    fun applyMusicVolume() {
        val vol = GameStore.musicVolume.value
        music?.setVolume(vol, vol)
        if (vol <= 0f) music?.pause() else music?.let { if (!it.isPlaying) it.start() }
    }

    fun pauseMusic() {
        music?.pause()
    }
}
