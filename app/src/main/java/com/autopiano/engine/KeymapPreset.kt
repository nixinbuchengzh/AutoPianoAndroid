package com.autopiano.engine

/**
 * 琴键网格位置：(row, col)，row=0 为最上面一排，col=0 为最左一列。
 */
data class KeySlot(val row: Int, val col: Int) {
    val id: String get() = "$row,$col"
}

/**
 * 单个游戏/乐器的键位预设：
 * - 原神 3排：3×7 = 21 键 (C4~B6)，row 0 = C6~B6, row 1 = C5~B5, row 2 = C4~B4
 * - 原神 2排：2×7 = 14 键 (C4~B5)，row 0 = C5~B5, row 1 = C4~B4
 * - 光遇 15键：3×5 = 15 键 (C4~C6)，按从左到右、从上到下排列：
 *     row 0: C4, D4, E4, F4, G4
 *     row 1: A4, B4, C5, D5, E5
 *     row 2: F5, G5, A5, B5, C6
 */
data class KeymapPreset(
    val id: String,
    val displayName: String,
    val rows: Int,
    val cols: Int,
    val noteToSlot: Map<String, KeySlot>
) {
    val midiBounds: Pair<Int, Int> by lazy {
        var lo = 127
        var hi = 0
        for (name in noteToSlot.keys) {
            val m = Keymaps.noteNameToMidi(name)
            if (m < lo) lo = m
            if (m > hi) hi = m
        }
        lo to hi
    }

    fun slotForMidi(midi: Int): KeySlot? {
        if (Keymaps.isBlackKey(midi)) return null
        val name = "${Keymaps.NOTE_NAMES[midi % 12]}${midi / 12 - 1}"
        return noteToSlot[name]
    }
}

object Keymaps {
    val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    val NOTE_TO_PC = NOTE_NAMES.withIndex().associate { (i, n) -> n to i }
    private val SCALE7 = arrayOf("C", "D", "E", "F", "G", "A", "B")
    private val BLACK_PCS = setOf(1, 3, 6, 8, 10)

    fun isBlackKey(midi: Int): Boolean = (midi % 12) in BLACK_PCS

    fun noteNameToMidi(name: String): Int {
        val oct = name.last().digitToInt()
        val pc = name.dropLast(1)
        return (oct + 1) * 12 + (NOTE_TO_PC[pc] ?: 0)
    }

    val GENSHIN_3ROW: KeymapPreset = run {
        val map = LinkedHashMap<String, KeySlot>()
        // row 0 = C6~B6 (高音), row 1 = C5~B5 (中音), row 2 = C4~B4 (低音)
        for (i in SCALE7.indices) map["${SCALE7[i]}6"] = KeySlot(0, i)
        for (i in SCALE7.indices) map["${SCALE7[i]}5"] = KeySlot(1, i)
        for (i in SCALE7.indices) map["${SCALE7[i]}4"] = KeySlot(2, i)
        KeymapPreset("genshin_3row", "原神 3排(21键)", 3, 7, map)
    }

    val GENSHIN_2ROW: KeymapPreset = run {
        val map = LinkedHashMap<String, KeySlot>()
        for (i in SCALE7.indices) map["${SCALE7[i]}5"] = KeySlot(0, i)
        for (i in SCALE7.indices) map["${SCALE7[i]}4"] = KeySlot(1, i)
        KeymapPreset("genshin_2row", "原神 2排(14键)", 2, 7, map)
    }

    val SKY_3X5: KeymapPreset = run {
        val notes = listOf(
            "C4", "D4", "E4", "F4", "G4",
            "A4", "B4", "C5", "D5", "E5",
            "F5", "G5", "A5", "B5", "C6"
        )
        val map = LinkedHashMap<String, KeySlot>()
        notes.forEachIndexed { idx, name ->
            map[name] = KeySlot(idx / 5, idx % 5)
        }
        KeymapPreset("sky_3x5", "光遇 15键(3×5)", 3, 5, map)
    }

    val PRESETS = listOf(GENSHIN_3ROW, GENSHIN_2ROW, SKY_3X5)

    fun byId(id: String): KeymapPreset = PRESETS.firstOrNull { it.id == id } ?: GENSHIN_3ROW
}
