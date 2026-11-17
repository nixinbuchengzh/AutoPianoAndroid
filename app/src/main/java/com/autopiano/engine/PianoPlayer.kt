package com.autopiano.engine

import android.graphics.PointF
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

class PianoPlayer(
    var score: Score,
    var preset: KeymapPreset,
    var calibration: CalibrationProfile,
    var bpm: Double = score.bpm,
    var loop: Boolean = false,
    var sustain: Boolean = false,
    private val dispatchChord: (List<PointF>, Long) -> Unit,
    private val onProgress: (curSec: Double, totalSec: Double) -> Unit,
    private val onState: (state: String) -> Unit
) {
    @Volatile var state: String = "idle"
        private set

    @Volatile private var events: List<PlayEvent> = emptyList()
    @Volatile private var pausedPosSec: Double = 0.0
    @Volatile private var currentPosSec: Double = 0.0
    @Volatile private var seekReqSec: Double? = null
    @Volatile private var paused: Boolean = false

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val durationSec: Double
        get() {
            val base = score.lengthBeats * 60.0 / max(1.0, bpm)
            val evMax = events.maxOfOrNull { it.t } ?: 0.0
            return max(base, evMax)
        }

    fun rebuild() {
        events = EventBuilder.build(score, bpm, sustain = sustain)
    }

    fun start(fromSec: Double = 0.0) {
        if (job?.isActive == true) {
            resume()
            return
        }
        rebuild()
        paused = false
        seekReqSec = null
        pausedPosSec = max(0.0, fromSec)
        setState("playing")
        job = scope.launch { runLoop() }
    }

    fun pause() {
        if (job?.isActive == true) {
            paused = true
            setState("paused")
        }
    }

    fun resume() {
        if (job?.isActive == true) {
            paused = false
            setState("playing")
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        paused = false
        pausedPosSec = 0.0
        currentPosSec = 0.0
        setState("idle")
    }

    fun seek(sec: Double): Double {
        val clamped = sec.coerceIn(0.0, max(0.0, durationSec))
        when (state) {
            "playing" -> seekReqSec = clamped
            else -> {
                pausedPosSec = clamped
                currentPosSec = clamped
            }
        }
        return clamped
    }

    fun setTempo(newBpm: Double): Double {
        val safeBpm = max(1.0, newBpm)
        val atSec = if (state == "playing") currentPosSec else pausedPosSec
        val beat = atSec * bpm / 60.0
        bpm = safeBpm
        rebuild()
        val newSec = (beat * 60.0 / safeBpm).coerceIn(0.0, max(0.0, durationSec))
        if (state == "playing") {
            seekReqSec = newSec
        } else {
            pausedPosSec = newSec
            currentPosSec = newSec
        }
        return newSec
    }

    private fun setState(s: String) {
        if (state != s) {
            state = s
            onState(s)
        }
    }

    private suspend fun runLoop() {
        try {
            do {
                if (!runOnce()) break
            } while (loop && coroutineContext.isActive)
            if (coroutineContext.isActive) {
                setState("finished")
            }
        } catch (_: CancellationException) {
            setState("idle")
        }
    }

    private suspend fun runOnce(): Boolean {
        var evs = events.filter { it.action == 0 }
        if (evs.isEmpty()) return false

        var startSec = pausedPosSec
        var dur = durationSec
        var idx = evs.indexOfFirst { it.t >= startSec }.let { if (it < 0) evs.size else it }
        var t0Ns = System.nanoTime() - (startSec * 1_000_000_000.0).toLong()
        var lastProgress = -1.0

        while (coroutineContext.isActive) {
            val req = seekReqSec
            if (req != null) {
                seekReqSec = null
                evs = events.filter { it.action == 0 }
                dur = durationSec
                startSec = req
                pausedPosSec = req
                currentPosSec = req
                idx = evs.indexOfFirst { it.t >= startSec }.let { if (it < 0) evs.size else it }
                t0Ns = System.nanoTime() - (startSec * 1_000_000_000.0).toLong()
                lastProgress = -1.0
                continue
            }

            if (paused) {
                pausedPosSec = (System.nanoTime() - t0Ns) / 1_000_000_000.0
                currentPosSec = pausedPosSec
                while (paused && coroutineContext.isActive) {
                    delay(20)
                }
                evs = events.filter { it.action == 0 }
                dur = durationSec
                startSec = pausedPosSec
                idx = evs.indexOfFirst { it.t >= startSec }.let { if (it < 0) evs.size else it }
                t0Ns = System.nanoTime() - (startSec * 1_000_000_000.0).toLong()
                lastProgress = -1.0
                continue
            }

            val now = (System.nanoTime() - t0Ns) / 1_000_000_000.0
            currentPosSec = now
            if (idx >= evs.size && now >= dur) break

            val nextT = if (idx < evs.size) evs[idx].t else dur
            val wait = nextT - now
            if (wait > 0.002) {
                if (now - lastProgress >= 0.05) {
                    lastProgress = now
                    onProgress(now.coerceIn(0.0, dur), dur)
                }
                delay(min(10L, (wait * 1000.0).toLong() - 1L).coerceAtLeast(1L))
                continue
            } else if (wait > 0) {
                continue
            }

            if (idx < evs.size) {
                // 将 10ms 内的同时音符打包成一次多指手势
                val anchorT = evs[idx].t
                val batch = mutableListOf<PlayEvent>()
                while (idx < evs.size && evs[idx].t - anchorT <= 0.010 && batch.size < 8) {
                    batch.add(evs[idx])
                    idx++
                }
                val points = batch.map { calibration.pointFor(it.slot, preset.rows, preset.cols) }
                val holdMs = (batch.maxOf { it.hold } * 1000.0).toLong().coerceIn(25L, 70L)
                dispatchChord(points, holdMs)
                if (anchorT - lastProgress >= 0.05) {
                    lastProgress = anchorT
                    onProgress(anchorT.coerceIn(0.0, dur), dur)
                }
            }
        }

        pausedPosSec = 0.0
        currentPosSec = dur
        onProgress(dur, dur)
        return true
    }
}
