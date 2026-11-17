package com.autopiano.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.autopiano.R
import com.autopiano.engine.LibraryScore
import com.autopiano.engine.ScoreLibrary
import com.autopiano.service.AutoPianoAccessibilityService
import com.autopiano.service.OverlayService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var permStatusTv: TextView
    private lateinit var libraryStatusTv: TextView
    private lateinit var libraryCountTv: TextView
    private lateinit var libraryEmptyTv: TextView
    private lateinit var libraryList: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var importButton: Button
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var scores: List<LibraryScore> = emptyList()
    private var libraryBusy = false
    private var loadJob: Job? = null
    private var pendingImports: List<Uri> = emptyList()

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) importScores(uris.distinct())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        permStatusTv = findViewById(R.id.tv_perm_status)
        libraryStatusTv = findViewById(R.id.tv_library_status)
        libraryCountTv = findViewById(R.id.tv_library_count)
        libraryEmptyTv = findViewById(R.id.tv_library_empty)
        libraryList = findViewById(R.id.library_list)
        searchInput = findViewById(R.id.et_library_search)
        importButton = findViewById(R.id.btn_pick_score)

        findViewById<Button>(R.id.btn_overlay_perm).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        findViewById<Button>(R.id.btn_acc_perm).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btn_start_overlay).setOnClickListener {
            startOverlay()
        }
        findViewById<Button>(R.id.btn_stop_overlay).setOnClickListener {
            loadJob?.cancel()
            val stopped = stopService(Intent(this, OverlayService::class.java))
            showMessage(if (stopped) "已停止悬浮服务" else "悬浮服务未运行")
        }
        importButton.setOnClickListener {
            pickFileLauncher.launch(arrayOf("*/*"))
        }
        searchInput.doAfterTextChanged { renderLibrary() }
        renderLibrary()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        refreshLibrary()
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }

    private fun updatePermissionStatus() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAcc = AutoPianoAccessibilityService.instance != null
        permStatusTv.text = "悬浮窗权限: ${if (hasOverlay) "已开启" else "未开启"}  |  无障碍服务: ${if (hasAcc) "已开启" else "未开启"}"
    }

    private fun startOverlay(score: LibraryScore? = null) {
        if (!Settings.canDrawOverlays(this)) {
            permStatusTv.text = "请先授予「悬浮窗权限」；导入曲库无需此权限"
            showMessage("请先开启悬浮窗权限")
            return
        }
        val intent = Intent(this, OverlayService::class.java).apply {
            if (score != null) putExtra("extra_library_id", score.id)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            if (score != null) {
                showMessage("已发送「${score.name}」到悬浮窗，请在悬浮窗中确认加载结果")
            } else {
                showMessage("已启动悬浮控制面板")
            }
        } catch (error: Exception) {
            showMessage("无法启动悬浮服务：${error.message ?: "请检查权限"}")
        }
    }

    private fun refreshLibrary() {
        if (libraryBusy) return
        setLibraryBusy(true)
        activityScope.launch {
            try {
                scores = withContext(Dispatchers.IO) { ScoreLibrary.list(applicationContext) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showMessage("读取曲库失败：${error.message ?: "未知错误"}")
            } finally {
                setLibraryBusy(false)
            }
        }
    }

    private fun importScores(uris: List<Uri>) {
        if (libraryBusy) {
            // 文件选择器返回时 onResume 可能仍在刷新曲库，不丢弃用户已选的文件。
            pendingImports = (pendingImports + uris).distinct()
            return
        }
        setLibraryBusy(true)
        activityScope.launch {
            var imported = 0
            var duplicates = 0
            val failures = mutableListOf<String>()
            try {
                val knownIds = withContext(Dispatchers.IO) {
                    ScoreLibrary.list(applicationContext).map { it.id }.toMutableSet()
                }
                for ((index, uri) in uris.withIndex()) {
                    ensureActive()
                    libraryStatusTv.text = "正在导入 ${index + 1}/${uris.size}…"
                    var name = "第 ${index + 1} 个文件"
                    try {
                        val score = withContext(Dispatchers.IO) {
                            name = queryFileName(uri) ?: name
                            ScoreLibrary.import(applicationContext, uri)
                        }
                        if (knownIds.add(score.id)) imported++ else duplicates++
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        failures.add("$name：${error.message ?: "无法读取文件"}")
                    }
                }
                scores = withContext(Dispatchers.IO) { ScoreLibrary.list(applicationContext) }
                val summary = "新增 $imported 首，已存在 $duplicates 首，失败 ${failures.size} 个"
                libraryStatusTv.text = if (failures.isEmpty()) summary
                    else "$summary\n${failures.joinToString("\n")}"
                Toast.makeText(this@MainActivity, summary, Toast.LENGTH_LONG).show()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showMessage("曲库处理失败：${error.message ?: "未知错误"}")
            } finally {
                setLibraryBusy(false)
            }
        }
    }

    private fun loadScore(score: LibraryScore) {
        if (libraryBusy) return
        if (!Settings.canDrawOverlays(this)) {
            startOverlay(score)
            return
        }
        setLibraryBusy(true)
        libraryStatusTv.text = "正在读取「${score.name}」…"
        loadJob = activityScope.launch {
            try {
                val storedScore = withContext(Dispatchers.IO) {
                    ScoreLibrary.read(applicationContext, score.id).first
                }
                startOverlay(storedScore)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showMessage("加载失败：${error.message ?: "曲谱不可用"}")
            } finally {
                loadJob = null
                setLibraryBusy(false)
            }
        }
    }

    private fun confirmDelete(score: LibraryScore) {
        if (libraryBusy) return
        AlertDialog.Builder(this)
            .setTitle("删除本地曲谱")
            .setMessage("确定从本地曲库删除「${score.name}」吗？此操作不可恢复，不会删除原始文件。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> deleteScore(score) }
            .show()
    }

    private fun deleteScore(score: LibraryScore) {
        if (libraryBusy) {
            showMessage("曲库正在处理，请稍后再删除")
            return
        }
        setLibraryBusy(true)
        activityScope.launch {
            try {
                val deleted = withContext(Dispatchers.IO) {
                    ScoreLibrary.delete(applicationContext, score.id)
                }
                scores = withContext(Dispatchers.IO) { ScoreLibrary.list(applicationContext) }
                showMessage(if (deleted) "已删除「${score.name}」" else "删除未完成或曲谱已不存在")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showMessage("删除失败：${error.message ?: "未知错误"}")
            } finally {
                setLibraryBusy(false)
            }
        }
    }

    private fun setLibraryBusy(busy: Boolean) {
        libraryBusy = busy
        importButton.isEnabled = !busy
        renderLibrary()
        if (!busy && pendingImports.isNotEmpty() && !isDestroyed) {
            val uris = pendingImports
            pendingImports = emptyList()
            importScores(uris)
        }
    }

    private fun renderLibrary() {
        val query = searchInput.text.toString().trim()
        val filtered = scores.filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
        libraryCountTv.text = if (query.isEmpty()) "本地曲库 · ${scores.size} 首"
            else "本地曲库 · ${filtered.size}/${scores.size} 首"
        libraryEmptyTv.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        libraryEmptyTv.text = if (scores.isEmpty()) "尚未导入曲谱，请先批量导入文件。" else "没有匹配的曲名。"
        libraryList.removeAllViews()
        for (score in filtered) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val loadButton = Button(this).apply {
                text = "${score.name}\n${if (score.isMidi) "MIDI" else "TXT"} · 点击加载到悬浮窗"
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                isEnabled = !libraryBusy
                setOnClickListener { loadScore(score) }
            }
            val deleteButton = Button(this).apply {
                text = "删除"
                contentDescription = "删除 ${score.name}"
                isEnabled = !libraryBusy
                setOnClickListener { confirmDelete(score) }
            }
            row.addView(loadButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(deleteButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            libraryList.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun queryFileName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && column >= 0 && !cursor.isNull(column)) cursor.getString(column) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun showMessage(message: String) {
        libraryStatusTv.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
