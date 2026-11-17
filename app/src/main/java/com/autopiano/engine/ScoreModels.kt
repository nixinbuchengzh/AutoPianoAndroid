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
    val bottom: Float
) {
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
    }.toString()

    companion object {
        fun defaultFor(preset: KeymapPreset, screenW: Int, screenH: Int): CalibrationProfile {
            // 横屏游戏默认居中偏下区域
            val w = screenW.toFloat()
            val h = screenH.toFloat()
            return CalibrationProfile(
                presetId = preset.id,
                left = w * 0.22f,
                top = h * 0.52f,
                right = w * 0.78f,
                bottom = h * 0.86f
            )
        }

        fun load(ctx: Context, preset: KeymapPreset, screenW: Int, screenH: Int): CalibrationProfile {
            val prefs = ctx.getSharedPreferences("autopiano_cal", Context.MODE_PRIVATE)
            val raw = prefs.getString("cal_${preset.id}", null)
                ?: return defaultFor(preset, screenW, screenH)
            return try {
                val obj = JSONObject(raw)
                CalibrationProfile(
                    presetId = preset.id,
                    left = obj.getDouble("left").toFloat(),
                    top = obj.getDouble("top").toFloat(),
                    right = obj.getDouble("right").toFloat(),
                    bottom = obj.getDouble("bottom").toFloat()
                )
            } catch (_: Exception) {
                defaultFor(preset, screenW, screenH)
            }
        }

        fun save(ctx: Context, profile: CalibrationProfile) {
            ctx.getSharedPreferences("autopiano_cal", Context.MODE_PRIVATE)
                .edit()
                .putString("cal_${profile.presetId}", profile.toJson())
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
