package com.autopiano.service

import android.content.Context
import android.graphics.*
import android.os.Build
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

/** 网格始终绘制在实际窗口内，存储/触控使用屏幕绝对坐标（含刘海和系统栏偏移）。 */
class CalibrationOverlay(
    private val context: Context,
    private val wm: WindowManager,
    private val preset: KeymapPreset,
    private val initial: CalibrationProfile,
    private val onSaved: (CalibrationProfile) -> Unit,
    private val onDismissed: () -> Unit = {}
) {
    private var profile = initial
    private var rootView: FrameLayout? = null
    private val origin = IntArray(2)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun show() {
        if (rootView != null) return
        val root = FrameLayout(context)
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.argb(235, 21, 22, 27))
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val gridView = object : View(context) {
            private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(200, 90, 169, 255)
                strokeWidth = dp(1).toFloat()
                style = Paint.Style.STROKE
            }
            private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(99, 226, 160)
            }
            private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(255, 184, 108)
                strokeWidth = dp(2).toFloat()
                style = Paint.Style.STROKE
            }
            private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = dp(12).toFloat()
                textAlign = Paint.Align.CENTER
            }
            private var dragging = 0
            private val radius = dp(18).toFloat()
            private val gap = maxOf(48f, dp(32).toFloat())
            private val edgeMargin = dp(24).toFloat()

            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                post {
                    if (rootView !== root || w <= 0 || h <= 0) return@post
                    getLocationOnScreen(origin)
                    // 等工具条测量完成；横屏不允许使用竖屏缓存或画到工具条后面。
                    val margin = dp(24)
                    val usableW = (width - margin * 2).coerceAtLeast(1)
                    val usableH = (height - bar.height - margin * 2).coerceAtLeast(1)
                    profile = profile.forViewport(
                        (origin[0] + margin).toFloat(),
                        (origin[1] + bar.height + margin).toFloat(),
                        usableW, usableH
                    )
                    invalidate()
                }
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                getLocationOnScreen(origin)
                canvas.drawColor(Color.argb(55, 0, 0, 0))
                canvas.save()
                canvas.translate(-origin[0].toFloat(), -origin[1].toFloat())
                canvas.drawRect(profile.left, profile.top, profile.right, profile.bottom, linePaint)
                for (r in 0 until preset.rows) {
                    for (c in 0 until preset.cols) {
                        val pt = profile.pointFor(KeySlot(r, c), preset.rows, preset.cols)
                        canvas.drawCircle(pt.x, pt.y, dp(4).toFloat(), dotPaint)
                    }
                }
                canvas.drawCircle(profile.left, profile.top, radius, handlePaint)
                canvas.drawText("左上", profile.left, profile.top - dp(22), textPaint)
                canvas.drawCircle(profile.right, profile.bottom, radius, handlePaint)
                canvas.drawText("右下", profile.right, profile.bottom - dp(22), textPaint)
                canvas.restore()
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                val x = event.rawX
                val y = event.rawY
                val minX = origin[0] + edgeMargin
                val minY = origin[1] + bar.height + edgeMargin
                val maxX = origin[0] + width - edgeMargin
                val maxY = origin[1] + height - edgeMargin
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dragging = when {
                            hypot(x - profile.left, y - profile.top) < dp(40) -> 1
                            hypot(x - profile.right, y - profile.bottom) < dp(40) -> 2
                            else -> 0
                        }
                        return true // 校准时不把触控传递给游戏。
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (maxX <= minX + gap || maxY <= minY + gap) return true
                        profile = when (dragging) {
                            1 -> profile.copy(
                                left = x.coerceIn(minX, profile.right - gap),
                                top = y.coerceIn(minY, profile.bottom - gap)
                            )
                            2 -> profile.copy(
                                right = x.coerceIn(profile.left + gap, maxX),
                                bottom = y.coerceIn(profile.top + gap, maxY)
                            )
                            else -> profile
                        }
                        invalidate()
                    }
                    MotionEvent.ACTION_UP -> {
                        dragging = 0
                        performClick()
                    }
                    MotionEvent.ACTION_CANCEL -> dragging = 0
                }
                return true
            }

            override fun performClick(): Boolean {
                super.performClick()
                return true
            }
        }
        root.addView(gridView, FrameLayout.LayoutParams(-1, -1))
        bar.addView(TextView(context).apply {
            text = "拖动首尾圆环对齐 ${preset.displayName}\n横竖屏独立保存，校准时已暂停弹奏"
            setTextColor(Color.WHITE)
            textSize = 11f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(Button(context).apply {
            text = "取消"
            textSize = 12f
            minWidth = dp(56)
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { dismiss() }
        })
        bar.addView(Button(context).apply {
            text = "保存"
            textSize = 12f
            minWidth = dp(56)
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener {
                CalibrationProfile.save(context, profile)
                onSaved(profile)
                dismiss()
            }
        })
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        val lp = WindowManager.LayoutParams(
            -1, -1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        wm.addView(root, lp)
        rootView = root
    }

    fun dismiss(notify: Boolean = true) {
        val view = rootView ?: return
        rootView = null
        try { wm.removeView(view) } catch (_: IllegalArgumentException) {}
        if (notify) onDismissed()
    }
}
