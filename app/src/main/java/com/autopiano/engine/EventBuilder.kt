package com.autopiano.engine

import java.util.IdentityHashMap

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

object EventBuilder {
    private fun hasOnsetNear(onsets: List<Double>, beatPos: Double, radiusBeats: Double): Boolean {
        if (onsets.isEmpty()) return false
        var lo = 0
        var hi = onsets.size
        val target = beatPos - radiusBeats
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (onsets[mid] < target) lo = mid + 1 else hi = mid
        }
        return lo < onsets.size && onsets[lo] <= beatPos + radiusBeats
    }

    private fun normalizeKeyEvents(events: List<PlayEvent>, minGate: Double): List<PlayEvent> {
        val byKey = events.filter { it.action == 0 }.groupBy { it.slot.id }
        val out = mutableListOf<PlayEvent>()
        val retriggerGap = EngineConstants.MIN_FAST_GATE + EngineConstants.MIN_KEY_GAP_SEC

        for ((_, seqRaw) in byKey) {
            val seq = seqRaw.sortedBy { it.t }
            val selected = mutableListOf<PlayEvent>()
            for (ev in seq) {
                if (selected.isEmpty()) {
                    selected.add(ev)
                } else if (ev.t - selected.last().t >= retriggerGap) {
                    selected.add(ev)
                } else {
                    selected[selected.lastIndex] = ev
                }
            }
            for (i in selected.indices) {
                val ev = selected[i]
                var hold = ev.hold.coerceIn(EngineConstants.MIN_FAST_GATE, EngineConstants.MAX_HOLD_SEC)
                if (i + 1 < selected.size) {
                    val limit = selected[i + 1].t - ev.t - EngineConstants.MIN_KEY_GAP_SEC
                    hold = max(EngineConstants.MIN_FAST_GATE, min(hold, limit))
                }
                out.add(PlayEvent(ev.t, 0, ev.slot, hold))
                out.add(PlayEvent(ev.t + hold, 1, ev.slot, hold))
            }
        }
        return out.sortedWith(compareBy<PlayEvent> { it.t }.thenByDescending { it.action })
    }

    fun build(
        score: Score,
        bpm: Double = score.bpm,
        sustain: Boolean = false,
        sustainMin: Double = EngineConstants.SUSTAIN_MIN_SEC,
        sustainInterval: Double = EngineConstants.SUSTAIN_INTERVAL_SEC,
        minGap: Double = EngineConstants.MIN_NOTE_GAP_SEC
    ): List<PlayEvent> {
        val playBpm = max(1.0, bpm)
        val baseBpm = max(1.0, score.bpm)
        val beatSec = 60.0 / playBpm
        val baseBeatSec = 60.0 / baseBpm
        val gateSec = max(EngineConstants.MIN_FAST_GATE, EngineConstants.MIN_GATE * min(1.0, baseBpm / playBpm))

        var ns = score.sortedNotes()
        if (minGap > 0) {
            val kept = mutableListOf<Note>()
            for (i in ns.indices) {
                val n = ns[i]
                val isMidiHarmony = n.src == "midi" && n.voice != "melody"
                if (isMidiHarmony && i + 1 < ns.size) {
                    val nxt = ns[i + 1]
                    val durSec = n.dur * baseBeatSec
                    val gapSec = (nxt.start - (n.start + n.dur)) * baseBeatSec
                    if (durSec < minGap && gapSec < minGap) continue
                }
                kept.add(n)
            }
            ns = kept
        }

        val melodyTimes = HashMap<String, MutableList<Double>>()
        for (n in ns) {
            if (n.voice == "melody") {
                melodyTimes.getOrPut(n.slot.id) { mutableListOf() }.add(n.start * baseBeatSec)
            }
        }

        fun nearMelody(n: Note): Boolean {
            if (n.voice != "harmony") return false
            val t = n.start * baseBeatSec
            return melodyTimes[n.slot.id]?.any { abs(t - it) < 0.035 } == true
        }

        val nextSame = IdentityHashMap<Note, Double>()
        val lastStart = HashMap<String, Double>()
        for (n in ns.asReversed()) {
            lastStart[n.slot.id]?.let { nextSame[n] = it }
            lastStart[n.slot.id] = n.start
        }

        val melodyOnsetsBeats = ns.filter { it.voice == "melody" }.map { it.start }.sorted()
        var sustainStepBeats = 2.0
        if (sustain && sustainInterval > 0) {
            val idealBeats = sustainInterval / max(0.05, baseBeatSec)
            sustainStepBeats = max(1.0, round(idealBeats))
            if (sustainStepBeats >= 3.0) {
                sustainStepBeats = if (idealBeats >= 3.2) 4.0 else 2.0
            }
        }

        val sustainBuckets = sortedMapOf<Double, MutableList<Triple<Double, KeySlot, Int>>>()
        val events = mutableListOf<PlayEvent>()

        val groups = mutableListOf<MutableList<Note>>()
        for (n in ns) {
            if (groups.isNotEmpty() && abs(n.start - groups.last()[0].start) < 1e-6) {
                groups.last().add(n)
            } else {
                groups.add(mutableListOf(n))
            }
        }

        for (group in groups) {
            val startSec = group[0].start * beatSec
            val seenKeys = HashSet<String>()
            for (n in group) {
                if (n.slot.id in seenKeys || nearMelody(n)) continue
                seenKeys.add(n.slot.id)

                val rawHold = n.dur * beatSec * EngineConstants.GATE_RATIO
                var hold = max(gateSec, min(rawHold, EngineConstants.MAX_HOLD_SEC))

                val nxtStart = nextSame[n]
                if (nxtStart != null) {
                    val gapBeats = nxtStart - n.start
                    val gapSec = gapBeats * baseBeatSec
                    if (gapSec < EngineConstants.MIN_FAST_GATE + EngineConstants.MIN_KEY_GAP_SEC) continue
                    val playLimit = gapBeats * beatSec - EngineConstants.MIN_KEY_GAP_SEC
                    hold = min(hold, max(EngineConstants.MIN_FAST_GATE, playLimit))
                }

                events.add(PlayEvent(startSec, 0, n.slot, hold))

                if (sustain && n.dur * baseBeatSec >= sustainMin && sustainInterval > 0) {
                    val isMidiHarmony = n.src == "midi" && n.voice != "melody"
                    val tailBeats = max(0.5, EngineConstants.SUSTAIN_TAIL_SEC / max(0.05, beatSec))
                    val endBeat = n.start + n.dur * EngineConstants.GATE_RATIO
                    val nxtLimit = if (nxtStart != null) nxtStart - tailBeats else Double.POSITIVE_INFINITY
                    val maxBeat = min(endBeat - tailBeats, nxtLimit)

                    var b = n.start + sustainStepBeats
                    while (b <= maxBeat) {
                        if (!(isMidiHarmony && hasOnsetNear(melodyOnsetsBeats, b, 0.6))) {
                            val key = round(b * 10000.0) / 10000.0
                            sustainBuckets.getOrPut(key) { mutableListOf() }
                                .add(Triple(b, n.slot, n.midi ?: 0))
                        }
                        b += sustainStepBeats
                    }
                }
            }
        }

        for ((_, items) in sustainBuckets) {
            items.sortBy { it.third }
            val sustainHold = max(gateSec, 0.032)
            items.take(3).forEachIndexed { idx, (b, slot, _) ->
                val t = b * beatSec + idx * EngineConstants.SUSTAIN_ROLL_SEC
                events.add(PlayEvent(t, 0, slot, sustainHold))
            }
        }

        return normalizeKeyEvents(events, gateSec)
    }
}
