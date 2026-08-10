package com.thundercrest.thundercrestgame.game

/** Static chapter / region metadata used by the map and level-select screens. */
data class ChapterInfo(val number: Int, val title: String, val subtitle: String)

val CHAPTER_INFO = listOf(
    ChapterInfo(1, "Sanctuaries of Earth", "The first trials of Olympus"),
    ChapterInfo(2, "Temples of Clouds", "Thunder nodes awaken"),
    ChapterInfo(3, "Sky Bridges", "One-way channels & blocks"),
    ChapterInfo(4, "Halls of Gods", "Portals of the divine"),
    ChapterInfo(5, "Peak of Olympus", "The final ascension"),
)

/**
 * Holds every generated level for the session. Populated once during the loading
 * screen so the progress bar reflects real work and gameplay never stalls.
 */
object LevelRepository {
    private val levels = HashMap<Int, Level>()

    val totalLevels: Int get() = CHAPTER_COUNT * LEVELS_PER_CHAPTER

    fun isReady(): Boolean = levels.size >= totalLevels

    /** Generates one level and stores it. Index across all chapters is 0-based. */
    fun generateAt(globalIndex: Int) {
        val chapter = globalIndex / LEVELS_PER_CHAPTER + 1
        val idx = globalIndex % LEVELS_PER_CHAPTER
        val level = LevelGen.generate(chapter, idx)
        synchronized(levels) { levels[level.id] = level }
    }

    fun get(chapter: Int, indexInChapter: Int): Level {
        val id = (chapter - 1) * LEVELS_PER_CHAPTER + indexInChapter
        return levels[id] ?: LevelGen.generate(chapter, indexInChapter).also {
            synchronized(levels) { levels[id] = it }
        }
    }

    fun getById(id: Int): Level = get(id / LEVELS_PER_CHAPTER + 1, id % LEVELS_PER_CHAPTER)
}
