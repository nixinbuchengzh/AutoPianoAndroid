package com.autopiano.ui

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.autopiano.R

/**
 * 启动入口：校验卡密后才允许进入主界面。
 */
class UnlockActivity : AppCompatActivity() {

    private lateinit var codeInput: EditText
    private lateinit var errorText: TextView
    private lateinit var rememberBox: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 已解锁则直接进入，避免闪一下解锁页。
        if (AccessGuard.isUnlocked(this)) {
            enterMain()
            return
        }

        setContentView(R.layout.activity_unlock)
        codeInput = findViewById(R.id.et_access_code)
        errorText = findViewById(R.id.tv_access_error)
        rememberBox = findViewById(R.id.cb_remember)

        val submit = {
            val ok = codeInput.text.toString().trim() == AccessGuard.ACCESS_CODE
            if (ok) {
                AccessGuard.unlock(this, rememberBox.isChecked)
                enterMain()
            } else {
                errorText.visibility = TextView.VISIBLE
                codeInput.text.clear()
                codeInput.requestFocus()
            }
        }

        findViewById<Button>(R.id.btn_unlock).setOnClickListener { submit() }
        codeInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else false
        }
    }

    private fun enterMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
