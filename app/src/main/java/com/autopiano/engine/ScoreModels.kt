package com.autopiano.engine

import android.content.Context
import android.graphics.PointF
import org.json.JSONObject

class ScoreError(val line: Int, val col: Int, message: String) : Exception(
    if (line > 0) "第 $line 行 第 ${col + 1} 列: $message" else message
)

data class Note(
    val slot: KeySlot,
    val start: Double,      // 单位：拍
    val dur: Double,        // 单位：拍
    val src: String = "",
    val voice: String = "", // "melody" / "harmony" / ""
    val midi: Int? = null
)

data class ScoreMeta(
    var isMidi: Boolean = false,
    var midiShift: Int = 0,
    var midiMelodyTrack: Int? = null,
    var midiMelodyTrackName: String = "",
    var midiMelodyNotes: Int = 0,
    var midiTotalNotes: Int = 0
)

class Score(
    var bpm: Double = EngineConstants.DEFAULT_BPM,
    val notes: MutableList<Note> = mutableListOf(),
    val meta: ScoreMeta = ScoreMeta()
) {
    val lengthBeats: Double
        get() = if (notes.isEmpty()) 0.0 else notes.maxOf { it.start + it.dur }

    fun sortedNotes(): List<Note> =
        notes.sortedWith(compareBy<Note> { it.start }.thenBy { it.slot.row }.thenBy { it.slot.col })
}

data class PlayEvent(
    val t: Double,          // 秒
    val action: Int,        // 0=按下 1=抬起
    val slot: KeySlot,
    val hold: Double = EngineConstants.MIN_GATE
)

/**
 * 屏幕坐标校准配置：记录某个游戏布局在当前屏幕上的左上角与右下角中心点，
 * 自动插值计算 rows × cols 每个琴键的 (x, y) 点击坐标。
 */
data class CalibrationProfile(
    val presetId: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val rotation: Int = 0
) {
    /** 校准值和无障碍手势统一使用屏幕绝对坐标。 */
    fun fitsViewport(leftEdge: Float, topEdge: Float, width: Int, height: Int): Boolean =
        listOf(left, top, right, bottom).all { it.isFinite() } &&
            left >= leftEdge && top >= topEdge &&
            right <= leftEdge + width && bottom <= topEdge + height &&
            right - left >= 48f && bottom - top >= 48f

    fun forViewport(leftEdge: Float, topEdge: Float, width: Int, height: Int): CalibrationProfile {
        if (fitsViewport(leftEdge, topEdge, width, height)) return this
        return copy(
            left = leftEdge + width * 0.14f,
            top = topEdge + height * 0.54f,
            right = leftEdge + width * 0.86f,
            bottom = topEdge + height * 0.84f
        )
    }
    fun pointFor(slot: KeySlot, rows: Int, cols: Int): PointF {
        val x = if (cols <= 1) (left + right) * 0.5f
        else left + (right - left) * (slot.col.toFloat() / (cols - 1))
        val y = if (rows <= 1) (top + bottom) * 0.5f
        else top + (bottom - top) * (slot.row.toFloat() / (rows - 1))
        return PointF(x, y)
    }

    fun toJson(): String = JSONObject().apply {
        put("presetId", presetId)
        put("left", left.toDouble())
        put("top", top.toDouble())
        put("right", right.toDouble())
        put("bottom", bottom.toDouble())
        put("screenWidth", screenWidth)
        put("screenHeight", screenHeight)
        put("rotation", rotation)
    }.toString()

    companion object {
        fun defaultFor(preset: KeymapPreset, screenW: Int, screenH: Int, rotation: Int = 0): CalibrationProfile =
            CalibrationProfile(
                presetId = preset.id,
                left = screenW * 0.14f,
                top = screenH * 0.54f,
                right = screenW * 0.86f,
                bottom = screenH * 0.84f,
                screenWidth = screenW,
                screenHeight = screenH,
                rotation = rotation
            )

        private fun key(presetId: String, width: Int, height: Int, rotation: Int) =
            "cal_${presetId}_${width}x${height}_r$rotation"

        fun load(ctx: Context, preset: KeymapPreset, screenW: Int, screenH: Int, rotation: Int = 0): CalibrationProfile {
            val fallback = defaultFor(preset, screenW, screenH, rotation)
            // 不沿用无尺寸信息的旧版配置，避免把竖屏绝对坐标套在横屏上。
            val raw = ctx.getSharedPreferences("autopiano_cal", Context.MODE_PRIVATE)
                .getString(key(preset.id, screenW, screenH, rotation), null) ?: return fallback
            return try {
                val obj = JSONObject(raw)
                val profile = CalibrationProfile(
                    presetId = preset.id,
                    left = obj.getDouble("left").toFloat(),
                    top = obj.getDouble("top").toFloat(),
                    right = obj.getDouble("right").toFloat(),
                    bottom = obj.getDouble("bottom").toFloat(),
                    screenWidth = screenW,
                    screenHeight = screenH,
                    rotation = rotation
                )
                if (profile.fitsViewport(0f, 0f, screenW, screenH)) profile else fallback
            } catch (_: Exception) {
                fallback
            }
        }

        fun save(ctx: Context, profile: CalibrationProfile) {
            require(profile.screenWidth > 0 && profile.screenHeight > 0)
            require(profile.fitsViewport(0f, 0f, profile.screenWidth, profile.screenHeight))
            ctx.getSharedPreferences("autopiano_cal", Context.MODE_PRIVATE)
                .edit()
                .putString(key(profile.presetId, profile.screenWidth, profile.screenHeight, profile.rotation), profile.toJson())
                .apply()
        }
    }
}

object EngineConstants {
    const val DEFAULT_BPM = 100.0
    const val GATE_RATIO = 0.90
    const val MIN_GATE = 0.040
    const val MAX_HOLD_SEC = 0.065
    const val MIN_NOTE_GAP_SEC = 0.045
    const val MIN_KEY_GAP_SEC = 0.010
    const val MIN_FAST_GATE = 0.016

    const val MIDI_MIN_VELOCITY = 1
    const val MIDI_MAX_POLYPHONY = 3
    const val MIDI_CHORD_WINDOW_SEC = 0.012
    const val MIDI_OCTAVE_SHIFT_PENALTY = 2.0
    const val MIDI_FAST_MELODY_SEC = 0.22

    const val SUSTAIN_MIN_SEC = 2.0
    const val SUSTAIN_INTERVAL_SEC = 1.8
    const val SUSTAIN_TAIL_SEC = 0.55
    const val SUSTAIN_ROLL_SEC = 0.018
}
