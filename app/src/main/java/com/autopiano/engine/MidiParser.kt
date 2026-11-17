package com.autopiano.engine

import java.nio.charset.Charset
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object MidiParser {
    private data class RawTickNote(
        val start: Int,
        val end: Int,
        val pitch: Int,
        val vel: Int,
        val ch: Int,
        val track: Int
    )

    private data class BeatNote(
        var start: Double,
        var dur: Double,
        var pitch: Int,
        val vel: Int,
        val ch: Int,
        val track: Int
    )

    private val MAJOR_PROFILE = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR_PROFILE = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    private fun readVlq(buf: ByteArray, startPos: Int): Pair<Int, Int> {
        var pos = startPos
        var value = 0
        while (true) {
            if (pos >= buf.size) throw IllegalStateException("MIDI 数据意外截断")
            val b = buf[pos++].toInt() and 0xFF
            value = (value shl 7) or (b and 0x7F)
            if ((b and 0x80) == 0) return value to pos
        }
    }

    private fun readBeInt(buf: ByteArray, pos: Int, len: Int): Int {
        var v = 0
        for (i in 0 until len) {
            v = (v shl 8) or (buf[pos + i].toInt() and 0xFF)
        }
        return v
    }

    private fun decodeText(payload: ByteArray): String {
        for (cs in listOf(Charsets.UTF_8, Charset.forName("GB18030"), Charsets.ISO_8859_1)) {
            try {
                return String(payload, cs).trim()
            } catch (_: Exception) {
            }
        }
        return ""
    }

    private fun applySustainPedal(raw: List<RawTickNote>, pedals: List<Pair<Int, Int>>, division: Int): List<RawTickNote> {
        if (pedals.isEmpty()) return raw
        val sorted = pedals.sortedBy { it.first }
        val intervals = mutableListOf<Pair<Int, Int>>()
        var downT: Int? = null
        for ((t, v) in sorted) {
            if (v >= 64 && downT == null) {
                downT = t
            } else if (v < 64 && downT != null) {
                if (t > downT) intervals.add(downT to t)
                downT = null
            }
        }
        if (intervals.isEmpty()) return raw
        val maxHold = max(1, division) * 4
        return raw.map { n ->
            var newEnd = n.end
            for ((ps, pe) in intervals) {
                if (n.end in ps..pe) {
                    newEnd = min(pe, n.start + maxHold)
                    break
                }
            }
            n.copy(end = max(n.end, newEnd))
        }
    }

    private fun buildTempoMap(tempos: List<Pair<Int, Int>>, division: Int): List<Pair<Int, Double>> {
        val div = if (division > 0) division.toDouble() else 480.0
        val sorted = tempos.filter { it.second > 0 }.sortedBy { it.first }.toMutableList()
        if (sorted.isEmpty()) sorted.add(0 to 500000)
        if (sorted[0].first > 0) sorted.add(0, 0 to sorted[0].second)
        val merged = mutableListOf<Pair<Int, Int>>()
        for ((t, u) in sorted) {
            if (merged.isNotEmpty() && merged.last().first == t) {
                merged[merged.lastIndex] = t to u
            } else {
                merged.add(t to u)
            }
        }
        return merged.map { (t, u) -> t to (u / 1_000_000.0 / div) }
    }

    private fun tickToSec(tmap: List<Pair<Int, Double>>, tick: Int): Double {
        var sec = 0.0
        for (i in tmap.indices) {
            val (t, spt) = tmap[i]
            if (i + 1 < tmap.size && tick >= tmap[i + 1].first) {
                sec += (tmap[i + 1].first - t) * spt
                continue
            }
            if (tick > t) sec += (tick - t) * spt
            break
        }
        return sec
    }

    fun parse(
        data: ByteArray,
        preset: KeymapPreset = Keymaps.GENSHIN_3ROW,
        melodyOnly: Boolean = false
    ): Score {
        if (data.size < 14 || String(data, 0, 4, Charsets.US_ASCII) != "MThd") {
            throw ScoreError(0, 0, "无效的 MIDI 文件头")
        }
        val hlen = readBeInt(data, 4, 4)
        if (data.size < 8 + hlen) throw ScoreError(0, 0, "MIDI 文件头损坏")
        val division = readBeInt(data, 12, 2)
        if ((division and 0x8000) != 0 || division <= 0) {
            throw ScoreError(0, 0, "暂不支持的 MIDI 时间格式")
        }

        var pos = 8 + hlen
        val tracks = mutableListOf<ByteArray>()
        while (pos + 8 <= data.size) {
            val cid = String(data, pos, 4, Charsets.US_ASCII)
            val clen = readBeInt(data, pos + 4, 4)
            val endPos = min(data.size, pos + 8 + clen)
            if (cid == "MTrk") {
                tracks.add(data.copyOfRange(pos + 8, endPos))
            }
            pos += 8 + clen
        }
        if (tracks.isEmpty()) throw ScoreError(0, 0, "MIDI 中未找到音轨")

        val allRaw = mutableListOf<RawTickNote>()
        val tempos = mutableListOf<Pair<Int, Int>>()
        val trackNames = mutableMapOf<Int, String>()

        tracks.forEachIndexed { ti, chunk ->
            var tick = 0
            var p = 0
            var status = 0
            val active = HashMap<Pair<Int, Int>, Pair<Int, Int>>()
            val trackNotes = mutableListOf<RawTickNote>()
            val pedals = mutableListOf<Pair<Int, Int>>()
            val nameParts = mutableListOf<String>()

            while (p < chunk.size) {
                val (delta, np) = try { readVlq(chunk, p) } catch (_: Exception) { break }
                p = np
                tick += delta
                if (p >= chunk.size) break
                val b = chunk[p].toInt() and 0xFF
                if ((b and 0x80) != 0) {
                    status = b
                    p++
                }
                if (status == 0xFF) {
                    if (p >= chunk.size) break
                    val mtype = chunk[p++].toInt() and 0xFF
                    val (ln, np2) = try { readVlq(chunk, p) } catch (_: Exception) { break }
                    p = np2
                    val payloadEnd = min(chunk.size, p + ln)
                    val payload = chunk.copyOfRange(p, payloadEnd)
                    p = payloadEnd
                    if (mtype == 0x03) {
                        nameParts.add(decodeText(payload))
                    } else if (mtype == 0x51 && payload.size >= 3) {
                        tempos.add(tick to readBeInt(payload, 0, 3))
                    } else if (mtype == 0x2F) {
                        break
                    }
                    status = 0
                    continue
                }
                if (status == 0xF0 || status == 0xF7) {
                    val (ln, np2) = try { readVlq(chunk, p) } catch (_: Exception) { break }
                    p = min(chunk.size, np2 + ln)
                    status = 0
                    continue
                }
                val hi = status and 0xF0
                val ch = status and 0x0F
                if (hi == 0xC0 || hi == 0xD0) {
                    if (p >= chunk.size) break
                    p++
                    continue
                }
                if (p + 1 >= chunk.size) break
                val d1 = chunk[p].toInt() and 0xFF
                val d2 = chunk[p + 1].toInt() and 0xFF
                p += 2

                if (hi == 0xB0 && d1 == 64 && ch != 9) {
                    pedals.add(tick to d2)
                } else if (hi == 0x90 && d2 > 0 && ch != 9) {
                    val sig = ch to d1
                    active[sig]?.let { (st, vel) ->
                        trackNotes.add(RawTickNote(st, tick, d1, vel, ch, ti))
                    }
                    active[sig] = tick to d2
                } else if (hi == 0x80 || (hi == 0x90 && d2 == 0)) {
                    active.remove(ch to d1)?.let { (st, vel) ->
                        trackNotes.add(RawTickNote(st, tick, d1, vel, ch, ti))
                    }
                }
            }
            for ((sig, stVel) in active) {
                trackNotes.add(RawTickNote(stVel.first, tick, sig.second, stVel.second, sig.first, ti))
            }
            allRaw.addAll(applySustainPedal(trackNotes, pedals, division))
            val joined = nameParts.filter { it.isNotEmpty() }.joinToString(" ").trim()
            if (joined.isNotEmpty()) trackNames[ti] = joined
        }

        if (allRaw.isEmpty()) throw ScoreError(0, 0, "MIDI 文件中没有任何有效音符")

        val tmap = buildTempoMap(tempos, division)
        val virtualBpm = 120.0
        val scale = virtualBpm / 60.0
        val minDur = 0.02 * scale
        val beatNotes = allRaw.map { n ->
            val s0 = tickToSec(tmap, n.start)
            val s1 = tickToSec(tmap, n.end)
            BeatNote(s0 * scale, max((s1 - s0) * scale, minDur), n.pitch, n.vel, n.ch, n.track)
        }.sortedWith(compareBy<BeatNote> { it.start }.thenBy { it.pitch })

        return scoreFromBeatNotes(beatNotes, preset, virtualBpm, melodyOnly, trackNames)
    }

    private fun foldMidiToRange(p: Int, lo: Int, hi: Int): Int {
        if (p in lo..hi) return p
        return if (p < lo) p + 12 * ((lo - p + 11) / 12)
        else p - 12 * ((p - hi + 11) / 12)
    }

    private fun noteWeight(n: BeatNote): Double =
        1.0 + n.dur.coerceIn(0.0, 2.0) * 0.5 + (n.vel / 128.0) * 0.5

    private fun groupByOnset(items: List<BeatNote>, bpm: Double): List<List<BeatNote>> {
        if (items.isEmpty()) return emptyList()
        val tol = (EngineConstants.MIDI_CHORD_WINDOW_SEC * bpm / 60.0).coerceIn(0.006, 0.03)
        val groups = mutableListOf<MutableList<BeatNote>>()
        var anchor: Double? = null
        for (it in items) {
            if (anchor == null || it.start - anchor > tol) {
                groups.add(mutableListOf(it))
                anchor = it.start
            } else {
                groups.last().add(it)
            }
        }
        return groups
    }

    private fun guessMelodyTrack(items: List<BeatNote>): Int? {
        val groups = items.groupBy { (it.start * 1_000_000.0).roundToInt() }
        val stats = mutableMapOf<Int, DoubleArray>() // cnt, pitchSum, durSum, onsets
        val byTrack = mutableMapOf<Int, MutableList<BeatNote>>()
        for (g in groups.values) {
            val present = mutableSetOf<Int>()
            for (it in g) {
                if (it.track < 0) continue
                present.add(it.track)
                val s = stats.getOrPut(it.track) { DoubleArray(4) }
                s[0] += 1.0
                s[1] += it.pitch.toDouble()
                s[2] += it.dur
                byTrack.getOrPut(it.track) { mutableListOf() }.add(it)
            }
            for (tr in present) stats[tr]!![3] += 1.0
        }
        val maxCnt = stats.values.maxOfOrNull { it[0].toInt() } ?: 0
        val totalOnsets = max(1, groups.size).toDouble()
        var bestScore = Double.NEGATIVE_INFINITY
        var bestTrack: Int? = null
        var bestStrongScore = Double.NEGATIVE_INFINITY
        var bestStrongTrack: Int? = null

        for ((tr, s) in stats) {
            val cnt = s[0].toInt()
            if (cnt < max(6, (maxCnt * 0.04).toInt())) continue
            val tg = byTrack[tr]!!.groupBy { (it.start * 1_000_000.0).roundToInt() }.toSortedMap()
            val tops = tg.values.map { list -> list.maxBy { it.pitch }.pitch }
            if (tops.toSet().size < 3) continue
            var stepCnt = 0
            for (i in 1 until tops.size) {
                if (abs(tops[i] - tops[i - 1]) <= 7) stepCnt++
            }
            val stepRatio = stepCnt.toDouble() / max(1, tops.size - 1)
            val meanPitch = s[1] / cnt
            val meanDur = s[2] / cnt
            val poly = cnt / max(1.0, s[3])
            val coverage = s[3] / totalOnsets
            val score = meanPitch * 0.6 + stepRatio * 22.0 + min(meanDur, 1.5) * 4.0 - poly * 3.5 + coverage * 12.0
            if (score > bestScore) {
                bestScore = score
                bestTrack = tr
            }
            if (coverage >= 0.12 && score > bestStrongScore) {
                bestStrongScore = score
                bestStrongTrack = tr
            }
        }
        return bestStrongTrack ?: bestTrack
    }

    private fun melodyNoteOf(group: List<BeatNote>, melodyTrack: Int?): BeatNote? {
        if (group.isEmpty()) return null
        val cand = if (melodyTrack == null) group else group.filter { it.track == melodyTrack }
        if (cand.isEmpty()) return null
        return cand.maxWith(compareBy<BeatNote> { it.pitch }.thenBy { it.vel })
    }

    private fun bestTranspose(items: List<BeatNote>, preset: KeymapPreset, bpm: Double): Int {
        val (lo, hi) = preset.midiBounds
        val rawGroups = groupByOnset(items, bpm)
        val melodyTrack = guessMelodyTrack(items)
        val melody = rawGroups.mapNotNull { melodyNoteOf(it, melodyTrack) }
            .ifEmpty { rawGroups.mapNotNull { g -> g.maxWithOrNull(compareBy<BeatNote> { it.pitch }.thenBy { it.vel }) } }
        if (melody.isEmpty()) return 0

        val pcWeights = DoubleArray(12)
        for (n in items) pcWeights[Math.floorMod(n.pitch, 12)] += noteWeight(n) * 0.4
        for (n in melody) pcWeights[Math.floorMod(n.pitch, 12)] += noteWeight(n) * 2.5
        val pcSum = pcWeights.sum() + 1e-6

        var bestShift = 0
        var bestCost = Double.POSITIVE_INFINITY
        for (shift in -11..11) {
            val melBlack = melody.filter { Keymaps.isBlackKey(it.pitch + shift) }.sumOf { noteWeight(it) }
            val allBlack = items.filter { Keymaps.isBlackKey(it.pitch + shift) }.sumOf { noteWeight(it) }
            val blackPenalty = (melBlack * 3.2 + allBlack * 0.35) / max(1.0, melody.size * 3.0)

            var majScore = 0.0
            var minScore = 0.0
            for (i in 0 until 12) {
                val w = pcWeights[Math.floorMod(i - shift, 12)]
                majScore += w * MAJOR_PROFILE[i]
                minScore += w * MINOR_PROFILE[Math.floorMod(i - 9, 12)]
            }
            val tonalBonus = max(majScore, minScore) / pcSum * 0.15
            val cost = blackPenalty - tonalBonus
            if (cost < bestCost - 1e-9 || (abs(cost - bestCost) <= 1e-9 && abs(shift) < abs(bestShift))) {
                bestCost = cost
                bestShift = shift
            }
        }

        var bestOct = 0
        var bestOctCost = Double.POSITIVE_INFINITY
        for (oct in intArrayOf(-24, -12, 0, 12, 24)) {
            var foldCost = 0.0
            for (it in melody) {
                val q = it.pitch + bestShift + oct
                val f = foldMidiToRange(q, lo, hi)
                if (f != q) foldCost += abs(f - q) * noteWeight(it)
            }
            foldCost += abs(oct) * 3.0
            if (foldCost < bestOctCost - 1e-6 || (abs(foldCost - bestOctCost) <= 1e-6 && abs(oct) < abs(bestOct))) {
                bestOctCost = foldCost
                bestOct = oct
            }
        }
        return bestShift + bestOct
    }

    private fun optimizeMelodyOctaves(groups: List<List<BeatNote>>, lo: Int, hi: Int, melodyTrack: Int?) {
        val melInfo = mutableListOf<Pair<Int, Int>>()
        groups.forEachIndexed { gi, g ->
            melodyNoteOf(g, melodyTrack)?.let { melInfo.add(gi to it.pitch) }
        }
        if (melInfo.isEmpty()) return

        val tops = melInfo.map { it.second }
        val steps = tops.map { top ->
            val offs = (-6..6).map { it * 12 }.filter { top + it in lo..hi }
            offs.ifEmpty { listOf((((lo + hi) * 0.5 - top) / 12.0).roundToInt() * 12) }
        }

        val costs = mutableListOf<DoubleArray>()
        val backs = mutableListOf<IntArray>()
        for (i in steps.indices) {
            val offs = steps[i]
            if (i == 0) {
                costs.add(DoubleArray(offs.size) { EngineConstants.MIDI_OCTAVE_SHIFT_PENALTY * abs(offs[it] / 12) })
                backs.add(IntArray(offs.size) { -1 })
            } else {
                val curCost = DoubleArray(offs.size)
                val curBack = IntArray(offs.size)
                val prevOffs = steps[i - 1]
                for (oi in offs.indices) {
                    val played = tops[i] + offs[oi]
                    var bestC = Double.POSITIVE_INFINITY
                    var bestJ = 0
                    for (j in prevOffs.indices) {
                        val prevPlayed = tops[i - 1] + prevOffs[j]
                        val err = abs((played - prevPlayed) - (tops[i] - tops[i - 1]))
                        val c = costs[i - 1][j] + err * err + EngineConstants.MIDI_OCTAVE_SHIFT_PENALTY * abs(offs[oi] / 12)
                        if (c < bestC) {
                            bestC = c
                            bestJ = j
                        }
                    }
                    curCost[oi] = bestC
                    curBack[oi] = bestJ
                }
                costs.add(curCost)
                backs.add(curBack)
            }
        }

        var j = costs.last().indices.minBy { costs.last()[it] }
        val offByGroup = mutableMapOf<Int, Int>()
        for (i in melInfo.indices.reversed()) {
            offByGroup[melInfo[i].first] = steps[i][j]
            j = backs[i][j]
        }
        val keys = offByGroup.keys.sorted()
        for (gi in groups.indices) {
            val off = offByGroup[gi]
                ?: keys.lastOrNull { it < gi }?.let { offByGroup[it] }
                ?: keys.firstOrNull { it > gi }?.let { offByGroup[it] }
                ?: 0
            if (off != 0) {
                for (it in groups[gi]) it.pitch += off
            }
        }
    }

    private fun naturalizeInContext(p: Int, peers: List<Int>): Int {
        if (!Keymaps.isBlackKey(p)) return p
        val low = p - 1
        val high = p + 1
        if (peers.isEmpty()) return low
        val pcs = peers.map { Math.floorMod(it, 12) }.toSet()
        val center = peers.sorted()[peers.size / 2].toDouble()
        fun score(c: Int): Double = (if (Math.floorMod(c, 12) in pcs) 2.0 else 0.0) - abs(c - center) * 0.08
        return if (score(low) >= score(high)) low else high
    }

    private fun clusterKeep(
        group: List<BeatNote>,
        lo: Int,
        hi: Int,
        maxPoly: Int,
        melodyOnly: Boolean,
        melodyNote: BeatNote?,
        duckAccomp: Boolean
    ): List<BeatNote> {
        val sorted = group.sortedWith(compareBy<BeatNote> { it.pitch }.thenBy { it.vel })
        val melody = melodyNote ?: sorted.last()
        if (melodyOnly || maxPoly == 1) return listOf(melody)

        val effectivePoly = if (duckAccomp) 2 else maxPoly
        val keep = mutableListOf(melody)
        val keepSet = HashSet<BeatNote>(listOf(melody))

        val bass = sorted.firstOrNull { it !in keepSet && !Keymaps.isBlackKey(it.pitch) } ?: sorted.first()
        if (bass !in keepSet) {
            keep.add(bass)
            keepSet.add(bass)
        }
        if (keep.size >= effectivePoly) return keep.take(effectivePoly)

        val peers = sorted.map { it.pitch }
        val slots = keep.mapTo(HashSet()) { foldMidiToRange(naturalizeInContext(it.pitch, peers), lo, hi) }
        val keptPcs = keep.mapTo(HashSet()) { Math.floorMod(it.pitch, 12) }

        val cand = sorted.filter {
            it !in keepSet && it.pitch >= melody.pitch - 32 && !Keymaps.isBlackKey(it.pitch)
        }.map {
            val imp = (it.vel / 128.0) * 0.7 + min(it.dur, 2.0) / 2.0 * 0.5 + if (it.pitch in lo..hi) 0.35 else 0.0
            it to imp
        }.toMutableList()

        while (cand.isNotEmpty() && keep.size < effectivePoly) {
            val best = cand.maxWith(compareBy<Pair<BeatNote, Double>> {
                it.second + if (Math.floorMod(it.first.pitch, 12) !in keptPcs) 0.9 else 0.0
            }.thenBy { it.first.pitch })
            cand.remove(best)
            val it = best.first
            val slot = foldMidiToRange(naturalizeInContext(it.pitch, peers), lo, hi)
            if (slot in slots) continue
            keep.add(it)
            slots.add(slot)
            keptPcs.add(Math.floorMod(it.pitch, 12))
        }
        return keep
    }

    private fun scoreFromBeatNotes(
        rawNotes: List<BeatNote>,
        preset: KeymapPreset,
        bpm: Double,
        melodyOnly: Boolean,
        trackNames: Map<Int, String>
    ): Score {
        val (lo, hi) = preset.midiBounds
        val shift = bestTranspose(rawNotes, preset, bpm)
        val items = rawNotes.filter { (it.ch and 0x0F) != 9 && it.vel >= EngineConstants.MIDI_MIN_VELOCITY }
            .map { it.copy(dur = max(it.dur, 0.05), pitch = it.pitch + shift) }
            .sortedWith(compareBy<BeatNote> { it.start }.thenBy { it.pitch })
        if (items.isEmpty()) throw ScoreError(0, 0, "MIDI 中没有可用音符")

        val groups = groupByOnset(items, bpm)
        val melodyTrack = guessMelodyTrack(items)
        optimizeMelodyOctaves(groups, lo, hi, melodyTrack)

        val secPerBeat = 60.0 / bpm
        val score = Score(bpm)
        score.meta.isMidi = true
        score.meta.midiShift = shift
        score.meta.midiMelodyTrack = melodyTrack
        score.meta.midiMelodyTrackName = melodyTrack?.let { trackNames[it] ?: "" } ?: ""

        val groupMelodies = groups.map { melodyNoteOf(it, melodyTrack) }
        var melodyCount = 0

        groups.forEachIndexed { gi, group ->
            val start = group[0].start
            val peers = group.map { it.pitch }
            val melodyNote = groupMelodies[gi]

            var duckAccomp = false
            if (melodyNote != null) {
                var prevDt = 999.0
                var nextDt = 999.0
                for (pj in gi - 1 downTo 0) {
                    if (groupMelodies[pj] != null) {
                        prevDt = (start - groups[pj][0].start) * secPerBeat
                        break
                    }
                }
                for (nj in gi + 1 until groups.size) {
                    if (groupMelodies[nj] != null) {
                        nextDt = (groups[nj][0].start - start) * secPerBeat
                        break
                    }
                }
                if (min(prevDt, nextDt) < EngineConstants.MIDI_FAST_MELODY_SEC) {
                    duckAccomp = true
                }
            }

            val keep = clusterKeep(
                group, lo, hi, EngineConstants.MIDI_MAX_POLYPHONY,
                melodyOnly, melodyNote, duckAccomp
            )
            val used = HashSet<String>()
            for (it in keep) {
                var p = naturalizeInContext(it.pitch, peers)
                p = foldMidiToRange(p, lo, hi)
                val slot = preset.slotForMidi(p) ?: continue
                if (!used.add(slot.id)) continue
                val isMelody = melodyNote != null && it === melodyNote
                if (isMelody) melodyCount++
                score.notes.add(
                    Note(
                        slot = slot,
                        start = start,
                        dur = max(it.dur, 0.05),
                        src = "midi",
                        voice = if (isMelody) "melody" else "harmony",
                        midi = p
                    )
                )
            }
        }

        if (score.notes.isEmpty()) throw ScoreError(0, 0, "MIDI 映射后无有效按键")
        score.meta.midiMelodyNotes = melodyCount
        score.meta.midiTotalNotes = score.notes.size
        return score
    }
}
