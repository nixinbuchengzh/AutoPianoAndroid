package com.autopiano.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.autopiano.R
import com.autopiano.service.AutoPianoAccessibilityService
import com.autopiano.service.OverlayService

class MainActivity : AppCompatActivity() {

    private lateinit var permStatusTv: TextView

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val name = queryFileName(uri) ?: "score"
            val it = Intent(this, OverlayService::class.java).apply {
                putExtra(OverlayService.EXTRA_URI, uri.toString())
                putExtra(OverlayService.EXTRA_NAME, name)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(it)
            } else {
                startService(it)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        permStatusTv = findViewById(R.id.tv_perm_status)

        findViewById<Button>(R.id.btn_overlay_perm).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }

        findViewById<Button>(R.id.btn_acc_perm).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btn_start_overlay).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                permStatusTv.text = "请先授予「悬浮窗权限」"
                return@setOnClickListener
            }
            val it = Intent(this, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(it)
            } else {
                startService(it)
            }
        }

        findViewById<Button>(R.id.btn_pick_score).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                permStatusTv.text = "请先授予「悬浮窗权限」"
                return@setOnClickListener
            }
            pickFileLauncher.launch(arrayOf("*/*"))
        }
    }

    override fun onResume() {
        super.onResume()
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAcc = AutoPianoAccessibilityService.instance != null
        permStatusTv.text = "悬浮窗权限: ${if (hasOverlay) "已开启" else "未开启"}  |  无障碍服务: ${if (hasAcc) "已开启" else "未开启"}"
    }

    private fun queryFileName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return uri.lastPathSegment
    }
}
