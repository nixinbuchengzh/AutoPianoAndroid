package com.autopiano.ui

import android.content.Context

/**
 * 启动卡密校验。
 * - 卡密正确后可选择记住解锁状态，避免每次冷启动都输入。
 * - sessionUnlocked 只在进程内存有效：未勾选记住时，下次冷启动仍需输入。
 * - relock 用于主界面主动重新上锁。
 */
object AccessGuard {
    const val ACCESS_CODE = "YF191178369"

    private const val PREF_NAME = "autopiano_access"
    private const val KEY_REMEMBER = "remember_unlocked"
    private var sessionUnlocked = false

    fun isUnlocked(context: Context): Boolean {
        if (sessionUnlocked) return true
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_REMEMBER, false)
    }

    fun unlock(context: Context, remember: Boolean) {
        sessionUnlocked = true
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_REMEMBER, remember)
            .apply()
    }

    fun relock(context: Context) {
        sessionUnlocked = false
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_REMEMBER, false)
            .apply()
    }
}
