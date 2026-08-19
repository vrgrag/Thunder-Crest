package com.thundercrest.thundercrestgame

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.thundercrest.thundercrestgame.core.Art
import com.thundercrest.thundercrestgame.core.Audio
import com.thundercrest.thundercrestgame.core.GameStore
import com.thundercrest.thundercrestgame.game.LevelRepository
import com.thundercrest.thundercrestgame.ui.App
import com.thundercrest.thundercrestgame.ui.ThunderCrestTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var portraitLocked = false

    // Flip to true only after every bundled bitmap has been decoded and
    // every level has been generated on a background thread. Until then
    // the activity intentionally renders nothing — the gray-part splash
    // in LauncherActivity is the single user-visible loading UI, and any
    // second progress bar (the old in-game "Loading..." screen) is gone.
    private var gameReady by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        GameStore.init(this)
        Art.init(assets)
        Audio.init(this)

        // Empty setContent up front so the activity has a compose root
        // (no black window flash). Content only appears once preload
        // finishes, which usually happens well within the cross-activity
        // animation window (~300 ms on modern devices).
        setContent {
            ThunderCrestTheme {
                if (gameReady) {
                    App(setPortraitLock = { locked -> setPortraitLock(locked) })
                }
            }
        }

        // Preload every bundled image + generate every level off the main
        // thread. This is the exact work the old in-game LoadingScreen used
        // to do, minus the visible progress bar the user complained about.
        lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                for (p in Art.preloadPaths) runCatching { Art.load(p) }
                for (i in 0 until LevelRepository.totalLevels) {
                    runCatching { LevelRepository.generateAt(i) }
                }
            }
            gameReady = true
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
