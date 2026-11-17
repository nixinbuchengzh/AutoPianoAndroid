package com.autopiano.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.view.accessibility.AccessibilityEvent

class AutoPianoAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不需要监听界面文字，只用 dispatchGesture 注入点击
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    fun tapChord(points: List<PointF>, holdMs: Long) {
        if (points.isEmpty()) return
        val builder = GestureDescription.Builder()
        val safeHold = holdMs.coerceIn(20L, 120L)
        for (pt in points.take(10)) {
            val path = Path().apply {
                moveTo(pt.x, pt.y)
                lineTo(pt.x + 0.5f, pt.y + 0.5f)
            }
            builder.addStroke(GestureDescription.StrokeDescription(path, 0L, safeHold))
        }
        dispatchGesture(builder.build(), null, null)
    }

    companion object {
        @Volatile
        var instance: AutoPianoAccessibilityService? = null
            private set
    }
}
