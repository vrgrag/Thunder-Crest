package com.thundercrest.thundercrestgame.game

import kotlin.random.Random

/** Tuning per chapter (1..5). Difficulty ramps as the design document describes. */
private data class ChapterSpec(
    val size: Int,
    val colors: List<GemColor>,
    val gems: IntRange,
    val walls: IntRange,
    val nodes: IntRange,
    val oneways: IntRange,
    val portals: Boolean,
    val blocks: IntRange,
    val minPar: Int,
    val maxPar: Int,
)

private val CHAPTERS = listOf(
    // Ch1 - Sanctuaries of Earth: one gem type, simple channels.
    ChapterSpec(5, listOf(GemColor.SAPPHIRE), 1..2, 2..4, 0..0, 0..0, false, 0..0, 1, 5),
    // Ch2 - Temples of Clouds: several gems + thunder nodes.
    ChapterSpec(6, listOf(GemColor.SAPPHIRE, GemColor.RUBY), 2..3, 3..6, 1..2, 0..0, false, 0..0, 2, 7),
    // Ch3 - Sky Bridges: one-way channels, movable blocks.
    ChapterSpec(6, listOf(GemColor.SAPPHIRE, GemColor.RUBY, GemColor.EMERALD), 2..3, 3..6, 1..2, 1..2, false, 1..2, 3, 9),
    // Ch4 - Halls of Gods: portals + everything.
    ChapterSpec(7, listOf(GemColor.RUBY, GemColor.SAPPHIRE, GemColor.EMERALD, GemColor.AMETHYST), 3..4, 4..8, 1..3, 1..2, true, 1..2, 3, 10),
    // Ch5 - Peak of Olympus: the hardest combinations.
    ChapterSpec(7, listOf(GemColor.RUBY, GemColor.SAPPHIRE, GemColor.EMERALD, GemColor.AMETHYST), 3..5, 5..9, 2..3, 2..3, true, 1..3, 4, 12),
)

const val CHAPTER_COUNT = 5
const val LEVELS_PER_CHAPTER = 10

object LevelGen {

    /** Deterministic per (chapter,index) so every install has identical, solvable levels. */
    fun generate(chapter: Int, indexInChapter: Int): Level {
        val spec = CHAPTERS[chapter - 1]
        // Scale counts by how far into the chapter we are (0..1).
        val t = indexInChapter.toFloat() / (LEVELS_PER_CHAPTER - 1)
        val baseSeed = chapter * 100_000 + indexInChapter * 1000
        var attempt = 0
        while (attempt < 1200) {
            val rng = Random((baseSeed + attempt).toLong())
            val level = tryBuild(chapter, indexInChapter, spec, t, rng)
            if (level != null) return level
            attempt++
        }
        // Extremely unlikely fallback: a trivial guaranteed-solvable board.
        return fallback(chapter, indexInChapter)
    }

    private fun scaled(range: IntRange, t: Float, rng: Random): Int {
        val lo = range.first
        val hi = range.last
        if (hi <= lo) return lo
        val mid = lo + ((hi - lo) * t).toInt()
        val jitter = rng.nextInt(-1, 2)
        return (mid + jitter).coerceIn(lo, hi)
    }

    private fun tryBuild(
        chapter: Int,
        idx: Int,
        spec: ChapterSpec,
        t: Float,
        rng: Random,
    ): Level? {
        val w = spec.size
        val h = spec.size
        val size = w * h
        val tCode = IntArray(size) { T.FLOOR }
        val slotColor = IntArray(size) { -1 }
        val oneDir = IntArray(size) { -1 }
        val portalExit = IntArray(size) { -1 }
        var nodeMask = 0L

        val free = (0 until size).toMutableList()
        free.shuffle(rng)

        fun take(): Int? = if (free.isEmpty()) null else free.removeAt(free.size - 1)

        // Walls.
        val wallCount = scaled(spec.walls, t, rng)
        repeat(wallCount) {
            val c = take() ?: return@repeat
            tCode[c] = T.WALL
        }

        // Determine gem colours used and counts.
        val colorCount = (1 + (spec.colors.size * t).toInt()).coerceIn(1, spec.colors.size)
        val usedColors = spec.colors.take(colorCount)
        val gemsPerColor = HashMap<GemColor, Int>()
        val totalGemsTarget = scaled(spec.gems, t, rng).coerceAtLeast(usedColors.size)
        for (i in 0 until totalGemsTarget) {
            val col = usedColors[i % usedColors.size]
            gemsPerColor[col] = (gemsPerColor[col] ?: 0) + 1
        }

        val pieces = ArrayList<Piece>()
        // Place gems and matching slots.
        for ((color, count) in gemsPerColor) {
            repeat(count) {
                val gemCell = take() ?: return null
                val slotCell = take() ?: return null
                pieces.add(Piece(Kind.GEM, color, gemCell % w, gemCell / w))
                tCode[slotCell] = T.SLOT
                slotColor[slotCell] = color.ordinal
            }
        }

        // Thunder nodes.
        val nodeCount = scaled(spec.nodes, t, rng)
        repeat(nodeCount) {
            val c = take() ?: return@repeat
            tCode[c] = T.NODE
            nodeMask = nodeMask or (1L shl c)
        }

        // One-way channels.
        val oneCount = scaled(spec.oneways, t, rng)
        repeat(oneCount) {
            val c = take() ?: return@repeat
            tCode[c] = T.ONEWAY
            oneDir[c] = Dir.entries[rng.nextInt(4)].ordinal
        }

        // Portals (a single pair).
        if (spec.portals && rng.nextFloat() < 0.7f) {
            val a = take()
            val b = take()
            if (a != null && b != null) {
                tCode[a] = T.PORTAL
                tCode[b] = T.PORTAL
                portalExit[a] = b
                portalExit[b] = a
            }
        }

        // Movable stone blocks.
        val blockCount = scaled(spec.blocks, t, rng)
        repeat(blockCount) {
            val c = take() ?: return@repeat
            pieces.add(Piece(Kind.BLOCK, null, c % w, c / w))
        }

        if (pieces.none { it.kind == Kind.GEM }) return null

        val level = Level(
            id = (chapter - 1) * LEVELS_PER_CHAPTER + idx,
            chapter = chapter,
            indexInChapter = idx,
            w = w, h = h,
            tCode = tCode,
            slotColor = slotColor,
            oneDir = oneDir,
            portalExit = portalExit,
            nodeMask = nodeMask,
            requireNodes = nodeMask != 0L,
            pieces = pieces,
            par = 0,
        )

        val sim = Sim(level)
        val start = sim.newState()
        if (sim.isWin(start)) return null // already solved -> boring
        val par = sim.solve(start, maxDepth = spec.maxPar + 2) ?: return null
        if (par < spec.minPar || par > spec.maxPar) return null

        return Level(
            id = level.id, chapter = chapter, indexInChapter = idx,
            w = w, h = h, tCode = tCode, slotColor = slotColor, oneDir = oneDir,
            portalExit = portalExit, nodeMask = nodeMask, requireNodes = level.requireNodes,
            pieces = pieces, par = par,
        )
    }

    private fun fallback(chapter: Int, idx: Int): Level {
        // A minimal 5x5 board with one gem that needs a couple of rotations.
        val w = 5; val h = 5; val size = w * h
        val tCode = IntArray(size) { T.FLOOR }
        val slotColor = IntArray(size) { -1 }
        val slot = 0 // top-left corner
        tCode[slot] = T.SLOT
        slotColor[slot] = GemColor.SAPPHIRE.ordinal
        val pieces = listOf(Piece(Kind.GEM, GemColor.SAPPHIRE, w - 1, h - 1))
        val level = Level(
            id = (chapter - 1) * LEVELS_PER_CHAPTER + idx,
            chapter = chapter, indexInChapter = idx, w = w, h = h,
            tCode = tCode, slotColor = slotColor, oneDir = IntArray(size) { -1 },
            portalExit = IntArray(size) { -1 }, nodeMask = 0L, requireNodes = false,
            pieces = pieces, par = 2,
        )
        val sim = Sim(level)
        val par = sim.solve(sim.newState(), 8) ?: 2
        return Level(
            id = level.id, chapter = chapter, indexInChapter = idx, w = w, h = h,
            tCode = tCode, slotColor = slotColor, oneDir = level.oneDir,
            portalExit = level.portalExit, nodeMask = 0L, requireNodes = false,
            pieces = pieces, par = par,
        )
    }
}
