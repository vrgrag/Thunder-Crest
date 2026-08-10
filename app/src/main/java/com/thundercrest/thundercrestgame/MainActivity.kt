package com.thundercrest.thundercrestgame

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.thundercrest.thundercrestgame.core.Art
import com.thundercrest.thundercrestgame.core.Audio
import com.thundercrest.thundercrestgame.core.GameStore
import com.thundercrest.thundercrestgame.ui.App
import com.thundercrest.thundercrestgame.ui.ThunderCrestTheme

class MainActivity : ComponentActivity() {

    private var portraitLocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        GameStore.init(this)
        Art.init(assets)
        Audio.init(this)

        setContent {
            ThunderCrestTheme {
                App(setPortraitLock = { locked -> setPortraitLock(locked) })
            }
        }
    }

    private fun setPortraitLock(locked: Boolean) {
        if (locked == portraitLocked) return
        portraitLocked = locked
        requestedOrientation = if (locked) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }
    }

    override fun onResume() {
        super.onResume()
        Audio.startMusic()
    }

    override fun onPause() {
        super.onPause()
        Audio.pauseMusic()
    }
}
