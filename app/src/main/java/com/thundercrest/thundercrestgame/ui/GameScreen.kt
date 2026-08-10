package com.thundercrest.thundercrestgame.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thundercrest.thundercrestgame.core.Art
import com.thundercrest.thundercrestgame.core.Audio
import com.thundercrest.thundercrestgame.core.GameStore
import com.thundercrest.thundercrestgame.core.Sfx
import com.thundercrest.thundercrestgame.game.Dir
import com.thundercrest.thundercrestgame.game.GemColor
import com.thundercrest.thundercrestgame.game.Kind
import com.thundercrest.thundercrestgame.game.LEVELS_PER_CHAPTER
import com.thundercrest.thundercrestgame.game.Level
import com.thundercrest.thundercrestgame.game.RState
import com.thundercrest.thundercrestgame.game.Sim
import com.thundercrest.thundercrestgame.game.T
import com.thundercrest.thundercrestgame.game.LevelRepository
import kotlin.math.min

private enum class Status { PLAYING, WON, FAILED }

@Composable
fun GameScreen(
    chapter: Int,
    index: Int,
    onExit: () -> Unit,
    onNext: (Int, Int) -> Unit,
    onLevels: (Int) -> Unit,
) {
    val level = remember(chapter, index) { LevelRepository.get(chapter, index) }
    val sim = remember(level) { Sim(level) }
    var state by remember(level) { mutableStateOf(sim.newState()) }
    var moves by remember(level) { mutableIntStateOf(0) }
    var status by remember(level) { mutableStateOf(Status.PLAYING) }
    var earned by remember(level) { mutableIntStateOf(0) }
    var paused by remember(level) { mutableStateOf(false) }

    LaunchedEffect(level) { Audio.play(Sfx.LEVEL_START) }

    fun rotate(cw: Boolean) {
        if (status != Status.PLAYING || paused) return
        val prev = state
        val ns = sim.rotate(prev, cw)
        Audio.play(Sfx.ROTATE)
        Audio.vibrateTick()
        val prevLocked = prev.locked.count { it }
        val newLocked = ns.locked.count { it }
        if (newLocked > prevLocked) Audio.play(Sfx.GEM_PLACE)
        if (ns.nodes != prev.nodes) Audio.play(Sfx.NODE)
        state = ns
        moves++
        if (sim.isWin(ns)) {
            status = Status.WON
            earned = level.starsFor(moves)
            GameStore.recordResult(level.id, earned)
            Audio.play(Sfx.LEVEL_COMPLETE)
        } else if (sim.solve(ns, maxDepth = 14) == null) {
            status = Status.FAILED
        }
    }

    fun restart() {
        state = sim.newState()
        moves = 0
        status = Status.PLAYING
        earned = 0
        paused = false
        Audio.play(Sfx.LEVEL_START)
    }

    OlympusBackground(Art.arenaBg, dim = 0.45f) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            GameHud(level, moves, onPause = { paused = true }, onRestart = { restart() })
            Spacer(Modifier.height(6.dp))
            ObjectiveRow(level, state, sim)
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Board(level, state)
            }
            RotateControls(enabled = status == Status.PLAYING && !paused, onRotate = { rotate(it) })
            Spacer(Modifier.height(6.dp))
        }

        if (paused) {
            PauseOverlay(
                onResume = { paused = false },
                onRestart = { restart() },
                onExit = onExit,
            )
        }
        if (status == Status.WON) {
            VictoryOverlay(
                level = level, moves = moves, stars = earned,
                hasNext = index + 1 < LEVELS_PER_CHAPTER,
                onReplay = { restart() },
                onNext = { onNext(chapter, index + 1) },
                onLevels = { onLevels(chapter) },
            )
        }
        if (status == Status.FAILED) {
            FailOverlay(onRetry = { restart() }, onLevels = { onLevels(chapter) })
        }
    }
}

@Composable
private fun GameHud(level: Level, moves: Int, onPause: () -> Unit, onRestart: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HudButton("\u2016") { onPause() }
        Spacer(Modifier.size(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Level ${level.chapter}-${level.indexInChapter + 1}",
                color = Color.White, fontFamily = Cinzel, fontWeight = FontWeight.Bold, fontSize = 18.sp,
            )
            Text(
                "Moves $moves   \u2605 par ${level.par}",
                color = Palette.sky, fontFamily = Marcellus, fontSize = 13.sp,
            )
        }
        HudButton("\u21BA") { onRestart() }
    }
}

@Composable
private fun HudButton(glyph: String, onClick: () -> Unit) {
    Box(
        Modifier.size(46.dp).clip(RoundedCornerShape(12.dp))
            .background(Palette.deepBlue.copy(alpha = 0.85f))
            .border(2.dp, Palette.gold, RoundedCornerShape(12.dp))
            .then(clickable { onClick() }),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = Palette.goldLight, fontFamily = Cinzel, fontWeight = FontWeight.Bold, fontSize = 20.sp)
    }
}

@Composable
private fun ObjectiveRow(level: Level, state: RState, sim: Sim) {
    val remaining = HashMap<GemColor, Int>()
    level.pieces.forEachIndexed { i, p ->
        if (p.kind == Kind.GEM && p.color != null && !state.locked[i]) {
            remaining[p.color] = (remaining[p.color] ?: 0) + 1
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (color in GemColor.entries) {
            val rem = remaining[color] ?: continue
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(20.dp).clip(CircleShape).background(colorOf(color)))
                Spacer(Modifier.size(4.dp))
                Text("x$rem", color = Color.White, fontFamily = Cinzel, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
        if (level.requireNodes) {
            val total = java.lang.Long.bitCount(level.nodeMask)
            val lit = java.lang.Long.bitCount(state.nodes and level.nodeMask)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("\u26A1", fontSize = 16.sp, color = Palette.cyan)
                Text(" $lit/$total", color = Color.White, fontFamily = Cinzel, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
}

private fun colorOf(c: GemColor): Color = when (c) {
    GemColor.RUBY -> Palette.ruby
    GemColor.SAPPHIRE -> Palette.sapphire
    GemColor.EMERALD -> Palette.emerald
    GemColor.AMETHYST -> Palette.amethyst
}

@Composable
private fun Board(level: Level, state: RState) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val boardDp = min(maxWidth.value, maxHeight.value).dp
        val boardPx = with(density) { boardDp.toPx() }
        val n = level.w
        val cell = boardPx / n
        val angle by animateFloatAsState(state.turns * 90f, tween(420), label = "angle")

        // Animated pixel positions for every movable piece.
        val positions = ArrayList<Offset>(level.pieces.size)
        for (i in level.pieces.indices) {
            val target = Offset((state.cols[i] + 0.5f) * cell, (state.rows[i] + 0.5f) * cell)
            val p by animateOffsetAsState(target, tween(380), label = "p$i")
            positions.add(p)
        }

        Box(Modifier.size(boardDp).rotate(angle)) {
            Canvas(Modifier.fillMaxSize()) {
                drawBoard(level, state, cell)
                drawPieces(level, state, positions, cell)
            }
        }
    }
}

private fun DrawScope.drawBoard(level: Level, state: RState, cell: Float) {
    val n = level.w
    val full = cell * n
    // Marble platform base.
    drawRoundRect(
        brush = Brush.linearGradient(listOf(Color(0xFFF3EFE4), Color(0xFFCFC7B4))),
        size = androidx.compose.ui.geometry.Size(full, full),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cell * 0.35f),
    )
    // Gold outer frame.
    drawRoundRect(
        color = Palette.gold,
        size = androidx.compose.ui.geometry.Size(full, full),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cell * 0.35f),
        style = Stroke(width = cell * 0.10f),
    )
    // Grid lines.
    for (i in 1 until n) {
        val p = i * cell
        drawLine(Color(0x33654F1F), Offset(p, 0f), Offset(p, full), strokeWidth = 1.5f)
        drawLine(Color(0x33654F1F), Offset(0f, p), Offset(full, p), strokeWidth = 1.5f)
    }

    for (row in 0 until level.h) {
        for (col in 0 until n) {
            val idx = row * n + col
            val cx = (col + 0.5f) * cell
            val cy = (row + 0.5f) * cell
            when (level.tCode[idx]) {
                T.VOID -> drawRoundRect(
                    color = Color(0xCC0A1024),
                    topLeft = Offset(col * cell + 2f, row * cell + 2f),
                    size = androidx.compose.ui.geometry.Size(cell - 4f, cell - 4f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cell * 0.15f),
                )
                T.WALL -> drawSprite(Art.wallBlock, cx, cy, cell * 0.98f)
                T.SLOT -> {
                    val gc = level.slotColor[idx]
                    val c = if (gc >= 0) colorOf(GemColor.entries[gc]) else Palette.gold
                    drawCircle(c.copy(alpha = 0.28f), radius = cell * 0.4f, center = Offset(cx, cy))
                    drawCircle(Palette.gold, radius = cell * 0.4f, center = Offset(cx, cy), style = Stroke(cell * 0.06f))
                    drawCircle(c.copy(alpha = 0.5f), radius = cell * 0.18f, center = Offset(cx, cy), style = Stroke(cell * 0.04f))
                }
                T.NODE -> {
                    val lit = (state.nodes ushr idx and 1L) == 1L
                    drawCircle(
                        if (lit) Palette.cyan.copy(alpha = 0.35f) else Color(0x33203050),
                        radius = cell * 0.44f, center = Offset(cx, cy),
                    )
                    drawSprite(Art.node, cx, cy, cell * 0.9f, alpha = if (lit) 1f else 0.6f)
                }
                T.ONEWAY -> {
                    drawRoundRect(
                        color = Color(0x22FFFFFF),
                        topLeft = Offset(col * cell + cell * 0.1f, row * cell + cell * 0.1f),
                        size = androidx.compose.ui.geometry.Size(cell * 0.8f, cell * 0.8f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cell * 0.12f),
                    )
                    drawArrow(cx, cy, cell, Dir.entries[level.oneDir[idx]])
                }
                T.PORTAL -> {
                    drawCircle(Palette.cyan.copy(alpha = 0.3f), radius = cell * 0.42f, center = Offset(cx, cy))
                    drawSprite(Art.portal, cx, cy, cell * 0.92f)
                }
            }
        }
    }
}

private fun DrawScope.drawPieces(level: Level, state: RState, positions: List<Offset>, cell: Float) {
    level.pieces.forEachIndexed { i, p ->
        val pos = positions[i]
        if (p.kind == Kind.BLOCK) {
            drawSprite(Art.movingBlock, pos.x, pos.y, cell * 0.92f)
        } else if (p.color != null) {
            if (state.locked[i]) {
                drawCircle(colorOf(p.color).copy(alpha = 0.45f), radius = cell * 0.46f, center = pos)
                drawCircle(Palette.goldLight.copy(alpha = 0.8f), radius = cell * 0.46f, center = pos, style = Stroke(cell * 0.05f))
            }
            drawSprite(Art.gem(p.color), pos.x, pos.y, cell * 0.82f)
        }
    }
}

private fun DrawScope.drawSprite(img: ImageBitmap, cx: Float, cy: Float, target: Float, alpha: Float = 1f) {
    val ar = img.width.toFloat() / img.height.toFloat()
    val w: Float
    val h: Float
    if (ar >= 1f) { w = target; h = target / ar } else { h = target; w = target * ar }
    drawImage(
        image = img,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(img.width, img.height),
        dstOffset = IntOffset((cx - w / 2f).toInt(), (cy - h / 2f).toInt()),
        dstSize = IntSize(w.toInt(), h.toInt()),
        alpha = alpha,
    )
}

private fun DrawScope.drawArrow(cx: Float, cy: Float, cell: Float, dir: Dir) {
    val len = cell * 0.28f
    val end = Offset(cx + dir.dx * len, cy + dir.dy * len)
    val start = Offset(cx - dir.dx * len, cy - dir.dy * len)
    drawLine(Palette.goldDark, start, end, strokeWidth = cell * 0.09f)
    // Arrowhead.
    val hx = dir.dx * len
    val hy = dir.dy * len
    val perpX = -dir.dy * len * 0.5f
    val perpY = dir.dx * len * 0.5f
    drawLine(Palette.goldDark, end, Offset(cx + hx * 0.4f + perpX, cy + hy * 0.4f + perpY), strokeWidth = cell * 0.09f)
    drawLine(Palette.goldDark, end, Offset(cx + hx * 0.4f - perpX, cy + hy * 0.4f - perpY), strokeWidth = cell * 0.09f)
}

@Composable
private fun RotateControls(enabled: Boolean, onRotate: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(30.dp, Alignment.CenterHorizontally),
    ) {
        RotateButton(cw = false, enabled = enabled) { onRotate(false) }
        RotateButton(cw = true, enabled = enabled) { onRotate(true) }
    }
}

@Composable
private fun RotateButton(cw: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(76.dp).clip(CircleShape)
                .background(if (enabled) goldBrush else Brush.verticalGradient(listOf(Color(0xFF4A5470), Color(0xFF313A52))))
                .border(3.dp, Palette.goldLight.copy(alpha = if (enabled) 0.9f else 0.3f), CircleShape)
                .then(clickable(enabled = enabled) { onClick() }),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (cw) "\u21BB" else "\u21BA",
                color = Palette.night, fontFamily = Cinzel, fontWeight = FontWeight.Black, fontSize = 40.sp,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (cw) "Rotate \u2192" else "\u2190 Rotate",
            color = Palette.goldLight, fontFamily = Cinzel, fontWeight = FontWeight.Bold, fontSize = 12.sp,
        )
    }
}

@Composable
private fun Overlay(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Panel(Modifier.fillMaxWidth(0.86f)) { content() }
    }
}

@Composable
private fun PauseOverlay(onResume: () -> Unit, onRestart: () -> Unit, onExit: () -> Unit) {
    Overlay {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Paused", color = Palette.goldLight, fontFamily = Cinzel, fontWeight = FontWeight.Black, fontSize = 26.sp)
            Spacer(Modifier.height(20.dp))
            GoldButton("Continue", Modifier.fillMaxWidth()) { onResume() }
            Spacer(Modifier.height(12.dp))
            GoldButton("Restart", Modifier.fillMaxWidth()) { onRestart() }
            Spacer(Modifier.height(12.dp))
            GoldButton("Exit", Modifier.fillMaxWidth()) { onExit() }
        }
    }
}

@Composable
private fun VictoryOverlay(
    level: Level,
    moves: Int,
    stars: Int,
    hasNext: Boolean,
    onReplay: () -> Unit,
    onNext: () -> Unit,
    onLevels: () -> Unit,
) {
    Overlay {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Victory!", color = Palette.goldLight, fontFamily = Cinzel, fontWeight = FontWeight.Black, fontSize = 30.sp)
            Spacer(Modifier.height(14.dp))
            StarsRow(count = stars, starSize = 44)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Stat("Moves", "$moves")
                Stat("Par", "${level.par}")
            }
            Spacer(Modifier.height(18.dp))
            if (hasNext) {
                GoldButton("Next Level", Modifier.fillMaxWidth()) { onNext() }
                Spacer(Modifier.height(12.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GoldButton("Replay", Modifier.weight(1f)) { onReplay() }
                GoldButton("Levels", Modifier.weight(1f)) { onLevels() }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontFamily = Cinzel, fontWeight = FontWeight.Black, fontSize = 26.sp)
        Text(label, color = Palette.sky, fontFamily = Marcellus, fontSize = 13.sp)
    }
}

@Composable
private fun FailOverlay(onRetry: () -> Unit, onLevels: () -> Unit) {
    Overlay {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Path Blocked", color = Palette.ruby, fontFamily = Cinzel, fontWeight = FontWeight.Black, fontSize = 26.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                "The gems can no longer reach their sacred nests. Try a different sequence of rotations.",
                color = Palette.textLight, fontFamily = Marcellus, fontSize = 14.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            GoldButton("Retry", Modifier.fillMaxWidth()) { onRetry() }
            Spacer(Modifier.height(12.dp))
            GoldButton("Level Select", Modifier.fillMaxWidth()) { onLevels() }
        }
    }
}
