package com.thundercrest.thundercrestgame.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** All destinations in the app. */
sealed interface Nav {
    data object Menu : Nav
    data object Map : Nav
    data class Levels(val chapter: Int) : Nav
    data class Play(val chapter: Int, val index: Int) : Nav
    data object Crest : Nav
    data object Collection : Nav
    data object Settings : Nav
}

@Composable
fun App(setPortraitLock: (Boolean) -> Unit) {
    // Nav.Loading is intentionally gone — the gray-part splash in
    // LauncherActivity is now the ONLY user-visible loading UI. Asset
    // preloading / level generation happens invisibly in MainActivity
    // before this composable is mounted (see MainActivity.onCreate).
    var stack by remember { mutableStateOf(listOf<Nav>(Nav.Menu)) }
    val current = stack.last()

    fun push(n: Nav) { stack = stack + n }
    fun replace(n: Nav) { stack = stack.dropLast(1) + n }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }

    // Menu and every other screen are portrait-only.
    setPortraitLock(true)

    BackHandler(enabled = stack.size > 1) { pop() }

    Crossfade(targetState = current, animationSpec = tween(350), label = "nav") { screen ->
        when (screen) {
            Nav.Menu -> MenuScreen(
                onPlay = { push(Nav.Map) },
                onSettings = { push(Nav.Settings) },
                onCollection = { push(Nav.Collection) },
                onCrest = { push(Nav.Crest) },
            )
            Nav.Map -> MapScreen(
                onBack = { pop() },
                onChapter = { chapter -> push(Nav.Levels(chapter)) },
                onCrest = { push(Nav.Crest) },
            )
            is Nav.Levels -> LevelSelectScreen(
                chapter = screen.chapter,
                onBack = { pop() },
                onLevel = { index -> push(Nav.Play(screen.chapter, index)) },
            )
            is Nav.Play -> GameScreen(
                chapter = screen.chapter,
                index = screen.index,
                onExit = { pop() },
                onNext = { c, i -> replace(Nav.Play(c, i)) },
                onLevels = { c -> stack = listOf(Nav.Menu, Nav.Map, Nav.Levels(c)) },
            )
            Nav.Crest -> CrestScreen(onBack = { pop() })
            Nav.Collection -> CollectionScreen(onBack = { pop() })
            Nav.Settings -> SettingsScreen(onBack = { pop() })
        }
    }
}
