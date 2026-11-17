package com.autopiano.engine

object JianpuParser {
    private val TOKEN_REGEX = Regex("""[ \t]*(\[[^\]\n]*\]|[^ \t\[\]\n]+)""")
    private val BPM_REGEX = Regex("""^\s*BPM\s*[:=]?\s*(\d+(?:\.\d+)?)\s*$""", RegexOption.IGNORE_CASE)
    private val COMMENT_HASH_REGEX = Regex("""(^|\s)#(?![0-9]).*$""")
    private val DEGREE_SEMITONE = mapOf(
        '1' to 0, '2' to 2, '3' to 4, '4' to 5, '5' to 7, '6' to 9, '7' to 11
    )

    private sealed class ParsedToken {
        data class Rest(val dur: Double) : ParsedToken()
        data class SingleNote(val slot: KeySlot, val dur: Double) : ParsedToken()
    }

    private fun noteNameFromSemitone(semitone: Int): String {
        val oct = 4 + Math.floorDiv(semitone, 12)
        val pc = Math.floorMod(semitone, 12)
        return "${Keymaps.NOTE_NAMES[pc]}$oct"
    }

    private fun foldToPreset(name: String, preset: KeymapPreset): KeySlot? {
        preset.noteToSlot[name]?.let { return it }
        val oct = name.last().digitToIntOrNull() ?: return null
        val pc = name.dropLast(1)
        for (delta in intArrayOf(1, -1, 2, -2, 3, -3)) {
            val cand = "$pc${oct + delta}"
            preset.noteToSlot[cand]?.let { return it }
        }
        return null
    }

    private fun parseSingleNote(tok: String, preset: KeymapPreset, lineno: Int): ParsedToken? {
        if (tok.isEmpty()) return null
        if (tok.all { it == '0' }) return ParsedToken.Rest(1.0)

        var octShift = 0
        var accident = 0
        var i = 0
        val n = tok.length

        while (i < n && (tok[i] == '^' || tok[i] == '_')) {
            octShift += if (tok[i] == '^') 1 else -1
            i++
        }
        if (i < n && (tok[i] == '#' || tok[i] == 'b')) {
            accident = if (tok[i] == '#') 1 else -1
            i++
        }
        if (i >= n || !tok[i].isDigit()) {
            throw ScoreError(lineno, 0, "无法识别的音符 '$tok'")
        }
        val degree = tok[i]
        if (degree != '0' && degree !in DEGREE_SEMITONE) {
            throw ScoreError(lineno, 0, "音级只能是 1-7 或 0: '$tok'")
        }
        i++

        if (degree == '0') {
            var dur = 1.0
            var tail = tok.substring(i)
            while (tail.startsWith("_")) { dur *= 0.5; tail = tail.drop(1) }
            while (tail.startsWith(".")) { dur *= 1.5; tail = tail.drop(1) }
            while (tail.startsWith("-")) { dur += 1.0; tail = tail.drop(1) }
            return ParsedToken.Rest(dur)
        }

        var semitone = (DEGREE_SEMITONE[degree] ?: 0) + 12 * octShift
        var tail = tok.substring(i)
        while (tail.isNotEmpty()) {
            val c = tail[0]
            if (c == '^') {
                octShift++
                semitone = (DEGREE_SEMITONE[degree] ?: 0) + 12 * octShift
                tail = tail.drop(1)
            } else if (c == '#' || c == 'b') {
                accident = if (c == '#') 1 else -1
                tail = tail.drop(1)
            } else {
                break
            }
        }

        var dur = 1.0
        while (tail.startsWith("_")) { dur *= 0.5; tail = tail.drop(1) }
        while (tail.startsWith(".")) { dur *= 1.5; tail = tail.drop(1) }
        while (tail.startsWith("-")) { dur += 1.0; tail = tail.drop(1) }
        if (tail.isNotEmpty()) {
            throw ScoreError(lineno, 0, "未知后缀 '$tail' in '$tok'")
        }

        var semi = semitone + accident
        if (Math.floorMod(semi, 12) in setOf(1, 3, 6, 8, 10)) {
            semi -= 1
        }
        val name = noteNameFromSemitone(semi)
        val slot = foldToPreset(name, preset)
            ?: throw ScoreError(lineno, 0, "音高 $name 超出键位范围")
        return ParsedToken.SingleNote(slot, dur)
    }

    fun parse(
        text: String,
        preset: KeymapPreset = Keymaps.GENSHIN_3ROW,
        baseBpm: Double = EngineConstants.DEFAULT_BPM
    ): Score {
        val score = Score(baseBpm)
        var beat = 0.0

        text.lines().forEachIndexed { idx, raw ->
            val lineno = idx + 1
            var line = raw
            val slashIdx = line.indexOf("//")
            if (slashIdx >= 0) line = line.substring(0, slashIdx)
            line = COMMENT_HASH_REGEX.replace(line, "")
            if (line.isBlank()) return@forEachIndexed

            BPM_REGEX.matchEntire(line)?.let { m ->
                m.groupValues[1].toDoubleOrNull()?.let { v ->
                    score.bpm = v.coerceIn(20.0, 400.0)
                }
                return@forEachIndexed
            }

            for (m in TOKEN_REGEX.findAll(line)) {
                val tok = m.groupValues[1].trim()
                if (tok.isEmpty() || tok == "|") continue

                if (tok.startsWith("[") && tok.endsWith("]")) {
                    val inner = tok.substring(1, tok.length - 1).trim()
                    if (inner.isEmpty()) continue
                    val parsedNotes = mutableListOf<Pair<KeySlot, Double>>()
                    var maxDur = 0.0
                    for (p in inner.split(Regex("""\s+"""))) {
                        when (val r = parseSingleNote(p, preset, lineno)) {
                            is ParsedToken.SingleNote -> {
                                parsedNotes.add(r.slot to r.dur)
                                if (r.dur > maxDur) maxDur = r.dur
                            }
                            is ParsedToken.Rest -> if (r.dur > maxDur) maxDur = r.dur
                            null -> {}
                        }
                    }
                    val step = if (maxDur > 0.0) maxDur else 1.0
                    for ((slot, dur) in parsedNotes) {
                        score.notes.add(Note(slot, beat, dur, "$lineno:$tok"))
                    }
                    beat += step
                } else {
                    when (val r = parseSingleNote(tok, preset, lineno)) {
                        is ParsedToken.Rest -> beat += r.dur
                        is ParsedToken.SingleNote -> {
                            score.notes.add(Note(r.slot, beat, r.dur, "$lineno:$tok"))
                            beat += r.dur
                        }
                        null -> {}
                    }
                }
            }
        }

        if (score.notes.isEmpty()) {
            throw ScoreError(0, 0, "谱面中没有任何音符")
        }
        return score
    }
}
