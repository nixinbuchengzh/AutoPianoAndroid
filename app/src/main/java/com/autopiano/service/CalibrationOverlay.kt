package com.autopiano.service

import android.content.Context
import android.graphics.*
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.autopiano.engine.CalibrationProfile
import com.autopiano.engine.KeySlot
import com.autopiano.engine.KeymapPreset
import kotlin.math.hypot

/**
 * 全屏半透明网格校准器：拖动左上角(A)和右下角(B)绿色圆环对准游戏琴键中心，
 * 支持原神 3×7 / 2×7、光遇 3×5 等任意布局。
 */
class CalibrationOverlay(
    private val context: Context,
    private val wm: WindowManager,
    private val preset: KeymapPreset,
    initial: CalibrationProfile,
    private val onSaved: (CalibrationProfile) -> Unit
) {
    private var left = initial.left
    private var top = initial.top
    private var right = initial.right
    private var bottom = initial.bottom

    private var rootView: FrameLayout? = null

    fun show() {
        val root = FrameLayout(context)
        val gridView = object : View(context) {
            private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(160, 90, 169, 255)
                strokeWidth = 3f
                style = Paint.Style.STROKE
            }
            private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(180, 99, 226, 160)
                style = Paint.Style.FILL
            }
            private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(230, 255, 184, 108)
                strokeWidth = 6f
                style = Paint.Style.STROKE
            }
            private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 30f
                textAlign = Paint.Align.CENTER
            }

            private var dragging = 0 // 1=topLeft, 2=bottomRight

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                canvas.drawColor(Color.argb(95, 0, 0, 0))
                canvas.drawRect(left, top, right, bottom, linePaint)

                val prof = CalibrationProfile(preset.id, left, top, right, bottom)
                for (r in 0 until preset.rows) {
                    for (c in 0 until preset.cols) {
                        val pt = prof.pointFor(KeySlot(r, c), preset.rows, preset.cols)
                        canvas.drawCircle(pt.x, pt.y, 14f, dotPaint)
                    }
                }
                canvas.drawCircle(left, top, 36f, handlePaint)
                canvas.drawText("左上", left, top - 44f, textPaint)
                canvas.drawCircle(right, bottom, 36f, handlePaint)
                canvas.drawText("右下", right, bottom + 64f, textPaint)
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val d1 = hypot(event.x - left, event.y - top)
                        val d2 = hypot(event.x - right, event.y - bottom)
                        dragging = when {
                            d1 < 90f -> 1
                            d2 < 90f -> 2
                            else -> 0
                        }
                        return dragging != 0
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (dragging == 1) {
                            left = event.x.coerceAtMost(right - 80f)
                            top = event.y.coerceAtMost(bottom - 60f)
                            invalidate()
                            return true
                        } else if (dragging == 2) {
                            right = event.x.coerceAtLeast(left + 80f)
                            bottom = event.y.coerceAtLeast(top + 60f)
                            invalidate()
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        dragging = 0
                    }
                }
                return super.onTouchEvent(event)
            }
        }

        root.addView(gridView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(220, 21, 22, 27))
            setPadding(24, 14, 24, 14)
        }
        val title = TextView(context).apply {
            text = "拖动「左上 / 右下」圆环对准【${preset.displayName}】首尾琴键"
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        val btnSave = Button(context).apply {
            text = "保存"
            setOnClickListener {
                val prof = CalibrationProfile(preset.id, left, top, right, bottom)
                CalibrationProfile.save(context, prof)
                dismiss()
                onSaved(prof)
            }
        }
        val btnCancel = Button(context).apply {
            text = "取消"
            setOnClickListener { dismiss() }
        }
        bar.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(btnCancel)
        bar.addView(btnSave)

        root.addView(bar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ))

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(root, lp)
        rootView = root
    }

    fun dismiss() {
        rootView?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        rootView = null
    }
}
