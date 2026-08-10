package com.thundercrest.thundercrestgame.game

import kotlin.random.Random

/** The four gem types described in the design document. */
enum class GemColor(val key: String) {
    RUBY("ruby"), SAPPHIRE("sapphire"), EMERALD("emerald"), AMETHYST("amethyst")
}

/** Gravity / movement directions. */
enum class Dir(val dx: Int, val dy: Int) {
    UP(0, -1), DOWN(0, 1), LEFT(-1, 0), RIGHT(1, 0);

    fun cw(): Dir = when (this) { UP -> RIGHT; RIGHT -> DOWN; DOWN -> LEFT; LEFT -> UP }
    fun ccw(): Dir = when (this) { UP -> LEFT; LEFT -> DOWN; DOWN -> RIGHT; RIGHT -> UP }
}

// Terrain codes stored per cell.
object T {
    const val FLOOR = 0
    const val WALL = 1
    const val VOID = 2      // hole in the platform, blocks movement
    const val SLOT = 3      // sacred nest (needs matching slotColor)
    const val NODE = 4      // thunder node, activated when a gem passes it
    const val ONEWAY = 5    // passable only in oneDir direction
    const val PORTAL = 6    // teleports to its paired portal
}

enum class Kind { GEM, BLOCK }

/** A movable object placed on the board (gem or stone block). */
data class Piece(val kind: Kind, val color: GemColor?, val col: Int, val row: Int)

/**
 * A fully described, pre-validated puzzle. All terrain is immutable; only the
 * runtime [RState] moves pieces around.
 */
class Level(
    val id: Int,
    val chapter: Int,
    val indexInChapter: Int,
    val w: Int,
    val h: Int,
    val tCode: IntArray,
    val slotColor: IntArray,
    val oneDir: IntArray,
    val portalExit: IntArray,
    val nodeMask: Long,
    val requireNodes: Boolean,
    val pieces: List<Piece>,
    val par: Int,
) {
    val star3 = par
    val star2 = par + 2

    fun starsFor(moves: Int): Int = when {
        moves <= star3 -> 3
        moves <= star2 -> 2
        else -> 1
    }

    fun cell(col: Int, row: Int) = row * w + col
}

/** Mutable runtime state: piece positions + which nodes are lit + platform turns. */
class RState(
    val cols: IntArray,
    val rows: IntArray,
    val locked: BooleanArray,
    var nodes: Long,
    var turns: Int,
) {
    fun copy() = RState(cols.copyOf(), rows.copyOf(), locked.copyOf(), nodes, turns)
}

/**
 * The physics + search engine for a single [Level]. Same code drives gameplay,
 * the dead-end detector and the offline level generator, so a level that the
 * solver accepts is guaranteed playable.
 */
class Sim(val level: Level) {
    private val w = level.w
    private val h = level.h
    private val n = level.pieces.size
    private val kinds = IntArray(n) { if (level.pieces[it].kind == Kind.GEM) 0 else 1 }
    private val colors = IntArray(n) { level.pieces[it].color?.ordinal ?: -1 }

    fun newState(): RState {
        val cols = IntArray(n) { level.pieces[it].col }
        val rows = IntArray(n) { level.pieces[it].row }
        val locked = BooleanArray(n)
        val s = RState(cols, rows, locked, 0L, 0)
        settle(s)
        return s
    }

    fun gravityFor(turns: Int): Dir {
        val g = arrayOf(Dir.DOWN, Dir.RIGHT, Dir.UP, Dir.LEFT)
        return g[((turns % 4) + 4) % 4]
    }

    fun isWin(s: RState): Boolean {
        for (i in 0 until n) if (kinds[i] == 0 && !s.locked[i]) return false
        if (level.requireNodes && (s.nodes and level.nodeMask) != level.nodeMask) return false
        return true
    }

    /** Applies gravity for the state's current [RState.turns] until everything rests. */
    fun settle(s: RState) {
        val dir = gravityFor(s.turns)
        val occ = IntArray(w * h) { -1 }
        for (i in 0 until n) occ[s.rows[i] * w + s.cols[i]] = i
        var guard = w * h * 8 + 16
        while (guard-- > 0) {
            var moved = false
            val order = (0 until n).sortedByDescending { dir.dx * s.cols[it] + dir.dy * s.rows[it] }
            for (i in order) {
                if (s.locked[i]) continue
                val nc = s.cols[i] + dir.dx
                val nr = s.rows[i] + dir.dy
                if (nc < 0 || nc >= w || nr < 0 || nr >= h) continue
                val cell = nr * w + nc
                val tc = level.tCode[cell]
                if (tc == T.WALL || tc == T.VOID) continue
                if (tc == T.ONEWAY && level.oneDir[cell] != dir.ordinal) continue
                if (occ[cell] != -1) continue
                occ[s.rows[i] * w + s.cols[i]] = -1
                s.cols[i] = nc
                s.rows[i] = nr
                occ[cell] = i
                moved = true
                applyEnter(i, cell, occ, s)
            }
            if (!moved) break
        }
    }

    private fun applyEnter(i: Int, cell: Int, occ: IntArray, s: RState) {
        if ((level.nodeMask ushr cell and 1L) == 1L) s.nodes = s.nodes or (1L shl cell)
        val tc = level.tCode[cell]
        if (kinds[i] == 0 && tc == T.SLOT && level.slotColor[cell] == colors[i]) {
            s.locked[i] = true
            return
        }
        if (tc == T.PORTAL) {
            val ex = level.portalExit[cell]
            if (ex >= 0 && occ[ex] == -1 && level.tCode[ex] != T.WALL && level.tCode[ex] != T.VOID) {
                occ[cell] = -1
                s.cols[i] = ex % w
                s.rows[i] = ex / w
                occ[ex] = i
                if ((level.nodeMask ushr ex and 1L) == 1L) s.nodes = s.nodes or (1L shl ex)
                if (kinds[i] == 0 && level.tCode[ex] == T.SLOT && level.slotColor[ex] == colors[i]) {
                    s.locked[i] = true
                }
            }
        }
    }

    /** Rotates the platform one step and settles. Returns a new state. */
    fun rotate(s: RState, cw: Boolean): RState {
        val next = s.copy()
        next.turns = s.turns + if (cw) 1 else -1
        settle(next)
        return next
    }

    private fun key(s: RState): String {
        val sb = StringBuilder()
        for (i in 0 until n) {
            sb.append(s.cols[i]).append(',').append(s.rows[i]).append(if (s.locked[i]) 'L' else '.').append(';')
        }
        sb.append('|').append(s.nodes).append('|').append(((s.turns % 4) + 4) % 4)
        return sb.toString()
    }

    /**
     * Breadth-first search over rotate-CW / rotate-CCW moves. Returns the minimum
     * number of rotations to win from [start], or null if unsolvable within [maxDepth].
     */
    fun solve(start: RState, maxDepth: Int = 14): Int? {
        if (isWin(start)) return 0
        val visited = HashSet<String>()
        visited.add(key(start))
        var frontier = ArrayList<RState>()
        frontier.add(start)
        var depth = 0
        while (frontier.isNotEmpty() && depth < maxDepth) {
            depth++
            val nextFrontier = ArrayList<RState>(frontier.size * 2)
            for (state in frontier) {
                for (cw in booleanArrayOf(true, false)) {
                    val ns = rotate(state, cw)
                    if (isWin(ns)) return depth
                    val k = key(ns)
                    if (visited.add(k)) nextFrontier.add(ns)
                }
            }
            frontier = nextFrontier
        }
        return null
    }
}
